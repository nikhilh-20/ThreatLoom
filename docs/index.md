<div class="hero" markdown>

# Threat Loom

<p class="tagline">AI-powered threat news analysis platform — aggregation, summarization, and forecasting</p>

</div>

Threat Loom is a self-hosted threat intelligence platform that **automatically collects**, **summarizes**, and **categorizes** cybersecurity articles from dozens of sources — then lets you **search**, **explore**, and **forecast** threats using AI.

<div class="badge-row" markdown>

![Python](https://img.shields.io/badge/Python-3.10+-3776AB?logo=python&logoColor=white)
![Flask](https://img.shields.io/badge/Flask-3.0-000000?logo=flask)
![OpenAI](https://img.shields.io/badge/OpenAI-GPT--4o-412991?logo=openai&logoColor=white)
![Anthropic](https://img.shields.io/badge/Anthropic-Claude-D97757?logoColor=white)
![SQLite](https://img.shields.io/badge/SQLite-WAL-003B57?logo=sqlite&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

</div>

---

<div class="feature-grid" markdown>

<div class="feature-card" markdown>

### :material-rss: Feed Aggregation

13 pre-configured security feeds plus Malpedia research. LLM-based relevance filtering ensures only threat intel makes it in.

</div>

<div class="feature-card" markdown>

### :material-brain: AI Summarization

Structured summaries with executive overview, novelty assessment, technical details, mitigations, and MITRE ATT&CK tags. Supports OpenAI and Anthropic providers.

</div>

<div class="feature-card" markdown>

### :material-chart-timeline-variant-shimmer: Attack Flow

Interactive kill chain visualization showing phase-by-phase attack sequences with MITRE tactic mapping.

</div>

<div class="feature-card" markdown>

### :material-search-web: Semantic Search

RAG-powered intelligence chat. Ask questions in natural language and get answers grounded in your collected articles.

</div>

<div class="feature-card" markdown>

### :material-chart-bar: Historical Trend Analysis

Quarter-by-quarter and year-by-year retrospectives with cross-period correlation. Collapsible panels per period with key developments and outlook.

</div>

<div class="feature-card" markdown>

### :material-trending-up: Trend Forecasting

Category-level current-trend analysis with 3-6 month forecasts. Drill into threat actors, malware families, and tooling. Cost estimated before generation.

</div>

<div class="feature-card" markdown>

### :material-clock-fast: Time-Period Filter

Filter the feed and all category analyses by 24 h, 7 d, 30 d, or 90 d lookback with one click. Filter propagates to trend analysis and forecast generation.

</div>

<div class="feature-card" markdown>

### :material-email-alert-outline: Email Alerts

Per-article email notifications with full structured analysis. Configure any SMTP provider — Gmail, Outlook, SendGrid, and more.

</div>

</div>

---

## Quick Start

```bash
git clone https://github.com/nikhilh-20/ThreatLoom.git
cd ThreatLoom
pip install -r requirements.txt
python app.py
```

The app opens in your browser automatically. Head to **Settings** to add your OpenAI API key, then hit **Refresh** to start ingesting feeds.

!!! info "Screenshot"
    _A screenshot or GIF of the Threat Loom dashboard can be added here._

---

## How It Works

```
RSS Feeds / Malpedia
        |
  Relevance Filter (LLM)
        |
  Scrape Article Content
        |
  AI Summarization + Tagging
        |
  Vector Embeddings
        |
  Browse · Search · Forecast
```

Each article flows through an automated pipeline — from ingestion to structured intelligence — with no manual intervention required.

---

## What's Next

- **[Getting Started](getting-started.md)** — Install, configure, and run your first pipeline
- **[Architecture](architecture.md)** — Understand the system design and data flow
- **[Configuration](configuration.md)** — Tune feeds, models, and fetch intervals
- **[API Reference](api-reference.md)** — Integrate with the REST API
