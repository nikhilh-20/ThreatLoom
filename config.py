import json
import os
import shutil

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


def get_default_config():
    """Return the hardcoded default configuration dictionary.

    Returns:
        dict: Default config with empty API keys, ``gpt-4o-mini`` model,
        30-minute fetch interval, and 13 pre-configured RSS feeds.
    """
    return {
        "openai_api_key": "",
        "malpedia_api_key": "",
        "openai_model": "gpt-4o-mini",
        "fetch_interval_minutes": 30,
        "feeds": [
            {"name": "The Hacker News", "url": "https://feeds.feedburner.com/TheHackersNews", "enabled": True},
            {"name": "BleepingComputer", "url": "https://www.bleepingcomputer.com/feed/", "enabled": True},
            {"name": "Krebs on Security", "url": "https://krebsonsecurity.com/feed/", "enabled": True},
            {"name": "SecurityWeek", "url": "https://feeds.feedburner.com/securityweek", "enabled": True},
            {"name": "Dark Reading", "url": "https://www.darkreading.com/rss.xml", "enabled": True},
            {"name": "CISA Alerts", "url": "https://www.cisa.gov/cybersecurity-advisories/all.xml", "enabled": True},
            {"name": "Sophos News", "url": "https://news.sophos.com/en-us/feed/", "enabled": True},
            {"name": "Infosecurity Magazine", "url": "https://www.infosecurity-magazine.com/rss/news/", "enabled": True},
            {"name": "HackRead", "url": "https://hackread.com/feed/", "enabled": True},
            {"name": "SC Media", "url": "https://www.scworld.com/rss", "enabled": True},
            {"name": "Cyber Defense Magazine", "url": "https://www.cyberdefensemagazine.com/feed/", "enabled": False},
            {"name": "The Record", "url": "https://therecord.media/feed", "enabled": True},
            {"name": "Schneier on Security", "url": "https://www.schneier.com/feed/atom/", "enabled": True},
        ],
        "smtp_host": "",
        "smtp_port": 587,
        "smtp_username": "",
        "smtp_password": "",
        "smtp_use_tls": True,
        "notification_email": "",
        "email_notifications_enabled": False,
    }


def load_config():
    """Load configuration from ``config.json``, creating it with defaults if absent.

    Returns:
        dict: The parsed configuration dictionary.
    """
    if not os.path.exists(CONFIG_PATH):
        config = get_default_config()
        save_config(config)
    else:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)

    # Environment variables override file-based keys
    env_openai = os.environ.get("OPENAI_API_KEY")
    if env_openai:
        config["openai_api_key"] = env_openai
    env_malpedia = os.environ.get("MALPEDIA_API_KEY")
    if env_malpedia:
        config["malpedia_api_key"] = env_malpedia

    env_smtp_host = os.environ.get("SMTP_HOST")
    if env_smtp_host:
        config["smtp_host"] = env_smtp_host
    env_smtp_port = os.environ.get("SMTP_PORT")
    if env_smtp_port:
        config["smtp_port"] = int(env_smtp_port)
    env_smtp_user = os.environ.get("SMTP_USERNAME")
    if env_smtp_user:
        config["smtp_username"] = env_smtp_user
    env_smtp_pass = os.environ.get("SMTP_PASSWORD")
    if env_smtp_pass:
        config["smtp_password"] = env_smtp_pass
    env_notif_email = os.environ.get("NOTIFICATION_EMAIL")
    if env_notif_email:
        config["notification_email"] = env_notif_email
        config["email_notifications_enabled"] = True

    return config


def save_config(config):
    """Write the configuration dictionary to ``config.json``.

    Args:
        config: The configuration dictionary to persist.
    """
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2, ensure_ascii=False)
