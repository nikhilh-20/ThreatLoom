# API Reference

Threat Loom exposes a REST API for all data access and operations. All endpoints return JSON unless otherwise noted.

Base URL: `http://localhost:<port>` (port is auto-assigned starting from 5000)

---

## Pages

These routes serve HTML pages (not API endpoints).

| Method | Path | Description |
|---|---|---|
| GET | `/` | Main dashboard |
| GET | `/article/<article_id>` | Article detail page |
| GET | `/intelligence` | Intelligence search page |
| GET | `/settings` | Settings page |

---

## Articles

### GET `/api/articles`

Fetch a paginated list of articles.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `source_id` | integer | — | Filter by source |
| `search` | string | — | Search title and content |
| `tag` | string | — | Filter by tag |
| `page` | integer | `1` | Page number |
| `limit` | integer | `20` | Results per page (max 100) |

**Response**

```json
{
  "articles": [
    {
      "id": 1,
      "title": "New Ransomware Campaign Targets Healthcare",
      "url": "https://example.com/article",
      "author": "John Doe",
      "published_date": "2024-01-15T10:30:00",
      "source_name": "BleepingComputer",
      "summary_text": "## Executive Summary\n...",
      "tags": ["ransomware", "healthcare", "lockbit"],
      "image_url": "https://example.com/image.jpg"
    }
  ],
  "total": 150,
  "page": 1,
  "limit": 20
}
```

---

### GET `/api/articles/<article_id>`

Fetch a single article with its full summary.

**Response**

```json
{
  "id": 1,
  "title": "New Ransomware Campaign Targets Healthcare",
  "url": "https://example.com/article",
  "author": "John Doe",
  "published_date": "2024-01-15T10:30:00",
  "source_name": "BleepingComputer",
  "summary_text": "## Executive Summary\n...",
  "key_points": "[{\"phase\": \"Initial Access\", ...}]",
  "tags": ["ransomware", "healthcare"],
  "novelty_notes": "First observed use of...",
  "model_used": "gpt-4o-mini",
  "image_url": "https://example.com/image.jpg"
}
```

---

### DELETE `/api/articles/<article_id>/summary`

Remove the AI summary and embeddings for a single article. The article itself is preserved and can be re-summarized on the next pipeline run.

**Response**

```json
{"status": "ok"}
```

Returns 404 if the article has no summary.

---

### GET `/api/articles/categorized`

Get articles grouped by threat category.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `limit` | integer | `5` | Articles per category |
| `days` | integer | — | Lookback window in days (e.g. `7` = last 7 days). Omit for all time. |

**Response**

```json
{
  "categories": {
    "Malware": {
      "articles": [...],
      "count": 42
    },
    "Vulnerabilities": {
      "articles": [...],
      "count": 28
    }
  }
}
```

---

## Sources

### GET `/api/sources`

List all configured feed sources.

**Response**

```json
{
  "sources": [
    {
      "id": 1,
      "name": "The Hacker News",
      "url": "https://feeds.feedburner.com/TheHackersNews",
      "enabled": 1,
      "last_fetched": "2024-01-15T12:00:00"
    }
  ]
}
```

---

### GET `/api/stats`

Get aggregate database statistics.

**Response**

```json
{
  "total_articles": 730,
  "total_sources": 13,
  "total_summaries": 584,
  "articles_last_24h": 12,
  "unsummarized": 10,
  "scrape_failed": 73,
  "failed_summaries": 63,
  "has_api_key": true
}
```

---

## Refresh

### POST `/api/refresh`

Trigger a manual pipeline refresh.

**Request Body**

```json
{
  "days": 1,
  "since_last_fetch": false
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `days` | integer | `1` | Lookback period in days |
| `since_last_fetch` | boolean | `false` | Only fetch articles newer than each source's last fetch |

**Response**

```json
{
  "status": "started",
  "days": 1
}
```

If a refresh is already running:

```json
{
  "status": "already_running"
}
```

---

### GET `/api/refresh-status`

Poll the current pipeline state. Call every 3 seconds while a pipeline is running.

**Response**

```json
{
  "is_refreshing": false,
  "is_embedding": false,
  "is_aborting": false,
  "stage": "done",
  "cost_estimate": null,
  "actual_cost": null
}
```

| Field | Type | Description |
|---|---|---|
| `is_refreshing` | boolean | True while any pipeline (refresh, ingest, embed) is running |
| `is_embedding` | boolean | True when an embed-only job is running |
| `is_aborting` | boolean | True after abort was requested but before the pipeline stops |
| `stage` | string | Current stage: `fetch`, `scrape`, `confirm`, `summarize`, `embed`, `done`, `aborted`, `error` |
| `cost_estimate` | object\|null | Present during `confirm` stage — `{article_count, estimated_cost, model}` |
| `actual_cost` | object\|null | Present after summarization — `{article_count, actual_cost, model}` |

---

### POST `/api/clear-db`

Delete articles, summaries, embeddings, and insights. Source definitions are preserved. Pass `days` to limit deletion to articles older than N days.

**Request Body**

```json
{
  "days": 0
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `days` | integer | `0` | `0` = delete everything; `N` = delete articles older than N days |

**Response**

```json
{"status": "ok", "deleted": 45}
```

(For `days=0`, `deleted` is omitted.)

!!! warning "Destructive Operation"
    This permanently deletes all collected data. Source feed configurations are retained.

---

### POST `/api/abort`

Abort the currently running pipeline. The pipeline stops between stages (after the current batch completes).

**Response**

```json
{"status": "ok"}
```

If no pipeline is running, returns the same response (no-op).

---

### POST `/api/embed`

Generate embeddings for all summarized articles that don't have one yet. Runs as a background job (same lock as the full pipeline — returns `already_running` if a pipeline is active).

**Response**

```json
{"status": "started"}
```

```json
{"status": "already_running"}
```

---

### POST `/api/ingest-urls`

Scrape and summarize a list of specific article URLs without running a full feed fetch.

**Request Body**

```json
{
  "urls": [
    "https://example.com/threat-report-1",
    "https://example.com/threat-report-2"
  ]
}
```

URLs must use `http://` or `https://`. Invalid or duplicate URLs are skipped.

**Response**

```json
{
  "status": "started",
  "inserted": 2,
  "skipped": 0
}
```

| Field | Description |
|---|---|
| `inserted` | Number of new URLs added to the database |
| `skipped` | Number of URLs already in the database or with invalid schemes |

---

## Categories & Insights

### GET `/api/subcategories`

Get entity-level breakdown within a category.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `category` | string | required | Category name (e.g., "Threat Actors") |
| `limit` | integer | `5` | Articles per subcategory |
| `days` | integer | — | Lookback window in days. Omit for all time. |

**Response**

```json
{
  "subcategories": {
    "APT29": {
      "articles": [...],
      "count": 8,
      "display_name": "APT29"
    },
    "Lazarus Group": {
      "articles": [...],
      "count": 5,
      "display_name": "Lazarus Group"
    }
  }
}
```

---

### GET `/api/category-insight`

Generate or retrieve a cached trend + forecast for a category.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `category` | string | required | Category name |
| `subcategory` | string | — | Entity tag for drill-down (e.g., "apt29") |
| `days` | integer | — | Lookback window in days. Omit for all time. When set, result is not written to the persistent cache. |

**Response**

```json
{
  "trend": "## Trend Analysis\n\nOver the past several months...",
  "forecast": "## Forecast\n\nLooking ahead 3-6 months...",
  "article_count": 42,
  "model_used": "gpt-4o-mini",
  "cached": true,
  "generated_at": "2024-01-15T12:00:00",
  "actual_cost": 0.0
}
```

`actual_cost` is `0.0` when the result was served from cache.

---

### GET `/api/trend-analysis`

Generate or retrieve cached historical trend analysis (quarterly + yearly) for a category.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `category` | string | required | Category name |
| `subcategory` | string | — | Entity tag for drill-down (e.g., "apt29") |
| `days` | integer | — | Lookback window in days. Omit for all time. When set, result is generated fresh and not cached. |

**Response**

```json
{
  "quarterly": [
    {
      "period": "2024-Q1",
      "article_count": 18,
      "trend_text": "## Q1 2024 Trends\n\n..."
    },
    {
      "period": "2024-Q2",
      "article_count": 24,
      "trend_text": "## Q2 2024 Trends\n\n..."
    }
  ],
  "yearly": [
    {
      "period": "2024",
      "article_count": 42,
      "trend_text": "## 2024 Annual Trends\n\n..."
    }
  ],
  "model_used": "gpt-4o-mini",
  "actual_cost": 0.012
}
```

**Error Responses**

```json
{"error": "insufficient_data", "article_count": 2}
```

Returned when fewer than 3 articles are available.

---

### GET `/api/insight-estimate`

Estimate the API cost before generating a trend analysis or forecast. Call this before `/api/trend-analysis` or `/api/category-insight` to show the user a cost confirmation.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `category` | string | required | Category name |
| `subcategory` | string | — | Entity tag for drill-down |
| `days` | integer | — | Lookback window in days |
| `type` | string | `"forecast"` | `"forecast"` or `"trend"` |

**Response**

```json
{
  "article_count": 42,
  "estimated_cost": 0.008,
  "model": "gpt-4o-mini",
  "n_quarters": 4,
  "n_years": 1
}
```

`n_quarters` and `n_years` are only present when `type=trend`. `estimated_cost` is `0.0` when the forecast result is already cached.

---

## Intelligence

### POST `/api/intelligence/chat`

RAG-based question answering over collected threat intelligence.

**Request Body**

```json
{
  "messages": [
    {"role": "user", "content": "What ransomware groups have been most active recently?"}
  ],
  "since_days": 7
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `messages` | array | required | Conversation messages in OpenAI chat format. Include previous messages for multi-turn conversations (last 6 are used). |
| `since_days` | integer | auto-detect | Restrict article retrieval to this many days. Omit to auto-detect from the query (e.g. "last 24 hours" → 1 day). Pass `0` to search all articles regardless of time phrases in the query. |

Time references are detected automatically from natural language: `last N hours`, `past N days`, `yesterday`, `last week`, `last month`. See the [Intelligence Search](features/intelligence-search.md#time-period-filtering) feature doc for the full list.

**Response**

```json
{
  "response": "Based on the collected intelligence, several ransomware groups...",
  "articles": [
    {
      "id": 42,
      "title": "LockBit 3.0 Targets Manufacturing Sector",
      "source_name": "BleepingComputer",
      "published_date": "2024-01-14",
      "tags": ["lockbit", "ransomware"],
      "score": 0.89
    }
  ],
  "model_used": "gpt-4o-mini",
  "since_days": 7,
  "error": null
}
```

The `since_days` field in the response echoes back the effective time window that was applied (`null` if all articles were searched).

**Error States**

| `error` Value | Meaning |
|---|---|
| `null` | Success |
| `"no_api_key"` | OpenAI API key not configured |
| `string` | Error description from API call |

---

### POST `/api/intelligence/search`

Standalone semantic search without LLM response generation.

**Request Body**

```json
{
  "query": "supply chain attacks targeting open source",
  "top_k": 10
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `query` | string | required | Natural language search query |
| `top_k` | integer | `15` | Number of results (max 50) |

**Response**

```json
{
  "articles": [
    {
      "id": 15,
      "title": "Malicious npm Packages Target Developers",
      "source_name": "The Hacker News",
      "published_date": "2024-01-13",
      "tags": ["supply-chain", "npm", "typosquatting"],
      "score": 0.92
    }
  ],
  "error": null
}
```

---

### GET `/api/intelligence/status`

Get embedding index statistics.

**Response**

```json
{
  "total_summarized": 310,
  "total_embedded": 295
}
```

---

## Settings

### POST `/api/settings`

Save configuration settings.

**Request Body**

```json
{
  "llm_provider": "openai",
  "openai_api_key": "sk-proj-...",
  "openai_model": "gpt-4o-mini",
  "anthropic_api_key": "",
  "anthropic_model": "claude-haiku-4-5-20251001",
  "malpedia_api_key": "",
  "fetch_interval_minutes": 30,
  "feeds": [
    {
      "name": "The Hacker News",
      "url": "https://feeds.feedburner.com/TheHackersNews",
      "enabled": true
    }
  ],
  "email_notifications_enabled": true,
  "notification_email": "you@example.com",
  "smtp_host": "smtp.gmail.com",
  "smtp_port": 587,
  "smtp_username": "you@gmail.com",
  "smtp_password": "app-password",
  "smtp_use_tls": true
}
```

All Anthropic and email fields are optional. Omitted fields retain their current saved values. Feed URLs must use `http://` or `https://` — entries with other schemes are silently dropped.

**Response**

```json
{
  "status": "ok"
}
```

---

### POST `/api/test-key`

Validate an OpenAI API key.

**Request Body**

```json
{
  "key": "sk-proj-..."
}
```

**Response**

```json
{
  "valid": true
}
```

---

### POST `/api/test-malpedia-key`

Validate a Malpedia API key.

**Request Body**

```json
{
  "key": "your-malpedia-token"
}
```

**Response**

```json
{
  "valid": true
}
```

---

### POST `/api/test-email`

Send a test email notification. Accepts SMTP settings directly from the request body so you can test before saving.

**Request Body**

```json
{
  "smtp_host": "smtp.gmail.com",
  "smtp_port": 587,
  "smtp_username": "you@gmail.com",
  "smtp_password": "app-password",
  "smtp_use_tls": true,
  "notification_email": "you@example.com"
}
```

If the body is empty or `smtp_host` is not provided, falls back to saved config settings.

**Response**

```json
{
  "success": true,
  "error": null
}
```

On failure:

```json
{
  "success": false,
  "error": "Connection refused"
}
```

---

### POST `/api/report`

Submit a report about an LLM-generated output. Sends an email via the configured SMTP server to the notification recipient.

**Request Body**

```json
{
  "type": "Article Summary",
  "identifier": "New Ransomware Campaign Targets Healthcare",
  "llm_content": "## Executive Summary\n...",
  "metadata": {
    "Article URL": "https://example.com/article",
    "Source": "BleepingComputer",
    "Reported": "2026-02-22T04:00:00Z"
  },
  "user_note": "This summary seems incorrect.",
  "token": ""
}
```

| Field | Description |
|---|---|
| `type` | Report category: `Article Summary`, `Trend Analysis`, `Forecast`, `Intelligence Response` |
| `identifier` | Title or short description of the reported item |
| `llm_content` | The exact LLM-generated text (auto-captured, not editable by the user) |
| `metadata` | Key-value pairs included in the email body |
| `user_note` | Optional note from the reporter |
| `token` | Must match `report_token` in `config.json` if one is configured; omit or leave blank if no token is set |

**Response**

```json
{"ok": true}
```

Returns 403 if a `report_token` is configured and the submitted token doesn't match. Returns 500 if SMTP is not configured or the send fails.
