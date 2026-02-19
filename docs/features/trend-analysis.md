# Trend Analysis & Forecasting

Threat Loom generates two distinct AI-powered analyses for each threat category: **Historical Trend Analysis** (quarterly and yearly retrospective) and **Forecast** (current trend + 3-6 month outlook). Both are accessible from any category or subcategory drill-down view.

---

## Historical Trend Analysis

Historical Trend Analysis synthesises your entire article corpus into a structured retrospective, broken down quarter-by-quarter and year-by-year. This is useful for identifying how a threat category has evolved over months or years.

### How It Works

When you click **Trend Analysis** on a category drill-down:

1. **Collects articles** — Gathers all summarised articles in the selected category (optionally filtered by the active time-period)
2. **Groups by quarter** — Articles are bucketed by `(year, quarter)` using their `published_date`
3. **Extracts summaries** — Pulls the executive summary from each article's markdown (first 300 chars as fallback)
4. **Batch condensation** — For quarters with >50 articles, batches of 50 are condensed into a single trend string via a preliminary LLM pass
5. **Quarterly analysis** — Each quarter is analysed with a multi-pass prompt. The first quarter uses only its own articles; subsequent quarters receive the previous quarter's trend as context for cross-period correlation
6. **Yearly synthesis** — For each year, the quarterly trends are fed into a yearly synthesis prompt using the same first/subsequent pattern
7. **Caches results** — Quarterly and yearly results are cached in `trend_analyses` with hash-based invalidation

### Output Structure

Results are displayed as collapsible panels — one per quarter and one per year — each containing:

**Quarterly analysis** (3-5 paragraphs)

- How threats evolved during the quarter
- Key developments (3-7 bullet points)
- Outlook for the following quarter

**Yearly synthesis** (3-5 paragraphs)

- Year-level trend across all quarters
- Key developments (3-7 bullet points)
- Outlook for the following year

### Cost Estimation

Before generation begins, a cost estimate modal shows:

- Number of articles to be analysed
- Estimated quarters and years to be processed
- Projected API cost

Actual cost is displayed after generation completes.

### Caching

Trend analysis results are cached in the `trend_analyses` table. Cache entries are keyed by `(category_name, period_type, period_label)`. A cached entry is reused when the article hash matches the current set of articles.

When a **time-period filter** is active (e.g., 30d), results are generated fresh and not written to the cache — this preserves the full-dataset cache entries.

---

## Forecast

The Forecast produces a current-state trend analysis plus a 3-6 month forward-looking assessment for a category.

### How It Works

When you click **Forecast** on a category drill-down:

1. **Collects articles** — Gathers all summarised articles in the selected category (optionally filtered by the active time-period)
2. **Extracts summaries** — Pulls the executive summary from each article (up to 500 articles)
3. **Generates insight** — Sends the collected summaries to the LLM with a structured trend + forecast prompt
4. **Caches the result** — Stores the insight in `category_insights` with hash-based invalidation and 24h TTL

### Output Structure

**Current Trends** (3-6 paragraphs)

- Evolving tactics, techniques, and procedures (TTPs)
- Tools and infrastructure being used
- Targeting patterns (industries, regions, platforms)
- Notable shifts in threat actor behaviour

**Forecast** (2-4 paragraphs)

- Likely developments over the next 3-6 months
- Emerging risks and attack vectors
- Expected evolution of current threats

### Cost Estimation

Before generation, a cost estimate is shown. If the result will be served from cache, the modal is skipped and the cached result is returned immediately at no cost.

### Caching

Forecast results are cached in `category_insights`. A cached result is considered stale when:

1. The **article hash** has changed (new articles added or existing ones modified)
2. The **TTL** has expired (older than 24 hours)

When a time-period filter is active, the cache key is namespaced (e.g., `Malware::days7`) to avoid overwriting the full-dataset cache.

---

## Category & Subcategory Support

Both analyses are available for all 9 broad threat categories:

- Malware
- Vulnerabilities
- Threat Actors
- Data Leaks
- Phishing & Social Engineering
- Supply Chain
- Botnet & DDoS
- C2 & Offensive Tooling
- IoT & Hardware

For categories supporting entity drill-down (**Threat Actors**, **Malware**, **C2 & Offensive Tooling**), both analyses can be scoped to a specific entity:

- "APT29" — Trend and forecast focused on Cozy Bear activity
- "LockBit" — Historical quarters + forecast for LockBit ransomware
- "Cobalt Strike" — Trends in Cobalt Strike usage and detection

---

## Time-Period Filter

Both analyses respect the active time-period filter (All / 24h / 7d / 30d / 90d). When a filter is active, only articles published within the selected window are included. Filtered results are generated fresh and not written to the persistent cache.

---

## Triggering via the UI

1. Navigate to the dashboard
2. Click a **category card** to enter the drill-down view
3. Click **Trend Analysis** for the historical quarterly/yearly view, or **Forecast** for the current trend + outlook
4. For subcategory scoping, drill into an entity first, then request the analysis

---

## Triggering via the API

```bash
# Historical trend analysis — category level
curl "http://localhost:5000/api/trend-analysis?category=Malware"

# Historical trend analysis — subcategory level
curl "http://localhost:5000/api/trend-analysis?category=Threat+Actors&subcategory=apt29"

# Historical trend analysis — filtered to last 30 days
curl "http://localhost:5000/api/trend-analysis?category=Malware&days=30"

# Forecast — category level
curl "http://localhost:5000/api/category-insight?category=Malware"

# Cost estimate before generation
curl "http://localhost:5000/api/insight-estimate?category=Malware&type=trend"
curl "http://localhost:5000/api/insight-estimate?category=Malware&type=forecast"
```

See [API Reference](../api-reference.md) for full response schemas.

---

## LLM Configuration

### Historical Trend Analysis

| Setting | Value |
|---|---|
| Batch summary temperature | 0.3 |
| Quarterly analysis temperature | 0.4 |
| Yearly synthesis temperature | 0.4 |
| Batch summary max tokens | 1,500 |
| Quarterly max tokens | 2,500 |
| Yearly max tokens | 3,000 |
| Batch size | 50 articles per batch |
| Model | Configured `openai_model` or `anthropic_model` |

### Forecast

| Setting | Value |
|---|---|
| Temperature | 0.4 |
| Max tokens | 2,000 |
| Input limit | Up to 500 article summaries |
| Model | Configured `openai_model` or `anthropic_model` |

!!! tip "Analysis Quality"
    Analysis quality scales with the number of articles and the time span covered. Categories with fewer than 3 articles return an insufficient data error. For historical trend analysis, having articles spanning multiple quarters produces the richest cross-period correlation. Let the pipeline run for several weeks to build a meaningful corpus.

!!! note "LLM Disclaimer"
    All trend analyses and forecasts are generated by large language models. LLMs can make mistakes. Always verify important information against the original source articles.
