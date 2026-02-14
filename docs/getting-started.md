# Getting Started

This guide walks you through installing Threat Loom, configuring your API keys, and running your first intelligence pipeline.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Python | 3.10+ | Tested on 3.11 and 3.12 |
| pip | Latest | Comes with Python |
| OpenAI API key | — | Required for summarization, search, and insights |
| Malpedia API key | — | Optional, for research article ingestion |

## Installation

### 1. Clone the Repository

```bash
git clone https://github.com/nikhilh-20/ThreatLoom.git
cd ThreatLoom
```

### 2. Create a Virtual Environment (Recommended)

=== "Linux / macOS"

    ```bash
    python3 -m venv venv
    source venv/bin/activate
    ```

=== "Windows"

    ```bash
    python -m venv venv
    venv\Scripts\activate
    ```

### 3. Install Dependencies

```bash
pip install -r requirements.txt
```

The key dependencies are:

| Package | Purpose |
|---|---|
| `flask` | Web framework and API server |
| `feedparser` | RSS/Atom feed parsing |
| `trafilatura` | Article content extraction |
| `openai` | LLM summarization and embeddings |
| `apscheduler` | Background pipeline scheduling |
| `numpy` | Vector similarity computation |
| `requests` | HTTP client |
| `lxml_html_clean` | HTML sanitization |

## Configuration

### Add Your OpenAI API Key

You can configure the key in two ways:

**Option A — Settings UI (recommended)**

1. Run the app (see below)
2. Navigate to **Settings**
3. Enter your API key and click **Test** to verify
4. Click **Save Settings**

**Option B — Edit `data/config.json` directly**

```json
{
  "openai_api_key": "sk-proj-your-key-here",
  "openai_model": "gpt-4o-mini",
  "fetch_interval_minutes": 30
}
```

!!! tip "Model Selection"
    `gpt-4o-mini` is the default — fast and cost-effective. Use `gpt-4o` for higher quality summaries and insights. See [Configuration](configuration.md) for all model options.

### Malpedia API Key (Optional)

Malpedia provides curated threat research articles. To enable it:

1. Register at [malpedia.caad.fkie.fraunhofer.de](https://malpedia.caad.fkie.fraunhofer.de/)
2. Generate an API token from your profile
3. Add it in **Settings** or in `data/config.json` under `"malpedia_api_key"`

## Docker Setup

If you prefer running Threat Loom in a container, Docker is the quickest way to get started.

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/) (v2+)

### Quick Start

```bash
OPENAI_API_KEY=sk-proj-your-key-here docker compose up
```

Or set the key in `docker-compose.yml` under the `environment` section, then run:

```bash
docker compose up
```

The app will be available at **http://localhost:5000**.

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `OPENAI_API_KEY` | — | OpenAI API key (overrides `config.json`) |
| `MALPEDIA_API_KEY` | — | Optional Malpedia API key |
| `HOST` | `0.0.0.0` | Bind address (set by Dockerfile) |
| `PORT` | `5000` | Listen port |
| `DATA_DIR` | `./data` (standalone) or `/app/data` (Docker) | Directory for `config.json` and `threatlandscape.db` |

### Data Persistence

The `docker-compose.yml` bind-mounts the local `./data` directory into the container at `/app/data`. This means the database (`threatlandscape.db`) and config (`config.json`) are shared between standalone and Docker modes — if you populated data while running standalone, it will be available inside Docker automatically.

To start fresh, stop the container and delete the data files:

```bash
docker compose down
rm data/threatlandscape.db
```

---

## First Run

```bash
python app.py
```

On startup, Threat Loom will:

1. **Initialize the data directory** — Creates the `data/` subdirectory and migrates any existing `config.json` or `threatlandscape.db` from the project root into it
2. **Initialize the database** — Creates `data/threatlandscape.db` with all tables
2. **Sync feed sources** — Registers the 13 default RSS feeds
3. **Find a free port** — Starting from port 5000
4. **Open your browser** — Navigates to the dashboard automatically
5. **Start the scheduler** — Background pipeline runs every N minutes (default 30)

!!! info "First Run is Empty"
    The dashboard will be empty on first launch. Click the **Refresh** button to trigger the ingestion pipeline manually. Depending on your configuration, the first run may take a few minutes as it fetches, scrapes, and summarizes articles.

## The Pipeline

When you click **Refresh** (or the scheduler triggers automatically), the pipeline runs these steps in order:

1. **Fetch feeds** — Download articles from enabled RSS/Atom sources
2. **Fetch Malpedia** — Pull research articles (if API key configured)
3. **Scrape content** — Download and extract article text
4. **Summarize** — Generate structured AI summaries with tags and attack flows
5. **Embed** — Create vector embeddings for semantic search

Each step processes articles in batches. The pipeline is non-blocking — you can continue browsing while it runs.

## Troubleshooting

??? warning "No articles appearing after refresh"
    - Check that your OpenAI API key is valid (use the **Test** button in Settings)
    - Ensure at least some feeds are enabled
    - Check the terminal/console for error messages
    - Some feeds may be temporarily unavailable

??? warning "Port already in use"
    Threat Loom automatically finds a free port starting from 5000. If you see connection errors, check that no other process is using the selected port.

??? warning "Summaries not generating"
    - Verify your OpenAI API key has sufficient credits
    - Check that articles have been scraped first (content must be extracted before summarization)
    - Rate limiting may slow down processing — the pipeline retries with exponential backoff

??? warning "Import errors on startup"
    Make sure you're using the virtual environment and all dependencies are installed:
    ```bash
    pip install -r requirements.txt
    ```

## Security

The project undergoes periodic security scanning. See the [Security Audit Report](security-audit.md) for full results covering dependency vulnerabilities, static analysis, secret scanning, and container image review.

To run the scans yourself:

```bash
pip install pip-audit bandit detect-secrets
pip-audit                                          # dependency CVEs
bandit -r . --exclude ./venv                       # static analysis
detect-secrets scan --exclude-files 'venv/.*'      # secret detection
```
