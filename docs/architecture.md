# Architecture

Threat Loom is a monolithic Python application with a clear separation of concerns across modules. This page describes the system architecture, data flow, and key design decisions.

## High-Level Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                         BROWSER (UI)                             │
│  index.html · article.html · intelligence.html · settings.html   │
│  app.js (state, API calls) · style.css (dark theme)             │
└──────────────────────────┬───────────────────────────────────────┘
                           │ HTTP
┌──────────────────────────▼───────────────────────────────────────┐
│                      Flask (app.py)                              │
│  Page Routes: / · /article/<id> · /intelligence · /settings     │
│  REST API:  /api/articles · /api/refresh · /api/intelligence/*  │
└──────┬───────────────┬───────────────┬───────────────┬───────────┘
       │               │               │               │
┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐ ┌───────────┐
│ scheduler   │ │ summarizer  │ │ embeddings  │ │intelligence │ │ notifier  │
│             │ │             │ │             │ │             │ │           │
│ APScheduler │ │ OpenAI Chat │ │ OpenAI Emb  │ │ RAG Chat    │ │ Email     │
│ Pipeline    │ │ Relevance   │ │ Cosine Sim  │ │ Semantic    │ │ Alerts    │
│ Orchestrate │ │ Insights    │ │ BLOB Store  │ │ Search      │ │ (SMTP)    │
└──────┬──────┘ └─────────────┘ └─────────────┘ └─────────────┘ └───────────┘
       │
┌──────▼──────────────────────────────────────────────────────────┐
│                    Data Ingestion Layer                          │
│  feed_fetcher.py    13 RSS/Atom feeds + custom sources          │
│  malpedia_fetcher.py  BibTeX research bibliography              │
│  article_scraper.py   HTML download + text extraction           │
└──────┬──────────────────────────────────────────────────────────┘
       │
┌──────▼──────────────────────────────────────────────────────────┐
│                  SQLite (database.py)                            │
│  sources · articles · summaries · article_embeddings            │
│  category_insights · article_correlations                       │
│  WAL mode · thread-local connections · strategic indexes        │
└─────────────────────────────────────────────────────────────────┘
```

## Data Pipeline

Every pipeline run — whether triggered manually or by the scheduler — executes these stages in order:

```
1. Clean Up          Delete articles with file URLs (PDFs, DOCs)
       │
2. Fetch Feeds       Download RSS/Atom entries from enabled sources
       │
3. Fetch Malpedia    Pull BibTeX research articles (if API key set)
       │
4. Scrape            Download article HTML → extract text
       │
5. Summarize         LLM generates structured summary + tags
       │
6. Notify            Send email alert per article (if enabled)
       │
7. Embed             Generate vector embeddings for search
```

Each stage operates on articles the previous stage produced. Stages are idempotent — rerunning the pipeline only processes new or unprocessed articles.

### Stage Details

| Stage | Module | Batch Size | What It Does |
|---|---|---|---|
| Clean Up | `database.py` | All | Remove articles pointing to file URLs (.pdf, .doc, etc.) |
| Fetch Feeds | `feed_fetcher.py` | 25 titles | Parse feeds, LLM relevance filter, insert new articles |
| Fetch Malpedia | `malpedia_fetcher.py` | 25 titles | Parse BibTeX, relevance filter, insert articles |
| Scrape | `article_scraper.py` | 10 articles | Download HTML, extract text via trafilatura |
| Summarize | `summarizer.py` | 10 articles | Generate summary, tags, attack flow via OpenAI |
| Notify | `notifier.py` | Per article | Send email notification for each summarized article (if enabled) |
| Embed | `embeddings.py` | 50 articles | Generate 1536-dim vectors via text-embedding-3-small |

## Module Responsibilities

### `app.py` — Web Server

Flask application serving the UI and REST API. Handles routing, request validation, and response formatting. On startup, initializes the database, syncs feed sources from config, starts the scheduler, and opens the browser.

### `scheduler.py` — Pipeline Orchestrator

Manages the background pipeline using APScheduler. Runs on a configurable interval (default 30 minutes). Provides a thread-locked `trigger_manual_refresh()` for on-demand runs. Only one pipeline can run at a time.

### `feed_fetcher.py` — RSS/Atom Ingestion

Fetches articles from configured RSS and Atom feeds. Uses `requests` with a feed-reader User-Agent (falls back to `feedparser` on failure). Applies date-based filtering and LLM relevance classification in batches of 25.

### `malpedia_fetcher.py` — Research Ingestion

Fetches the Malpedia BibTeX bibliography (~4.5 MB). Parses entries with regex, filters by date, and runs relevance checks identical to the feed fetcher.

### `article_scraper.py` — Content Extraction

Downloads article HTML using a browser-like `requests.Session` (with fallback to `trafilatura.fetch_url`). Extracts clean text via `trafilatura.extract()`. Each article has a 30-second timeout via ThreadPoolExecutor.

### `summarizer.py` — AI Analysis

Three core functions:

- **Relevance classification** — Batch-classify article titles as relevant/irrelevant for threat intelligence
- **Article summarization** — Generate structured JSON (executive summary, novelty, details, mitigations, tags, attack flow)
- **Category insights** — Produce trend analysis and 3-6 month forecasts for threat categories

### `notifier.py` — Email Notifications

Sends per-article email alerts after successful summarization. Builds inline-CSS HTML emails with the full structured analysis (executive summary, novelty, details, mitigations) and a link to the original article. Uses only Python stdlib (`smtplib`, `email.mime`) — no external dependencies. Errors are logged but never raised, so email failures cannot block the pipeline. Supports STARTTLS on port 587 by default.

### `embeddings.py` — Vector Search

Generates OpenAI `text-embedding-3-small` embeddings (1536 dimensions) for summarized articles. Stores vectors as numpy float32 BLOBs in SQLite. Implements cosine similarity search for the RAG pipeline.

### `intelligence.py` — RAG Chat

Retrieval-Augmented Generation system. Takes a user query, runs semantic search to find relevant articles, builds a context window (capped at 30,000 characters), and calls OpenAI to generate a grounded response with citations.

### `database.py` — Data Layer

SQLite interface with thread-local connections, WAL mode, and foreign keys. Manages 6 tables plus a categorization layer that maps tags to 9 broad threat categories using keyword rules and MITRE ATT&CK entity lookups.

### `config.py` — Configuration

Loads and saves `config.json`. Provides defaults for all settings including the 13 pre-configured feeds. Manages the `DATA_DIR` resolution: uses the `DATA_DIR` environment variable if set, otherwise defaults to the `data/` subdirectory. On first run, auto-migrates any existing `config.json` or `threatlandscape.db` from the project root into `data/`.

### `mitre_data.py` — ATT&CK Taxonomy

Provides lookup sets of MITRE ATT&CK threat actor groups, software/tools, and techniques used by the categorization layer for entity normalization.

## Threading Model

```
Main Thread
├── Flask HTTP server
│
├── APScheduler Thread (daemon)
│   └── Pipeline execution (locked via threading.Lock)
│       ├── Feed fetching (sequential per source)
│       ├── Scraping (ThreadPoolExecutor per article)
│       └── Summarization / Embedding (sequential batches)
│
└── Manual Refresh Thread (daemon, spawned on-demand)
    └── Same pipeline, same lock (only one runs at a time)
```

- The pipeline uses `Lock.acquire(blocking=False)` to atomically check-and-lock — if another thread already holds the lock, the attempt is skipped immediately with no race window
- `is_refreshing()` exposes pipeline state for the UI status poll
- SQLite uses thread-local connections to avoid cross-thread access issues
- The ThreadPoolExecutor in the scraper provides per-article timeouts without blocking the pipeline
- All date comparisons use UTC (`datetime.utcnow()`, `datetime.utcfromtimestamp()`) to stay consistent with SQLite's `CURRENT_TIMESTAMP`

## Technology Choices

| Choice | Rationale |
|---|---|
| **Flask** | Lightweight, sufficient for a single-user tool, simple template rendering |
| **SQLite + WAL** | Zero-config, embedded, WAL enables concurrent reads during writes |
| **OpenAI API** | Best-in-class for structured summarization, embeddings, and chat |
| **trafilatura** | Robust article text extraction with broad site compatibility |
| **feedparser** | Battle-tested RSS/Atom parsing library |
| **APScheduler** | Simple background scheduling without external dependencies |
| **numpy** | Fast vector operations for cosine similarity computation |
| **BLOB embeddings** | Avoids external vector DB dependency; sufficient for <100k articles |

## Key Design Decisions

**Monolithic over microservices** — A single Python process keeps deployment simple. The target use case is a single analyst or small team, not enterprise scale.

**SQLite over Postgres** — No external database to install or manage. WAL mode handles the read-heavy workload well. BLOB storage for embeddings avoids adding a vector database.

**LLM relevance filtering** — Feed sources often contain non-security content (product announcements, opinion pieces). Batch-classifying titles via LLM keeps the database focused on actual threat intelligence.

**Structured JSON summaries** — The summarizer requests JSON output with explicit fields (summary, tags, attack_flow) rather than free-form text. This enables programmatic categorization and the attack flow visualization.

**Client-side rendering** — Markdown summaries are rendered in the browser via `marked.js`. This keeps the server simple and avoids server-side markdown dependencies.
