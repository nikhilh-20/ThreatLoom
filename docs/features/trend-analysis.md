# Trend Analysis

Threat Loom generates AI-powered trend analysis and forecasts for each threat category. These insights synthesize patterns across your collected articles to identify emerging threats, shifting tactics, and likely future developments.

## How It Works

When you click **Trend & Forecast** on a category card (or drill into a subcategory), Threat Loom:

1. **Collects articles** — Gathers all summarized articles in the selected category (up to 500)
2. **Extracts summaries** — Pulls the executive summary from each article's markdown
3. **Generates insight** — Sends the collected summaries to the LLM with a trend analysis prompt
4. **Caches the result** — Stores the insight with a hash-based cache key

### Output Structure

Each insight contains two sections:

**Trend Analysis** (3-6 paragraphs)

- Evolving tactics, techniques, and procedures (TTPs)
- Tools and infrastructure being used
- Targeting patterns (industries, regions, platforms)
- Notable shifts in threat actor behavior

**Forecast** (2-4 paragraphs)

- Likely developments over the next 3-6 months
- Emerging risks and attack vectors
- Expected evolution of current threats

## Category-Level Insights

Insights are generated for the 9 broad threat categories:

- Malware
- Vulnerabilities
- Threat Actors
- Data Leaks
- Phishing & Social Engineering
- Supply Chain
- Botnet & DDoS
- C2 & Offensive Tooling
- IoT & Hardware

Each category aggregates articles matching its tag rules (see [AI Summarization — Categorization](ai-summarization.md#categorization)).

## Subcategory Insights

For categories that support entity drill-down (**Threat Actors**, **Malware**, **C2 & Offensive Tooling**), you can generate insights for specific entities:

- "APT29" — Trend analysis focused on Cozy Bear activity
- "LockBit" — Forecast for LockBit ransomware evolution
- "Cobalt Strike" — Trends in Cobalt Strike usage and detection

Subcategory insights use the same generation process but filter articles to only those tagged with the specific entity.

## Caching Strategy

Insights are cached in the `category_insights` database table to avoid redundant LLM calls.

### Cache Key

Each cache entry stores:

| Field | Purpose |
|---|---|
| `category_name` | Category or `"Category::Entity"` for subcategories |
| `article_hash` | SHA-256 hash (first 16 chars) of sorted article content hashes |
| `article_count` | Number of articles used to generate the insight |
| `model_used` | Which LLM model generated the insight |
| `created_date` | When the insight was generated |

### Cache Invalidation

A cached insight is considered **stale** when either:

1. **Article hash changed** — New articles have been added or existing ones modified since the insight was generated
2. **TTL expired** — The insight is older than **24 hours**

When stale, the next request regenerates the insight. Fresh cache hits return instantly without an API call.

### Cache Indicators

The UI shows cache status:

- **"Cached"** — Result served from cache
- **Generated timestamp** — When the insight was created
- **Article count** — How many articles informed the analysis
- **Model used** — Which LLM produced the insight

## Triggering Insight Generation

### Via the UI

1. Navigate to the dashboard
2. Click a **category card**
3. Click the **Trend & Forecast** button in the insight panel
4. For subcategories, drill into an entity first, then request the insight

### Via the API

```bash
# Category-level insight
curl "http://localhost:5000/api/category-insight?category=Malware"

# Subcategory-specific insight
curl "http://localhost:5000/api/category-insight?category=Threat+Actors&subcategory=apt29"
```

See [API Reference](../api-reference.md#get-apicategory-insight) for full details.

## LLM Configuration

| Setting | Value |
|---|---|
| Temperature | 0.4 |
| Max tokens | 2,000 |
| Input limit | Up to 500 article summaries |
| Model | Configured `openai_model` |

!!! tip "Insight Quality"
    Trend analysis quality scales with the number of articles in a category. Categories with fewer than 5 articles may produce generic insights. For best results, let the pipeline run for several days to build up a meaningful corpus.
