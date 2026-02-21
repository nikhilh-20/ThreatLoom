# Feed Aggregation

Threat Loom automatically collects threat intelligence articles from RSS/Atom feeds and the Malpedia research library. A built-in LLM relevance filter ensures only genuine threat intelligence enters the database.

## Supported Formats

- **RSS 2.0** — Standard syndication format used by most security blogs
- **Atom** — Alternative feed format, fully supported

The feed parser uses `feedparser` with a `requests`-based fallback for sites that block feed readers.

## Pre-Configured Sources

Threat Loom ships with 13 curated cybersecurity feeds:

| # | Source | Type | Default |
|---|---|---|---|
| 1 | The Hacker News | News | Enabled |
| 2 | BleepingComputer | News | Enabled |
| 3 | Krebs on Security | Blog | Enabled |
| 4 | SecurityWeek | News | Enabled |
| 5 | Dark Reading | News | Enabled |
| 6 | CISA Alerts | Government | Enabled |
| 7 | Sophos News | Vendor Research | Enabled |
| 8 | Infosecurity Magazine | News | Enabled |
| 9 | HackRead | News | Enabled |
| 10 | SC Media | News | Disabled |
| 11 | Cyber Defense Magazine | News | Disabled |
| 12 | The Record | News | Enabled |
| 13 | Schneier on Security | Blog | Enabled |

## How Fetching Works

### RSS/Atom Pipeline

1. **Iterate enabled feeds** — Each feed is processed sequentially
2. **Download feed XML** — `requests` with a feed-reader User-Agent (20-second timeout)
3. **Parse entries** — Extract title, URL, author, published date, image
4. **Date filtering** — Skip articles older than the lookback period (default: 1 day)
5. **Skip file URLs** — Ignore links to PDFs, DOCs, ZIPs, and other non-web content
6. **Deduplication** — Skip articles whose URL is already in the database
7. **Relevance filtering** — Batch-classify titles via LLM (see below)
8. **Insert** — Store relevant articles in the database
9. **Update timestamp** — Record `last_fetched` for the source

### Lookback Period

When refreshing, you can specify a lookback window:

- **By days** — Fetch articles published within the last N days (default: 1)
- **Since last fetch** — Only fetch articles newer than each source's `last_fetched` timestamp

The scheduler uses the default 1-day lookback. Manual refreshes allow custom lookback periods via the UI.

## Relevance Filtering

Not all articles from security feeds are threat intelligence. Many are product announcements, opinion pieces, or general IT news. Threat Loom uses an LLM to classify relevance:

1. **Batch titles** — Up to 25 article titles per LLM call
2. **Classify** — The model labels each as `RELEVANT` or `IRRELEVANT` based on threat research value
3. **Filter** — Only articles classified as relevant are inserted

!!! info "No API Key Fallback"
    If no OpenAI API key is configured, relevance filtering is skipped and all articles are accepted. This allows basic operation without an API key, though the database will contain more noise.

## Malpedia Integration

[Malpedia](https://malpedia.caad.fkie.fraunhofer.de/) is a curated repository of threat research maintained by Fraunhofer FKIE. Threat Loom integrates with it as an additional article source.

### How It Works

1. **Fetch BibTeX** — Download the full bibliography from `/api/get/bib` (~4.5 MB, 60-second timeout)
2. **Parse entries** — Extract title, URL, author, organization, and date using regex
3. **Date filter** — Same lookback logic as RSS feeds
4. **Relevance check** — Same LLM batch classification
5. **Insert** — Store as articles with source "Malpedia"

### Setup

A Malpedia API key is required. See [Configuration](../configuration.md#malpedia-integration) for setup instructions.

## Adding Custom Feeds

You can add any RSS or Atom feed as a source. See [Configuration — Adding Custom Feeds](../configuration.md#adding-custom-feeds) for instructions.

!!! tip "Finding Feed URLs"
    Most security blogs and news sites offer RSS feeds. Common URL patterns:

    - `/feed/` or `/rss/`
    - `/feed.xml` or `/rss.xml`
    - Check the page source for `<link rel="alternate" type="application/rss+xml">`

## URL Ingestion

In addition to scheduled feed fetching, you can process specific article URLs on demand using the **Ingest URLs** button in the dashboard header.

### How It Works

1. Click **Ingest URLs** in the header
2. Paste one URL per line in the dialog
3. Click **Process** — the pipeline runs scrape → cost gate → summarize → embed for each new URL

URLs already in the database are skipped. Invalid schemes (non-http/https) are rejected. The job runs in the background using the same pipeline lock as a full refresh.

### API

```json
POST /api/ingest-urls
{
  "urls": ["https://example.com/threat-report"]
}
```

This is equivalent to adding a one-off article source without modifying your feed list.

## Skipped Content

The fetcher automatically skips URLs pointing to non-web content:

- Documents: `.pdf`, `.doc`, `.docx`
- Spreadsheets: `.xls`, `.xlsx`
- Archives: `.zip`, `.tar`, `.gz`
- Executables: `.exe`, `.msi`
- Images and other binary formats

These URLs cannot be scraped for text content and are filtered out during ingestion.
