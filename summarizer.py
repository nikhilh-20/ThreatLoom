import json
import logging
import time

from openai import OpenAI, APIError, RateLimitError

from config import load_config
from database import get_unsummarized_articles, save_summary, get_articles_for_category, _format_entity_name

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


def _get_client():
    """Create an OpenAI client using the configured API key.

    Returns:
        A tuple of ``(OpenAI client, model name)`` if an API key is
        configured, or ``(None, None)`` otherwise.
    """
    config = load_config()
    api_key = config.get("openai_api_key", "").strip()
    if not api_key:
        return None, None
    model = config.get("openai_model", "gpt-4o-mini")
    return OpenAI(api_key=api_key), model


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

    client, model = _get_client()
    if client is None:
        return [True] * len(titles)  # No API key → accept all

    results = []
    BATCH = 25

    for i in range(0, len(titles), BATCH):
        batch = titles[i : i + BATCH]
        numbered = "\n".join(f'{j + 1}. "{t}"' for j, t in enumerate(batch))
        prompt = RELEVANCE_PROMPT.format(titles=numbered)

        try:
            resp = client.chat.completions.create(
                model=model,
                messages=[{"role": "user", "content": prompt}],
                response_format={"type": "json_object"},
                temperature=0,
                max_tokens=300,
            )
            data = json.loads(resp.choices[0].message.content)
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
    client, model = _get_client()
    if client is None:
        logger.warning("OpenAI API key not configured, skipping summarization")
        return None

    # Truncate content to stay within token limits
    max_chars = 12000
    if len(content) > max_chars:
        content = content[:max_chars] + "\n\n[Content truncated...]"

    user_message = f"Title: {title}\n\nArticle Content:\n{content}"

    for attempt in range(3):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": SUMMARY_PROMPT},
                    {"role": "user", "content": user_message},
                ],
                response_format={"type": "json_object"},
                temperature=0.3,
                max_tokens=2500,
            )

            result = json.loads(response.choices[0].message.content)
            summary_md = _compose_markdown(result)

            return {
                "summary": summary_md,
                "tags": result.get("tags", []),
                "attack_flow": result.get("attack_flow", []),
                "novelty": result.get("novelty", ""),
                "raw_data": result,
            }

        except RateLimitError:
            wait = 2 ** (attempt + 1)
            logger.warning(f"Rate limited, waiting {wait}s before retry")
            time.sleep(wait)
        except (APIError, json.JSONDecodeError) as e:
            logger.error(f"Summarization error (attempt {attempt + 1}): {e}")
            if attempt < 2:
                time.sleep(1)
        except Exception as e:
            logger.error(f"Unexpected error during summarization: {e}")
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


def generate_category_insight(category_name, subcategory_tag=None):
    """Generate trend analysis and forecast for a threat category.

    Retrieves all summarized articles for the given category (and
    optionally a subcategory entity), then asks the LLM to produce a
    trend analysis and forward-looking forecast.

    Args:
        category_name: Broad category name (e.g. ``"Malware"``).
        subcategory_tag: Optional entity tag to narrow the focus
            (e.g. ``"lockbit"``).

    Returns:
        A dict with keys ``trend`` (markdown), ``forecast`` (markdown),
        ``article_count`` (int), and ``model_used`` (str). Returns None
        if fewer than 3 articles are available or the API key is missing.
    """
    import re

    client, model = _get_client()
    if client is None:
        logger.warning("OpenAI API key not configured, skipping insight generation")
        return None

    articles = get_articles_for_category(category_name, subcategory_tag=subcategory_tag)
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
            response = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": prompt},
                    {"role": "user", "content": user_message},
                ],
                response_format={"type": "json_object"},
                temperature=0.4,
                max_tokens=2000,
            )

            result = json.loads(response.choices[0].message.content)
            return {
                "trend": result.get("trend", ""),
                "forecast": result.get("forecast", ""),
                "article_count": len(articles),
                "model_used": model,
            }

        except RateLimitError:
            wait = 2 ** (attempt + 1)
            logger.warning(f"Rate limited, waiting {wait}s before retry")
            time.sleep(wait)
        except (APIError, json.JSONDecodeError) as e:
            logger.error(f"Insight generation error (attempt {attempt + 1}): {e}")
            if attempt < 2:
                time.sleep(1)
        except Exception as e:
            logger.error(f"Unexpected error during insight generation: {e}")
            return None

    return None


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
    client, model = _get_client()
    if client is None:
        logger.info("No OpenAI API key configured, skipping summarization")
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
                model_used=model,
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
