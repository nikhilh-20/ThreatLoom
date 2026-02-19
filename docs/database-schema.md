# Database Schema

Threat Loom uses SQLite with WAL (Write-Ahead Logging) mode for persistent storage. The database file is `threatlandscape.db` in the project root.

## Tables

### `sources`

Feed source definitions. Populated from `config.json` on startup.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PRIMARY KEY | Auto-incrementing source ID |
| `name` | TEXT | NOT NULL | Display name (e.g., "The Hacker News") |
| `url` | TEXT | NOT NULL, UNIQUE | Feed URL |
| `enabled` | INTEGER | DEFAULT 1 | Whether the feed is active (1=yes, 0=no) |
| `last_fetched` | TIMESTAMP | — | When this source was last successfully fetched |

---

### `articles`

Ingested articles from all sources.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PRIMARY KEY | Auto-incrementing article ID |
| `source_id` | INTEGER | FOREIGN KEY → sources(id) | Which feed this article came from |
| `title` | TEXT | NOT NULL | Article headline |
| `url` | TEXT | NOT NULL, UNIQUE | Original article URL (used for deduplication) |
| `author` | TEXT | — | Article author |
| `published_date` | TIMESTAMP | — | When the article was published |
| `fetched_date` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | When Threat Loom ingested the article |
| `content_raw` | TEXT | — | Scraped article text (populated by scraper) |
| `image_url` | TEXT | — | Thumbnail or hero image URL |

**Indexes**

| Index | Columns | Purpose |
|---|---|---|
| `idx_articles_url` | `url` | Fast deduplication lookups |
| `idx_articles_source` | `source_id` | Filter articles by source |
| `idx_articles_date` | `published_date DESC` | Chronological ordering |

---

### `summaries`

AI-generated article summaries, tags, and attack flows.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PRIMARY KEY | Auto-incrementing summary ID |
| `article_id` | INTEGER | UNIQUE, FOREIGN KEY → articles(id) | One summary per article |
| `summary_text` | TEXT | NOT NULL | Markdown-formatted summary (executive summary, details, mitigations) |
| `key_points` | TEXT | — | JSON string: attack flow steps or legacy bullet points |
| `tags` | TEXT | — | JSON array of categorization tags |
| `novelty_notes` | TEXT | — | What's novel about this threat (extracted from LLM response) |
| `model_used` | TEXT | — | OpenAI model that generated the summary (or "failed") |
| `created_date` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | When the summary was generated |

**Indexes**

| Index | Columns | Purpose |
|---|---|---|
| `idx_summaries_article` | `article_id` | Fast article-to-summary lookup |

**Notes on `key_points`**

This field stores a JSON string. For articles with attack flows, the format is:

```json
[
  {
    "phase": "Initial Access",
    "title": "Spearphishing attachment",
    "description": "Attacker sends weaponized document...",
    "technique": "T1566.001"
  }
]
```

For legacy or non-attack articles, it may contain a simple array of strings.

---

### `article_embeddings`

Vector embeddings for semantic search.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `article_id` | INTEGER | PRIMARY KEY, FOREIGN KEY → articles(id) | One embedding per article |
| `embedding` | BLOB | — | numpy float32 array (1536 dimensions × 4 bytes = 6,144 bytes per row) |
| `model_used` | TEXT | — | Embedding model (always `"text-embedding-3-small"`) |
| `created_date` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | When the embedding was generated |

**BLOB Format**

Embeddings are stored as raw bytes from `numpy.array(floats, dtype=np.float32).tobytes()` and read back with `numpy.frombuffer(blob, dtype=np.float32)`. Each embedding is a 1536-dimensional float32 vector occupying 6,144 bytes.

---

### `category_insights`

Cached trend analysis and forecasts per category.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PRIMARY KEY | Auto-incrementing insight ID |
| `category_name` | TEXT | NOT NULL, UNIQUE | Category key: `"Malware"` or `"Threat Actors::apt29"` for subcategories |
| `trend_text` | TEXT | — | Markdown trend analysis (3-6 paragraphs) |
| `forecast_text` | TEXT | — | Markdown forecast (2-4 paragraphs) |
| `article_count` | INTEGER | — | Number of articles used to generate the insight |
| `article_hash` | TEXT | — | SHA-256 hash (first 16 chars) of sorted article content hashes |
| `model_used` | TEXT | — | OpenAI model that generated the insight |
| `created_date` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | When the insight was generated |

**Cache Key Format**

- Category-level: `"Malware"`, `"Vulnerabilities"`, etc.
- Subcategory-level: `"Threat Actors::apt29"`, `"Malware::lockbit"`, etc.

**Cache Invalidation**

An insight is regenerated when:

- The `article_hash` doesn't match the current hash (new articles added)
- The `created_date` is older than 24 hours

---

### `trend_analyses`

Cached historical trend analysis reports (quarterly and yearly) per category.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PRIMARY KEY | Auto-incrementing ID |
| `category_name` | TEXT | NOT NULL | Category key: `"Malware"` or `"Threat Actors::apt29"` for subcategories |
| `period_type` | TEXT | NOT NULL | `"quarterly"` or `"yearly"` |
| `period_label` | TEXT | NOT NULL | Quarter label (`"2024-Q1"`) or year (`"2024"`) |
| `trend_text` | TEXT | — | Markdown trend analysis for the period |
| `article_count` | INTEGER | — | Number of articles used to generate the analysis |
| `article_hash` | TEXT | — | SHA-256 hash (first 16 chars) of the articles used |
| `model_used` | TEXT | — | LLM model that generated the analysis |
| `created_date` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | When the analysis was generated |

**Unique Constraint**

`UNIQUE(category_name, period_type, period_label)` — one cached entry per category per period.

**Cache Invalidation**

A cached entry is regenerated when the `article_hash` doesn't match the current set of articles for that period. There is no TTL — trend analyses are considered stable once generated. When a time-period filter is active, results are generated fresh and not stored here.

---

### `article_correlations`

Relationships between related articles.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PRIMARY KEY | Auto-incrementing correlation ID |
| `article_id_1` | INTEGER | FOREIGN KEY → articles(id) | First article in the pair |
| `article_id_2` | INTEGER | FOREIGN KEY → articles(id) | Second article in the pair |
| `correlation_type` | TEXT | — | Type of relationship |
| `confidence` | REAL | — | Confidence score (0.0-1.0) |
| `description` | TEXT | — | Human-readable relationship description |
| `created_date` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | When the correlation was identified |

---

## Entity-Relationship Overview

```
sources 1──────────* articles
                       │
                       ├──────────1 summaries
                       │
                       ├──────────1 article_embeddings
                       │
                       └──*───────* article_correlations

category_insights  (standalone, keyed by category_name)
trend_analyses     (standalone, keyed by category_name + period_type + period_label)
```

- Each **source** has many **articles**
- Each **article** has at most one **summary** and one **embedding**
- **Article correlations** link pairs of articles
- **Category insights** are independent, keyed by category name string
- **Trend analyses** are independent, keyed by category name + period type + period label

## Design Decisions

### WAL Mode

SQLite is configured with `PRAGMA journal_mode=WAL`, enabling concurrent reads while a write is in progress. This is important because the pipeline writes data while the Flask server serves read requests.

### Thread-Local Connections

Each thread gets its own database connection to avoid SQLite's thread-safety limitations. This is essential for the multi-threaded architecture (Flask server + scheduler + scraper ThreadPoolExecutor).

### BLOB Embeddings

Embeddings are stored as raw numpy byte arrays rather than using a dedicated vector database. This keeps the stack simple (no Pinecone, Chroma, or Faiss dependency) and works well for datasets under ~100,000 articles. Cosine similarity is computed in Python using numpy.

### Strategic Indexes

Indexes are placed on columns used in frequent queries:

- `articles.url` — Deduplication on every insert
- `articles.source_id` — Source-based filtering
- `articles.published_date` — Chronological sorting
- `summaries.article_id` — Article-summary JOIN

### Foreign Keys

Foreign keys are enabled via `PRAGMA foreign_keys=ON`. Deleting an article cascades to its summary, embedding, and correlations.

### Article Deduplication

The `articles.url` column has a UNIQUE constraint. Duplicate URLs are silently skipped during ingestion (INSERT OR IGNORE pattern).
