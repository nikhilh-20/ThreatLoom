import json
import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

from config import load_config
from cost_tracker import cost_tracker
from database import get_unsummarized_articles, save_summary, get_articles_for_category, _format_entity_name
from llm_client import has_api_key, get_model_name, call_llm

logger = logging.getLogger(__name__)

RELEVANCE_PROMPT = """You are a cybersecurity threat-intelligence triage analyst.
Classify each article title as RELEVANT or IRRELEVANT to cybersecurity threat research.

RELEVANT — include if the article is about ANY of these:
  Malware analysis, exploits, vulnerabilities (CVEs), attack campaigns, threat actors/APTs,
  zero-days, supply chain attacks, security advisories, novel attack techniques or tooling,
  breach investigations with technical details, proof-of-concept exploits, offensive/defensive
  security tool releases, emerging security technologies, botnets, ransomware operations,
  exploit kits, C2 infrastructure, phishing campaigns, firmware/hardware security research.

IRRELEVANT — exclude if the article is ONLY about:
  Business/financial news, regulatory or legal actions, privacy policy changes, fines or lawsuits,
  mergers & acquisitions, product marketing, career/hiring, opinion without technical substance,
  awards, conference announcements, stock prices, executive appointments.

IMPORTANT: When in doubt, classify as RELEVANT. It is much better to include a borderline
article than to miss a genuine threat.

Titles:
{titles}

Respond with a JSON object: {{"relevant": [true, false, ...]}} — one boolean per title, same order."""

SUMMARY_PROMPT = """You are a senior cybersecurity threat intelligence analyst.
Given an article title and its full content, produce a structured analysis as a JSON object
with these exact keys:

- "executive_summary": A concise paragraph (3-5 sentences) capturing the essence and
  significance of the threat, vulnerability, or finding. Be precise and informative.

- "novelty": Describe what is novel or noteworthy about the reported threat actor tactics,
  techniques, and tooling (TTPs). Be specific — mention exact techniques, tools, or
  behavioral patterns that are new or unusual. If nothing is particularly novel, say so briefly.

- "details": A JSON array of strings. Each string is one detailed bullet point covering an
  important technical finding from the article. Be thorough and accurate — do NOT skip any
  significant detail. Include IOCs, affected systems/versions, attack chains, CVE IDs,
  CVSS scores, technical specifics, timelines, and attribution where available.

- "mitigations": A JSON array of strings. Each string is one actionable mitigation step or
  defensive recommendation against the described attack or vulnerability. Include patching
  guidance, detection rules, configuration hardening, and workarounds where mentioned.

- "tags": A JSON array of 3-8 lowercase hyphenated tags categorizing the article. Include:
  * General category tags — use ONLY simple standard terms: "ransomware", "malware", "phishing",
    "vulnerability", "data-leak", "supply-chain", "botnet", "c2", "iot", etc.
    Do NOT invent compound descriptive tags (e.g., NEVER "offline-ransomware", "advanced-phishing",
    "cyber-strategy", "government-hacking"). Keep category tags to 1-2 standard words.
  * Specific threat actor or APT group names ONLY if explicitly named in the article.
    Use their canonical MITRE ATT&CK name in lowercase hyphenated form:
    "apt29", "apt42", "lazarus-group", "scattered-spider", "volt-typhoon",
    "mustang-panda", "sandworm-team", "magic-hound", "kimsuky", "turla", etc.
    See https://attack.mitre.org/groups/ for the authoritative list.
  * Specific malware family or offensive tool names ONLY if explicitly named in the article.
    Use their canonical MITRE ATT&CK name in lowercase hyphenated form:
    "emotet", "cobalt-strike", "qakbot", "darkgate", "lumma-stealer",
    "sliver", "brute-ratel-c4", "raspberry-robin", "socgholish", etc.
    See https://attack.mitre.org/software/ for the authoritative list.
    When the article mentions a specific version (e.g., "LockBit 3.0"), use it: "lockbit-3.0".
    When the article mentions only the family name (e.g., "LockBit"), use the base name: "lockbit".
  * CVE IDs if mentioned (e.g., "cve-2024-1234")
  Prefer real named entities over generic labels. Every tag must be EITHER a standard
  category keyword OR a real MITRE ATT&CK entity name — never an invented description.

- "attack_flow": A JSON array representing the attack chain / kill chain as ordered steps.
  Each step is an object with these keys:
    * "phase": The MITRE ATT&CK tactic name (e.g., "Initial Access", "Execution",
      "Persistence", "Privilege Escalation", "Defense Evasion", "Credential Access",
      "Discovery", "Lateral Movement", "Collection", "Exfiltration", "Impact",
      "Reconnaissance", "Resource Development"). Use the closest matching tactic.
    * "title": A short, specific title for this step of the attack (e.g.,
      "Spear-Phishing with Weaponized Document").
    * "description": 2-3 sentences describing what happened in this step, specific to
      the article's content. Be concrete — mention the actual tools, files, CVEs, or
      techniques used.
    * "technique": The MITRE ATT&CK technique ID if applicable (e.g., "T1566.001",
      "T1059.001"). Use "" if no specific technique maps clearly.
  If the article describes a clear attack sequence or campaign, capture each phase in order.
  If the article does NOT describe an attack sequence (e.g., it is a vulnerability disclosure,
  policy piece, or tool release), return an empty array [].

CRITICAL: Be exhaustive and thorough in your analysis. NEVER skip or omit information
that could be relevant to a threat analyst. When in doubt, ALWAYS include information
rather than leave it out. Every IOC, every CVE, every technique, every tool name,
every affected system, every timeline detail matters. Incomplete analysis is worse
than verbose analysis. Cover EVERYTHING the article reports.

Be accurate and precise. When in doubt, include content rather than skip it.
Respond ONLY with valid JSON."""


def _is_rate_limit_error(exc):
    """Return True if the exception looks like a rate-limit (429) response."""
    name = type(exc).__name__
    msg = str(exc).lower()
    return name == "RateLimitError" or "429" in msg or "rate limit" in msg


def check_relevance(titles):
    """Batch-classify article titles for threat-research relevance via LLM.

    Sends titles in batches of 25 to the configured OpenAI model for
    binary classification. If the API key is not configured or a batch
    fails, all titles in that batch default to relevant.

    Args:
        titles: List of article title strings to classify.

    Returns:
        List of booleans, same length as ``titles``. True means the
        title is relevant to cybersecurity threat research.
    """
    if not titles:
        return []

    if not has_api_key():
        return [True] * len(titles)  # No API key → accept all

    results = []
    BATCH = 25

    for i in range(0, len(titles), BATCH):
        batch = titles[i : i + BATCH]
        numbered = "\n".join(f'{j + 1}. "{t}"' for j, t in enumerate(batch))
        prompt = RELEVANCE_PROMPT.format(titles=numbered)

        try:
            content, it, ot = call_llm(
                None,
                [{"role": "user", "content": prompt}],
                temperature=0,
                max_tokens=300,
                json_mode=True,
            )
            cost_tracker.add_tokens(it, ot)
            data = json.loads(content)
            batch_results = data.get("relevant", [True] * len(batch))
            # Pad if the model returned fewer entries than expected
            while len(batch_results) < len(batch):
                batch_results.append(True)
            results.extend(batch_results[: len(batch)])
        except Exception as e:
            logger.error(f"Relevance check failed for batch: {e}")
            results.extend([True] * len(batch))  # Accept all on error

    return results


def _compose_markdown(data):
    """Build a markdown summary from structured LLM JSON output.

    Assembles sections for executive summary, novelty notes, details,
    and mitigations into a single markdown string.

    Args:
        data: Dictionary with keys ``executive_summary``, ``novelty``,
            ``details`` (list), and ``mitigations`` (list).

    Returns:
        Formatted markdown string with headed sections.
    """
    sections = []

    sections.append("# Executive Summary")
    sections.append(data.get("executive_summary", "No summary available."))
    sections.append("")

    sections.append("# Novelty about reported threat actor tactics, techniques, and tooling")
    sections.append(data.get("novelty", "Nothing particularly novel reported."))
    sections.append("")

    sections.append("# Details")
    for point in data.get("details", []):
        sections.append(f"- {point}")
    sections.append("")

    sections.append("# Mitigations")
    for point in data.get("mitigations", []):
        sections.append(f"- {point}")

    return "\n".join(sections)


def summarize_article(title, content):
    """Summarize a single article using the OpenAI API.

    Sends the article title and content (truncated to 12,000 chars) to
    the configured model with a structured analysis prompt. Retries up
    to 3 times on rate limits or API errors.

    Args:
        title: The article headline.
        content: The full article text.

    Returns:
        A dict with keys ``summary`` (markdown string), ``tags``
        (list of strings), ``attack_flow`` (list of phase dicts), and
        ``novelty`` (string). Returns None if the API key is missing
        or all retries fail.
    """
    if not has_api_key():
        logger.warning("LLM API key not configured, skipping summarization")
        return None

    # Truncate content to stay within token limits
    max_chars = 12000
    if len(content) > max_chars:
        content = content[:max_chars] + "\n\n[Content truncated...]"

    user_message = f"Title: {title}\n\nArticle Content:\n{content}"

    for attempt in range(3):
        try:
            content_str, it, ot = call_llm(
                SUMMARY_PROMPT,
                [{"role": "user", "content": user_message}],
                temperature=0.3,
                max_tokens=2500,
                json_mode=True,
            )
            cost_tracker.add_tokens(it, ot)
            result = json.loads(content_str)
            summary_md = _compose_markdown(result)

            return {
                "summary": summary_md,
                "tags": result.get("tags", []),
                "attack_flow": result.get("attack_flow", []),
                "novelty": result.get("novelty", ""),
                "raw_data": result,
            }

        except Exception as e:
            if _is_rate_limit_error(e):
                wait = 2 ** (attempt + 1)
                logger.warning(f"Rate limited, waiting {wait}s before retry")
                time.sleep(wait)
            elif attempt < 2:
                logger.error(f"Summarization error (attempt {attempt + 1}): {e}")
                time.sleep(1)
            else:
                logger.error(f"All summarization retries failed: {e}")
                return None

    return None


TREND_FORECAST_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.

You are given a set of recent threat-intelligence article summaries, all belonging to the
category "{category}".

Produce a JSON object with exactly two keys:

1. "trend" — A concise analysis (3-6 paragraphs of markdown) of how this threat category is
   evolving right now. Cover:
   * Evolving TTPs (tactics, techniques, and procedures)
   * New tools, malware families, or infrastructure being adopted
   * Shifts in targeting (industries, geographies, platforms)
   * Notable behavioral changes compared to earlier activity

2. "forecast" — A forward-looking assessment (2-4 paragraphs of markdown) predicting where
   this category is headed over the next 3-6 months. Cover:
   * Most likely developments and escalation paths
   * Emerging risks defenders should prepare for
   * Recommended priority areas for security teams

Use markdown formatting (headings, bold, bullet lists) to make the text scannable.
Be specific and cite patterns you observe in the provided articles.
Respond ONLY with valid JSON."""


def estimate_insight_cost(article_count, model):
    """Estimate the LLM cost for a single category insight (forecast) call.

    Args:
        article_count: Number of articles that will be included.
        model: Model name string.

    Returns:
        Estimated cost in USD as a float.
    """
    from cost_tracker import _lookup_pricing
    inp_price, out_price = _lookup_pricing(model)
    # ~200 tokens per article for exec-summary context, capped at 5000, plus ~200 system tokens
    estimated_input = min(article_count * 200, 5000) + 200
    return (estimated_input * inp_price + 2000 * out_price) / 1_000_000


def estimate_trend_cost(articles, model):
    """Estimate the LLM cost for a full trend analysis run.

    Args:
        articles: List of article dicts (used to compute quarter groups).
        model: Model name string.

    Returns:
        Tuple of (estimated_cost_usd, n_quarters, n_years).
    """
    from cost_tracker import _lookup_pricing
    inp_price, out_price = _lookup_pricing(model)
    groups = _group_by_quarter(articles)
    n_quarters = len(groups)
    n_years = len({year for (year, _) in groups})
    # Batch summaries are needed when any quarter has >50 articles
    n_batches = sum(max(0, (len(q_arts) - 1) // 50) for q_arts in groups.values())
    total_input = n_quarters * 3000 + n_years * 8000 + n_batches * 15000
    total_output = n_quarters * 2500 + n_years * 3000 + n_batches * 1500
    cost = (total_input * inp_price + total_output * out_price) / 1_000_000
    return cost, n_quarters, n_years


def generate_category_insight(category_name, subcategory_tag=None, since_days=None):
    """Generate trend analysis and forecast for a threat category.

    Retrieves all summarized articles for the given category (and
    optionally a subcategory entity), then asks the LLM to produce a
    trend analysis and forward-looking forecast.

    Args:
        category_name: Broad category name (e.g. ``"Malware"``).
        subcategory_tag: Optional entity tag to narrow the focus
            (e.g. ``"lockbit"``).
        since_days: If set, only include articles from the last N days.

    Returns:
        A dict with keys ``trend`` (markdown), ``forecast`` (markdown),
        ``article_count`` (int), and ``model_used`` (str). Returns None
        if fewer than 3 articles are available or the API key is missing.
    """
    if not has_api_key():
        logger.warning("LLM API key not configured, skipping insight generation")
        return None

    articles = get_articles_for_category(category_name, subcategory_tag=subcategory_tag, since_days=since_days)
    if len(articles) < 3:
        return None

    # Build context label for the prompt
    if subcategory_tag:
        display_name = _format_entity_name(subcategory_tag)
        context_label = f"{category_name} > {display_name}"
    else:
        context_label = category_name

    # Build input: newest articles first, title + date + executive summary extract
    lines = []
    for art in articles:
        date_str = art.get("published_date") or "unknown date"
        summary = art.get("summary_text") or ""
        match = re.search(r"# Executive Summary\s*\n([\s\S]*?)(?=\n#|\n*$)", summary, re.IGNORECASE)
        exec_match = match.group(1).strip() if match else summary[:500]
        lines.append(f"- **{art['title']}** ({date_str}): {exec_match}")

    input_text = "\n".join(lines)
    if len(input_text) > 20000:
        input_text = input_text[:20000] + "\n\n[Truncated...]"

    prompt = TREND_FORECAST_PROMPT.format(category=context_label)
    user_message = f"Category: {context_label}\nArticle count: {len(articles)}\n\n{input_text}"

    for attempt in range(3):
        try:
            content, it, ot = call_llm(
                prompt,
                [{"role": "user", "content": user_message}],
                temperature=0.4,
                max_tokens=2000,
                json_mode=True,
            )
            cost_tracker.add_tokens(it, ot)
            result = json.loads(content)
            return {
                "trend": result.get("trend", ""),
                "forecast": result.get("forecast", ""),
                "article_count": len(articles),
                "model_used": get_model_name(),
            }

        except Exception as e:
            if _is_rate_limit_error(e):
                wait = 2 ** (attempt + 1)
                logger.warning(f"Rate limited, waiting {wait}s before retry")
                time.sleep(wait)
            elif attempt < 2:
                logger.error(f"Insight generation error (attempt {attempt + 1}): {e}")
                time.sleep(1)
            else:
                logger.error(f"All insight generation retries failed: {e}")
                return None

    return None


# ============================================================
# Historical Trend Analysis (quarterly + yearly time-series)
# ============================================================

BATCH_SUMMARY_PROMPT = """You are a senior cybersecurity threat intelligence analyst.
Summarize the key cybersecurity themes from these {category} articles into a concise overview.
Focus on: common attack patterns, notable threat actors, affected sectors, and emerging techniques.
Produce a JSON object with one key:
- "trend": A concise summary (2-3 paragraphs of markdown) of the main themes.
Respond ONLY with valid JSON."""

QUARTERLY_TREND_FIRST_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Analyze cybersecurity trends in {category} for {period} based on {count} articles.
Produce a JSON object with exactly three keys:
- "trend": A detailed analysis (3-5 paragraphs of markdown) of how threats in this category evolved during this quarter.
- "key_developments": A JSON array of 3-7 strings, each a concise bullet describing a key development.
- "outlook": A forward-looking paragraph on what to expect next quarter based on these trends.
Use markdown formatting. Be specific and cite patterns from the provided summaries.
Respond ONLY with valid JSON."""

QUARTERLY_TREND_SUBSEQUENT_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Analyze cybersecurity trends in {category} for {period} based on {count} articles.

Previous quarter's trend analysis:
{prev_trend}

Produce a JSON object with exactly three keys:
- "trend": A detailed analysis (3-5 paragraphs of markdown) of how threats evolved this quarter. Explicitly compare and correlate with the previous quarter's trends — what continued, what changed, what's new.
- "key_developments": A JSON array of 3-7 strings, each a concise bullet describing a key development.
- "outlook": A forward-looking paragraph on what to expect next quarter based on observed trajectory.
Use markdown formatting. Be specific and cite patterns from the provided summaries.
Respond ONLY with valid JSON."""

YEARLY_TREND_FIRST_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Synthesize these quarterly analyses for {category} in {year} into a comprehensive yearly trend report.

{quarterly_summaries}

Produce a JSON object with exactly three keys:
- "trend": A comprehensive yearly analysis (4-6 paragraphs of markdown) synthesizing all quarters. Identify overarching themes, major shifts, and year-defining developments.
- "key_developments": A JSON array of 5-10 strings, each a concise bullet describing the year's most significant developments.
- "outlook": A forward-looking assessment (2-3 paragraphs) predicting where this category is headed in the coming year.
Use markdown formatting. Be specific.
Respond ONLY with valid JSON."""

YEARLY_TREND_SUBSEQUENT_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Synthesize these quarterly analyses for {category} in {year} into a comprehensive yearly trend report.

{quarterly_summaries}

Previous year's trend analysis:
{prev_trend}

Produce a JSON object with exactly three keys:
- "trend": A comprehensive yearly analysis (4-6 paragraphs of markdown) synthesizing all quarters. Explicitly compare with the previous year — what intensified, what declined, what emerged as new.
- "key_developments": A JSON array of 5-10 strings, each a concise bullet describing the year's most significant developments.
- "outlook": A forward-looking assessment (2-3 paragraphs) predicting where this category is headed in the coming year based on multi-year trajectory.
Use markdown formatting. Be specific.
Respond ONLY with valid JSON."""

_EXEC_SUMMARY_RE = re.compile(r"# Executive Summary\s*\n([\s\S]*?)(?=\n#|\n*$)", re.IGNORECASE)
_TREND_BATCH_SIZE = 50
_TREND_MAX_SUMMARY_CHARS = 300


def _group_by_quarter(articles):
    """Group articles into a dict keyed by (year, quarter) tuples, sorted."""
    groups = {}
    for art in articles:
        pub = art.get("published_date")
        if not pub:
            continue
        try:
            dt = datetime.fromisoformat(pub)
        except (ValueError, TypeError):
            continue
        quarter = (dt.month - 1) // 3 + 1
        key = (dt.year, quarter)
        groups.setdefault(key, []).append(art)
    return dict(sorted(groups.items()))


def _extract_trend_summary(article, max_chars=_TREND_MAX_SUMMARY_CHARS):
    """Extract executive summary or fallback to first max_chars of summary_text."""
    summary = article.get("summary_text") or ""
    match = _EXEC_SUMMARY_RE.search(summary)
    text = match.group(1).strip() if match else summary[:max_chars]
    return text[:max_chars]


def _compute_trend_hash(articles):
    """Compute a 16-char SHA-256 hash over sorted (id, summary_length) pairs."""
    import hashlib
    tuples = sorted((art["id"], len(art.get("summary_text") or "")) for art in articles)
    digest = hashlib.sha256(str(tuples).encode()).hexdigest()
    return digest[:16]


def _format_trend_result(parsed):
    """Format JSON trend result into a single markdown string."""
    parts = [parsed.get("trend", "").strip()]
    key_devs = parsed.get("key_developments", [])
    if key_devs:
        parts.append("\n**Key Developments:**")
        parts.extend(f"- {d}" for d in key_devs)
    outlook = (parsed.get("outlook") or "").strip()
    if outlook:
        parts.append("\n**Outlook:**")
        parts.append(outlook)
    return "\n".join(parts).strip()


def _summarize_batch(category_name, batch_text):
    """Condense a batch of article summaries via LLM. Returns trend string or None."""
    prompt = BATCH_SUMMARY_PROMPT.replace("{category}", category_name)
    try:
        content, it, ot = call_llm(
            prompt,
            [{"role": "user", "content": batch_text}],
            temperature=0.3,
            max_tokens=1500,
            json_mode=True,
        )
        cost_tracker.add_tokens(it, ot)
        data = json.loads(content)
        return data.get("trend")
    except Exception as e:
        logger.error(f"Batch summarization failed: {e}")
        return None


def _build_article_summaries(articles, category_name):
    """Build a summary text block from articles. Batches large quarters via thread pool."""
    def fmt(art):
        date_str = art.get("published_date") or "unknown date"
        return f"- **{art['title']}** ({date_str}): {_extract_trend_summary(art)}"

    if len(articles) <= _TREND_BATCH_SIZE:
        return "\n".join(fmt(a) for a in articles)

    batches = [articles[i:i + _TREND_BATCH_SIZE] for i in range(0, len(articles), _TREND_BATCH_SIZE)]
    batch_texts = ["\n".join(fmt(a) for a in b) for b in batches]
    results = [None] * len(batches)

    with ThreadPoolExecutor(max_workers=5) as executor:
        futures = {
            executor.submit(_summarize_batch, category_name, bt): idx
            for idx, bt in enumerate(batch_texts)
        }
        for future in as_completed(futures):
            idx = futures[future]
            results[idx] = future.result() or f"Batch {idx + 1}: {len(batches[idx])} articles (summary unavailable)"

    return "\n\n---\n\n".join(r for r in results if r)


def _generate_quarterly_trend(category_name, quarter_label, article_count, summary_text, prev_trend):
    """Call LLM for a single quarter's trend. Returns formatted markdown or None."""
    if prev_trend:
        prompt = (QUARTERLY_TREND_SUBSEQUENT_PROMPT
                  .replace("{category}", category_name)
                  .replace("{period}", quarter_label)
                  .replace("{count}", str(article_count))
                  .replace("{prev_trend}", prev_trend[:3000]))
    else:
        prompt = (QUARTERLY_TREND_FIRST_PROMPT
                  .replace("{category}", category_name)
                  .replace("{period}", quarter_label)
                  .replace("{count}", str(article_count)))
    try:
        content, it, ot = call_llm(
            prompt,
            [{"role": "user", "content": f"Category: {category_name}\nPeriod: {quarter_label}\nArticle count: {article_count}\n\n{summary_text}"}],
            temperature=0.4,
            max_tokens=2500,
            json_mode=True,
        )
        cost_tracker.add_tokens(it, ot)
        return _format_trend_result(json.loads(content))
    except Exception as e:
        logger.error(f"Quarterly trend generation failed for {quarter_label}: {e}")
        return None


def _generate_yearly_trend(category_name, year, quarterly_trends, prev_year_trend):
    """Call LLM to synthesize quarterly trends into a yearly report. Returns markdown or None."""
    quarterly_summaries = "\n\n".join(
        f"### {qt['period']} ({qt['article_count']} articles)\n{qt['trend_text']}"
        for qt in quarterly_trends
    )
    if prev_year_trend:
        prompt = (YEARLY_TREND_SUBSEQUENT_PROMPT
                  .replace("{category}", category_name)
                  .replace("{year}", str(year))
                  .replace("{quarterly_summaries}", quarterly_summaries[:8000])
                  .replace("{prev_trend}", prev_year_trend[:3000]))
    else:
        prompt = (YEARLY_TREND_FIRST_PROMPT
                  .replace("{category}", category_name)
                  .replace("{year}", str(year))
                  .replace("{quarterly_summaries}", quarterly_summaries[:8000]))
    try:
        content, it, ot = call_llm(
            prompt,
            [{"role": "user", "content": f"Category: {category_name}\nYear: {year}\nQuarters covered: {len(quarterly_trends)}"}],
            temperature=0.4,
            max_tokens=3000,
            json_mode=True,
        )
        cost_tracker.add_tokens(it, ot)
        return _format_trend_result(json.loads(content))
    except Exception as e:
        logger.error(f"Yearly trend generation failed for {year}: {e}")
        return None


def generate_trend_analysis(category_name, subcategory_tag=None, since_days=None):
    """Generate quarterly and yearly historical trend analyses for a category.

    Groups summarized articles by quarter, generates per-quarter LLM analyses
    sequentially (each referencing the previous quarter), then synthesizes each
    year's quarters into a yearly report. Results are cached in ``trend_analyses``
    and only regenerated when the article hash changes.

    When ``since_days`` is provided, the cache is bypassed entirely (neither
    read nor written) to avoid overwriting the full-dataset cache with a
    time-filtered subset.

    Args:
        category_name: Broad category name (e.g. ``"Malware"``).
        subcategory_tag: Optional entity tag to narrow the focus.
        since_days: If set, only include articles from the last N days
            and skip the persistent cache.

    Returns:
        A dict with keys:
            - ``quarterly``: list of ``{period, article_count, trend_text}``
            - ``yearly``: list of ``{period, article_count, trend_text}``
            - ``model_used``: model name string
        Returns ``None`` if API key is missing or fewer than 3 articles exist.
    """
    from database import get_articles_for_category, get_trend_analysis, save_trend_analysis

    if not has_api_key():
        return None

    skip_cache = since_days is not None
    articles = get_articles_for_category(category_name, subcategory_tag=subcategory_tag, since_days=since_days)
    if len(articles) < 3:
        return None

    quarter_groups = _group_by_quarter(articles)
    if not quarter_groups:
        return None

    logger.info(f"Trend analysis: {len(articles)} articles across {len(quarter_groups)} quarters for {category_name}")

    # --- Quarterly pass (sequential so each can reference previous) ---
    quarterly_results = []
    prev_trend_text = None

    for (year, quarter), q_articles in quarter_groups.items():
        period_label = f"{year}-Q{quarter}"
        q_hash = _compute_trend_hash(q_articles)

        if not skip_cache:
            cached = get_trend_analysis(category_name, "quarterly", period_label)
            if cached and cached.get("article_hash") == q_hash:
                logger.info(f"  Using cached quarterly trend for {period_label}")
                entry = {"period": period_label, "article_count": cached["article_count"], "trend_text": cached["trend_text"]}
                quarterly_results.append(entry)
                prev_trend_text = cached["trend_text"]
                continue

        logger.info(f"  Generating quarterly trend for {period_label} ({len(q_articles)} articles)...")
        summary_text = _build_article_summaries(q_articles, category_name)
        trend_text = _generate_quarterly_trend(
            category_name, period_label, len(q_articles), summary_text, prev_trend_text
        )
        model = get_model_name()
        if trend_text:
            if not skip_cache:
                save_trend_analysis(category_name, "quarterly", period_label, trend_text, len(q_articles), q_hash, model)
            entry = {"period": period_label, "article_count": len(q_articles), "trend_text": trend_text}
            quarterly_results.append(entry)
            prev_trend_text = trend_text

    # --- Yearly pass ---
    yearly_results = []
    years = sorted({year for (year, _) in quarter_groups})
    prev_year_text = None

    for year in years:
        year_quarters = [q for q in quarterly_results if q["period"].startswith(f"{year}-")]
        if not year_quarters:
            continue

        year_total = sum(q["article_count"] for q in year_quarters)

        if not skip_cache:
            year_hash = ":".join(
                get_trend_analysis(category_name, "quarterly", q["period"])["article_hash"]
                for q in year_quarters
                if get_trend_analysis(category_name, "quarterly", q["period"])
            )
            cached = get_trend_analysis(category_name, "yearly", str(year))
            if cached and cached.get("article_hash") == year_hash:
                logger.info(f"  Using cached yearly trend for {year}")
                entry = {"period": str(year), "article_count": cached["article_count"], "trend_text": cached["trend_text"]}
                yearly_results.append(entry)
                prev_year_text = cached["trend_text"]
                continue

        logger.info(f"  Generating yearly trend for {year}...")
        trend_text = _generate_yearly_trend(category_name, year, year_quarters, prev_year_text)
        model = get_model_name()
        if trend_text:
            if not skip_cache:
                year_hash_save = ":".join(
                    get_trend_analysis(category_name, "quarterly", q["period"])["article_hash"]
                    for q in year_quarters
                    if get_trend_analysis(category_name, "quarterly", q["period"])
                )
                save_trend_analysis(category_name, "yearly", str(year), trend_text, year_total, year_hash_save, model)
            entry = {"period": str(year), "article_count": year_total, "trend_text": trend_text}
            yearly_results.append(entry)
            prev_year_text = trend_text

    return {
        "quarterly": quarterly_results,
        "yearly": yearly_results,
        "model_used": get_model_name(),
    }


def summarize_pending(limit=10):
    """Process a batch of unsummarized articles that have scraped content.

    Fetches up to ``limit`` articles with ``content_raw`` but no summary,
    generates a structured summary for each, and saves results to the
    database. Failed summaries are marked with ``model_used='failed'``
    to prevent indefinite retries.

    Args:
        limit: Maximum number of articles to summarize in this batch.

    Returns:
        Total number of articles processed (successful + failed).
    """
    if not has_api_key():
        logger.info("No LLM API key configured, skipping summarization")
        return 0

    articles = get_unsummarized_articles(limit=limit)
    summarized = 0

    for article in articles:
        title = article["title"]
        content = article["content_raw"]
        article_id = article["id"]
        article_url = article.get("url", "")

        logger.info(f"Summarizing article {article_id}: {title[:60]}")
        result = summarize_article(title, content)

        if result:
            attack_flow = result.get("attack_flow", [])
            novelty = result.get("novelty", "")
            save_summary(
                article_id=article_id,
                summary_text=result["summary"],
                key_points=json.dumps(attack_flow) if attack_flow else None,
                tags=json.dumps(result["tags"]),
                novelty_notes=novelty if novelty else None,
                model_used=get_model_name(),
            )
            summarized += 1
            logger.info(f"  Summarized article {article_id}")

            try:
                from notifier import send_article_notification
                send_article_notification(title, article_url, result.get("raw_data", {}))
            except Exception as e:
                logger.debug(f"  Notification skipped for article {article_id}: {e}")
        else:
            # Save a failed marker so this article isn't retried forever
            save_summary(
                article_id=article_id,
                summary_text="",
                key_points=None,
                tags="[]",
                novelty_notes=None,
                model_used="failed",
            )
            logger.warning(f"  Failed to summarize article {article_id}")

    logger.info(f"Summarized {summarized}/{len(articles)} articles")
    return len(articles)
