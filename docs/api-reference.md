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

### GET `/api/articles/categorized`

Get articles grouped by threat category.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `limit` | integer | `5` | Articles per category |

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

Get overall database statistics.

**Response**

```json
{
  "total_articles": 350,
  "total_sources": 13,
  "total_summaries": 310,
  "articles_24h": 15
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

Poll whether a refresh is currently running.

**Response**

```json
{
  "is_refreshing": false
}
```

---

### POST `/api/clear-db`

Delete all articles, summaries, embeddings, and insights. Source definitions are preserved.

**Response**

```json
{
  "status": "ok"
}
```

!!! warning "Destructive Operation"
    This permanently deletes all collected data. Source feed configurations are retained.

---

## Categories & Insights

### GET `/api/subcategories`

Get entity-level breakdown within a category.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `category` | string | required | Category name (e.g., "Threat Actors") |
| `limit` | integer | `5` | Articles per subcategory |

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

Generate or retrieve cached trend analysis and forecast for a category.

**Parameters**

| Param | Type | Default | Description |
|---|---|---|---|
| `category` | string | required | Category name |
| `subcategory` | string | — | Entity tag for drill-down (e.g., "apt29") |

**Response**

```json
{
  "trend": "## Trend Analysis\n\nOver the past several months...",
  "forecast": "## Forecast\n\nLooking ahead 3-6 months...",
  "article_count": 42,
  "model_used": "gpt-4o-mini",
  "cached": true,
  "generated_at": "2024-01-15T12:00:00"
}
```

---

## Intelligence

### POST `/api/intelligence/chat`

RAG-based question answering over collected threat intelligence.

**Request Body**

```json
{
  "messages": [
    {"role": "user", "content": "What ransomware groups have been most active recently?"}
  ]
}
```

The `messages` array follows the OpenAI chat format. Include previous messages for multi-turn conversations (last 6 are used).

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
  "error": null
}
```

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
  "openai_api_key": "sk-proj-...",
  "malpedia_api_key": "",
  "openai_model": "gpt-4o-mini",
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

All email fields are optional. Omitted fields retain their current saved values.

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
