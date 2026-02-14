# AI Summarization

Every ingested article is processed by OpenAI to produce a structured intelligence summary. Summaries include an executive overview, novelty assessment, technical details, defensive mitigations, tags, and an attack flow sequence.

## Summary Structure

The summarizer requests a structured JSON response with these fields:

### Executive Summary

A 3-5 sentence paragraph covering the core intelligence — what happened, who is involved, what's affected, and why it matters.

### Novelty Assessment

What's new or noteworthy about the threat. This highlights novel tactics, techniques, tooling, or targeting that distinguish this from routine activity. The novelty text is stored both within the markdown summary and as a separate `novelty_notes` field in the database, enabling the "What's Notable" section on the article detail page.

### Technical Details

An array of bullet points capturing:

- Indicators of Compromise (IOCs)
- CVE identifiers
- Affected systems and versions
- Attack chain steps
- Malware capabilities
- Infrastructure details

### Mitigations

Actionable defensive recommendations derived from the article content.

### Tags

3-8 lowercase, hyphenated tags for categorization:

| Tag Type | Examples | Purpose |
|---|---|---|
| Category | `ransomware`, `phishing`, `vulnerability` | Maps to broad threat categories |
| Entity | `apt29`, `emotet`, `cobalt-strike` | MITRE ATT&CK threat actors and software |
| CVE | `cve-2024-1234` | Specific vulnerability identifiers |

### Attack Flow

An ordered sequence of attack phases (when applicable). Each step includes:

```json
{
  "phase": "Initial Access",
  "title": "Spearphishing with macro-enabled document",
  "description": "Attacker sends targeted email with weaponized DOCX...",
  "technique": "T1566.001"
}
```

See [Attack Flow](attack-flow.md) for details on the visualization.

## Categorization

Tags drive the categorization system. Each tag is mapped to one of 9 broad threat categories using keyword rules:

| Category | Matching Tags |
|---|---|
| **Malware** | malware, trojan, backdoor, ransomware, infostealer, rootkit, wiper, loader, dropper, RAT, and 9 known RaaS groups |
| **Vulnerabilities** | CVE, exploit, RCE, zero-day, patch, privilege-escalation, buffer-overflow |
| **Threat Actors** | APT, campaign, nation-state, and 15+ named groups (lazarus, apt29, etc.) |
| **Data Leaks** | breach, data-leak, exfiltration, credential-dump |
| **Phishing & Social Engineering** | phishing, BEC, spearphishing, credential-theft, social-engineering |
| **Supply Chain** | supply-chain, dependency-confusion, typosquatting, npm, pypi |
| **Botnet & DDoS** | botnet, ddos, mirai, amplification |
| **C2 & Offensive Tooling** | cobalt-strike, metasploit, sliver, brute-ratel, C2 |
| **IoT & Hardware** | firmware, scada, ics, industrial, embedded |

Articles with tags matching multiple categories appear in each relevant category.

## Subcategory Drill-Down

Three categories support entity-level drill-down:

- **Threat Actors** — Named groups (APT29, Lazarus, Turla, etc.)
- **Malware** — Families (Emotet, LockBit, QakBot, etc.)
- **C2 & Offensive Tooling** — Tools (Cobalt Strike, Sliver, etc.)

Entity names are matched against a MITRE ATT&CK lookup table containing 100+ threat actor groups and 200+ malware families. Only known MITRE entities become subcategories; generic tags are grouped under "General."

### Entity Normalization

Versioned variants are canonicalized to their base name:

| Raw Tag | Normalized |
|---|---|
| `lockbit-3.0` | `lockbit` |
| `lockbit-2.0` | `lockbit` |
| `apt-29` | `apt29` |

This ensures articles about the same entity are grouped together regardless of version references.

## Processing Details

### Batch Processing

The `summarize_pending()` function processes up to 10 unsummarized articles per batch. Each article is summarized individually with its own API call.

### Content Handling

- Article content is truncated to **12,000 characters** before being sent to the LLM
- The summarizer uses `response_format: json_object` for reliable structured output
- Temperature is set to **0.3** for consistent, factual summaries

### Retry Logic

- Up to **3 retries** on rate limit or API errors
- Exponential backoff: 4s, 8s, 16s between retries
- Failed articles are marked with `model_used="failed"` and skipped on subsequent runs

### Model Configuration

The summarizer uses whichever model is set in `config.json` under `openai_model`. Token limits:

| Operation | Max Tokens | Temperature |
|---|---|---|
| Relevance check | 300 | 0.0 |
| Article summary | 2,500 | 0.3 |
| Category insight | 2,000 | 0.4 |

!!! tip "Quality vs. Cost"
    `gpt-4o-mini` handles most articles well. For articles requiring deeper analysis (APT campaigns, complex exploit chains), `gpt-4o` produces noticeably better attack flows and novelty assessments.
