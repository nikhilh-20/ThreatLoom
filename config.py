import json
import os
import shutil

# Load .env file if available (for local development)
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass  # python-dotenv not installed, fall back to environment variables

_APP_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get("DATA_DIR") or os.path.join(_APP_DIR, "data")

os.makedirs(DATA_DIR, exist_ok=True)

# Auto-migrate data files from the project root to the data directory.
# This handles the upgrade path for users who ran standalone mode before
# the data/ subdirectory was introduced.
for _fname in ("config.json", "threatlandscape.db"):
    _src = os.path.join(_APP_DIR, _fname)
    _dst = os.path.join(DATA_DIR, _fname)
    if os.path.isfile(_src) and not os.path.isfile(_dst) and _APP_DIR != DATA_DIR:
        shutil.move(_src, _dst)

CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
CONFIG_EXAMPLE_PATH = os.path.join(DATA_DIR, "config.json.example")


def _load_example_feeds():
    """Return the feeds list from ``config.json.example``, or [] if missing."""
    if not os.path.exists(CONFIG_EXAMPLE_PATH):
        return []
    with open(CONFIG_EXAMPLE_PATH, "r", encoding="utf-8") as f:
        return json.load(f).get("feeds", [])


def get_default_config():
    """Return the default configuration dictionary.

    Feeds are sourced from ``data/config.json.example``, which is the single
    source of truth for the pre-configured RSS feed list.

    Returns:
        dict: Default config with empty API keys, ``gpt-5.4-nano`` model,
        30-minute fetch interval, and feeds from ``config.json.example``.
    """
    return {
        "llm_provider": "openai",
        "openai_api_key": "",
        "openai_model": "gpt-5.4-nano",
        "anthropic_api_key": "",
        "anthropic_model": "claude-haiku-4-5-20251001",
        "malpedia_api_key": "",
        "fetch_interval_minutes": 30,
        "feeds": _load_example_feeds(),
        "smtp_host": "",
        "smtp_port": 587,
        "smtp_username": "",
        "smtp_password": "",
        "smtp_use_tls": True,
        "notification_email": "",
        "email_notifications_enabled": False,
        "email_mode": "per_article",
        "digest_period": "day",
        "report_token": "",
    }


def load_config():
    """Load configuration from ``config.json``, creating it with defaults if absent.

    On every load, feeds from ``config.json.example`` are merged in by URL so
    that new feeds added to the example are picked up by existing installations.

    Returns:
        dict: The parsed configuration dictionary.
    """
    if not os.path.exists(CONFIG_PATH):
        config = get_default_config()
        save_config(config)
    else:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)

        # Merge any feeds from the example file that aren't in config.json yet.
        existing_urls = {f["url"] for f in config.get("feeds", [])}
        new_feeds = [f for f in _load_example_feeds() if f["url"] not in existing_urls]
        if new_feeds:
            config.setdefault("feeds", []).extend(new_feeds)
            save_config(config)

    # API keys, SMTP, and email recipients are managed through the Settings page
    # and stored in config.json — they are intentionally NOT read from the
    # environment, so secrets never need to live in the process environment.
    return config


def save_config(config):
    """Write the configuration dictionary to ``config.json``.

    Args:
        config: The configuration dictionary to persist.
    """
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2, ensure_ascii=False)
