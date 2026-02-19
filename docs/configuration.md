# Configuration

Threat Loom is configured through `config.json` in the `data/` subdirectory. All settings can also be managed through the **Settings** page in the web UI.

## Configuration File

On first run, `data/config.json` is created with default values:

```json
{
  "openai_api_key": "",
  "openai_model": "gpt-4o-mini",
  "anthropic_api_key": "",
  "anthropic_model": "claude-haiku-4-5-20251001",
  "llm_provider": "openai",
  "fetch_interval_minutes": 30,
  "malpedia_api_key": "",
  "feeds": [
    {
      "name": "The Hacker News",
      "url": "https://feeds.feedburner.com/TheHackersNews",
      "enabled": true
    }
  ]
}
```

## Configuration Reference

### LLM Provider

| Key | Type | Default | Description |
|---|---|---|---|
| `llm_provider` | string | `"openai"` | Active LLM provider. Either `"openai"` or `"anthropic"`. |

### OpenAI Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `openai_api_key` | string | `""` | Your OpenAI API key. Required for summarization, relevance filtering, embeddings, and intelligence chat. Also required for embeddings when using the Anthropic provider. |
| `openai_model` | string | `"gpt-4o-mini"` | OpenAI model used for summarization, relevance checks, and insights. |

#### OpenAI Model Options

| Model | Speed | Cost | Quality | Best For |
|---|---|---|---|---|
| `gpt-4o-mini` | Fast | Low | Good | Daily use, high-volume processing |
| `gpt-4o` | Medium | Medium | Excellent | Higher quality summaries and insights |
| `gpt-4-turbo` | Medium | High | Excellent | Complex analysis tasks |
| `gpt-3.5-turbo` | Fast | Lowest | Adequate | Budget-conscious processing |

### Anthropic Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `anthropic_api_key` | string | `""` | Your Anthropic API key. Used for summarization and insights when `llm_provider` is `"anthropic"`. |
| `anthropic_model` | string | `"claude-haiku-4-5-20251001"` | Anthropic model used for summarization and insights. |

#### Anthropic Model Options

| Model | Speed | Cost | Quality | Best For |
|---|---|---|---|---|
| `claude-haiku-4-5-20251001` | Fast | Low | Good | Daily use, high-volume processing |
| `claude-sonnet-4-6` | Medium | Medium | Excellent | Higher quality summaries and insights |
| `claude-opus-4-6` | Medium | High | Best | Highest quality, complex analysis |

!!! tip "Recommendation"
    Start with `gpt-4o-mini` (OpenAI) or `claude-haiku-4-5-20251001` (Anthropic) for the best balance of speed and cost. Switch to a larger model if you need higher-quality trend analysis and attack flow generation.

!!! info "Embeddings"
    The embedding model is fixed at `text-embedding-3-small` (1536 dimensions) and always uses OpenAI, regardless of the active `llm_provider`. An OpenAI API key is required even when using Anthropic for summarization.

### Fetch Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `fetch_interval_minutes` | integer | `30` | How often the background pipeline runs (in minutes). |

The scheduler triggers the full pipeline (fetch, scrape, summarize, embed) at this interval. Set to a higher value to reduce API usage, or lower for near-real-time ingestion.

### Malpedia Integration

| Key | Type | Default | Description |
|---|---|---|---|
| `malpedia_api_key` | string | `""` | API token for Malpedia research library access. |

Malpedia provides curated threat research articles from the security community. When configured, the pipeline fetches the BibTeX bibliography and imports relevant entries.

To get a key:

1. Register at [malpedia.caad.fkie.fraunhofer.de](https://malpedia.caad.fkie.fraunhofer.de/)
2. Navigate to your profile
3. Generate an API token

### Email Notifications

| Key | Type | Default | Description |
|---|---|---|---|
| `email_notifications_enabled` | boolean | `false` | Enable per-article email alerts. |
| `notification_email` | string | `""` | Recipient email address. |
| `smtp_host` | string | `""` | SMTP server hostname (e.g. `smtp.gmail.com`). |
| `smtp_port` | integer | `587` | SMTP server port. |
| `smtp_username` | string | `""` | SMTP login username. |
| `smtp_password` | string | `""` | SMTP login password or app password. |
| `smtp_use_tls` | boolean | `true` | Use STARTTLS encryption. |

See [Email Notifications](features/email-notifications.md) for setup guides and common SMTP provider configurations.

### Feed Management

The `feeds` array contains all RSS/Atom sources:

```json
{
  "feeds": [
    {
      "name": "Feed Display Name",
      "url": "https://example.com/feed.xml",
      "enabled": true
    }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `name` | string | Display name shown in the UI |
| `url` | string | RSS or Atom feed URL |
| `enabled` | boolean | Whether the feed is fetched during pipeline runs |

## Default Feeds

Threat Loom ships with 13 pre-configured cybersecurity feeds:

| Feed | URL | Default |
|---|---|---|
| The Hacker News | `feeds.feedburner.com/TheHackersNews` | Enabled |
| BleepingComputer | `bleepingcomputer.com/feed/` | Enabled |
| Krebs on Security | `krebsonsecurity.com/feed/` | Enabled |
| SecurityWeek | `feeds.feedburner.com/securityweek` | Enabled |
| Dark Reading | `darkreading.com/rss.xml` | Enabled |
| CISA Alerts | `cisa.gov/cybersecurity-advisories/all.xml` | Enabled |
| Sophos News | `news.sophos.com/en-us/feed/` | Enabled |
| Infosecurity Magazine | `infosecurity-magazine.com/rss/news/` | Enabled |
| HackRead | `hackread.com/feed/` | Enabled |
| SC Media | `scmagazine.com/feed` | Disabled |
| Cyber Defense Magazine | `cyberdefensemagazine.com/feed/` | Disabled |
| The Record | `therecord.media/feed/` | Enabled |
| Schneier on Security | `schneier.com/feed/` | Enabled |

## Adding Custom Feeds

### Via the UI

1. Go to **Settings**
2. Scroll to the **RSS Feeds** section
3. Enter a **Name** and **URL** at the bottom
4. Click **Add Feed**
5. Click **Save Settings**

### Via `config.json`

Add an entry to the `feeds` array:

```json
{
  "feeds": [
    {
      "name": "My Custom Feed",
      "url": "https://example.com/threat-feed.xml",
      "enabled": true
    }
  ]
}
```

!!! warning "Feed Format"
    Only RSS 2.0 and Atom feeds are supported. The feed must return valid XML with standard entry elements (title, link, published date).

## Settings UI

The Settings page provides a graphical interface for all configuration:

- **LLM Provider** — Select between OpenAI and Anthropic
- **API Keys** — Enter and test OpenAI, Anthropic, and Malpedia keys
- **Model Selection** — Dropdown for the active provider's model
- **Fetch Interval** — Slider to adjust pipeline frequency
- **Email Notifications** — Configure SMTP and test email delivery
- **Feed Management** — Enable/disable feeds, add/remove custom sources (http/https URLs only)
- **Refresh Controls** — Trigger manual refresh with lookback period
- **Clear Database** — Remove all articles and summaries (preserves source list)

!!! info "Screenshot"
    _A screenshot of the Settings page can be added here._
