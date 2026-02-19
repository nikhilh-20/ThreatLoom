import hashlib
import json as _json
import re
import sqlite3
import os
import threading

from config import DATA_DIR
from mitre_data import KNOWN_THREAT_ACTORS, KNOWN_SOFTWARE

DB_PATH = os.path.join(DATA_DIR, "threatlandscape.db")

_local = threading.local()


def get_connection():
    """Get or create a thread-local SQLite database connection.

    Each thread receives its own connection with WAL journal mode and
    foreign keys enabled. Connections are cached in thread-local storage.

    Returns:
        A ``sqlite3.Connection`` with ``Row`` row factory.
    """
    if not hasattr(_local, "conn") or _local.conn is None:
        _local.conn = sqlite3.connect(DB_PATH, check_same_thread=False)
        _local.conn.row_factory = sqlite3.Row
        _local.conn.execute("PRAGMA journal_mode=WAL")
        _local.conn.execute("PRAGMA foreign_keys=ON")
    return _local.conn


def init_db():
    """Initialize the database schema.

    Creates all tables and indexes if they do not already exist:
    ``sources``, ``articles``, ``summaries``, ``article_correlations``,
    ``category_insights``, and ``article_embeddings``.
    """
    conn = get_connection()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            url TEXT NOT NULL UNIQUE,
            enabled INTEGER DEFAULT 1,
            last_fetched TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS articles (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            url TEXT NOT NULL UNIQUE,
            author TEXT,
            published_date TIMESTAMP,
            fetched_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            content_raw TEXT,
            image_url TEXT,
            FOREIGN KEY (source_id) REFERENCES sources(id)
        );

        CREATE TABLE IF NOT EXISTS summaries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            article_id INTEGER NOT NULL UNIQUE,
            summary_text TEXT NOT NULL,
            key_points TEXT,
            tags TEXT,
            novelty_notes TEXT,
            model_used TEXT,
            created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (article_id) REFERENCES articles(id)
        );

        CREATE TABLE IF NOT EXISTS article_correlations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            article_id_1 INTEGER NOT NULL,
            article_id_2 INTEGER NOT NULL,
            correlation_type TEXT,
            confidence REAL,
            description TEXT,
            created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (article_id_1) REFERENCES articles(id),
            FOREIGN KEY (article_id_2) REFERENCES articles(id)
        );

        CREATE TABLE IF NOT EXISTS category_insights (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            category_name TEXT NOT NULL UNIQUE,
            trend_text TEXT,
            forecast_text TEXT,
            article_count INTEGER,
            article_hash TEXT,
            model_used TEXT,
            created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS trend_analyses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            category_name TEXT NOT NULL,
            period_type TEXT NOT NULL,
            period_label TEXT NOT NULL,
            trend_text TEXT,
            article_count INTEGER,
            article_hash TEXT,
            model_used TEXT,
            created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(category_name, period_type, period_label)
        );

        CREATE TABLE IF NOT EXISTS article_embeddings (
            article_id INTEGER PRIMARY KEY,
            embedding BLOB NOT NULL,
            model_used TEXT NOT NULL,
            created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (article_id) REFERENCES articles(id)
        );

        CREATE INDEX IF NOT EXISTS idx_articles_url ON articles(url);
        CREATE INDEX IF NOT EXISTS idx_articles_source ON articles(source_id);
        CREATE INDEX IF NOT EXISTS idx_articles_published ON articles(published_date DESC);
        CREATE INDEX IF NOT EXISTS idx_summaries_article ON summaries(article_id);
    """)
    conn.commit()


def upsert_source(name, url, enabled=True):
    """Insert a source or update it if the URL already exists.

    Args:
        name: Display name of the feed source.
        url: Feed URL (used as the unique key).
        enabled: Whether the source is active.

    Returns:
        The integer ID of the upserted source row.
    """
    conn = get_connection()
    conn.execute(
        "INSERT INTO sources (name, url, enabled) VALUES (?, ?, ?) "
        "ON CONFLICT(url) DO UPDATE SET name=excluded.name, enabled=excluded.enabled",
        (name, url, int(enabled)),
    )
    conn.commit()
    row = conn.execute("SELECT id FROM sources WHERE url = ?", (url,)).fetchone()
    return row["id"]


def get_source_id(url):
    """Look up a source's ID by its URL.

    Args:
        url: The feed URL to search for.

    Returns:
        The integer source ID, or None if not found.
    """
    conn = get_connection()
    row = conn.execute("SELECT id FROM sources WHERE url = ?", (url,)).fetchone()
    return row["id"] if row else None


def get_source_last_fetched(source_id):
    """Get the ``last_fetched`` timestamp for a source.

    Args:
        source_id: The source's integer ID.

    Returns:
        The ISO-format timestamp string, or None if never fetched.
    """
    conn = get_connection()
    row = conn.execute("SELECT last_fetched FROM sources WHERE id = ?", (source_id,)).fetchone()
    return row["last_fetched"] if row and row["last_fetched"] else None


def article_exists(url):
    """Check whether an article with the given URL already exists.

    Args:
        url: The article URL to check.

    Returns:
        True if the article exists in the database.
    """
    conn = get_connection()
    row = conn.execute("SELECT 1 FROM articles WHERE url = ?", (url,)).fetchone()
    return row is not None


def insert_article(source_id, title, url, author=None, published_date=None, image_url=None):
    """Insert a new article into the database.

    Skips insertion if an article with the same URL already exists.

    Args:
        source_id: Foreign key to the source that produced this article.
        title: Article headline.
        url: Original article URL (unique constraint).
        author: Article author name.
        published_date: ISO-format publication date string.
        image_url: Thumbnail or hero image URL.

    Returns:
        The new article's integer ID, or None if it already exists or
        insertion fails due to an integrity error.
    """
    if article_exists(url):
        return None
    conn = get_connection()
    try:
        cur = conn.execute(
            "INSERT INTO articles (source_id, title, url, author, published_date, image_url) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (source_id, title, url, author, published_date, image_url),
        )
        conn.commit()
        return cur.lastrowid
    except sqlite3.IntegrityError:
        return None


def update_article_content(article_id, content_raw):
    """Store scraped article text.

    Args:
        article_id: The article's integer ID.
        content_raw: The scraped article text (may be empty string).
    """
    conn = get_connection()
    conn.execute("UPDATE articles SET content_raw = ? WHERE id = ?", (content_raw, article_id))
    conn.commit()


def update_source_fetched(source_id):
    """Update a source's ``last_fetched`` timestamp to now.

    Args:
        source_id: The source's integer ID.
    """
    conn = get_connection()
    conn.execute(
        "UPDATE sources SET last_fetched = CURRENT_TIMESTAMP WHERE id = ?", (source_id,)
    )
    conn.commit()


def get_articles(source_id=None, search=None, tag=None, page=1, limit=20):
    """Retrieve a paginated list of articles with optional filtering.

    Joins articles with their source and summary data. Supports
    filtering by source, full-text search across title/summary/tags,
    and exact tag matching.

    Args:
        source_id: Filter by this source ID.
        search: Substring to search in title, summary, or tags.
        tag: Exact tag string to filter by (JSON substring match).
        page: Page number (1-indexed).
        limit: Maximum articles per page.

    Returns:
        List of article dicts with source and summary fields.
    """
    conn = get_connection()
    query = """
        SELECT a.id, a.title, a.url, a.author, a.published_date, a.fetched_date,
               a.image_url, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags, sm.novelty_notes
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE 1=1
    """
    params = []

    if source_id:
        query += " AND a.source_id = ?"
        params.append(source_id)

    if search:
        query += " AND (a.title LIKE ? OR sm.summary_text LIKE ? OR sm.tags LIKE ?)"
        like = f"%{search}%"
        params.extend([like, like, like])

    if tag:
        query += " AND sm.tags LIKE ?"
        params.append(f'%"{tag}"%')

    query += " ORDER BY a.published_date DESC NULLS LAST, a.fetched_date DESC"
    query += " LIMIT ? OFFSET ?"
    params.extend([limit, (page - 1) * limit])

    rows = conn.execute(query, params).fetchall()
    return [dict(r) for r in rows]


def get_article(article_id):
    """Retrieve a single article with its source and summary data.

    Args:
        article_id: The article's integer ID.

    Returns:
        An article dict with source name and summary fields, or None
        if the article does not exist.
    """
    conn = get_connection()
    row = conn.execute(
        """
        SELECT a.*, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags, sm.novelty_notes, sm.model_used
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE a.id = ?
        """,
        (article_id,),
    ).fetchone()
    return dict(row) if row else None


def save_summary(article_id, summary_text, key_points, tags, novelty_notes, model_used):
    """Upsert a summary for an article.

    Inserts a new summary or updates an existing one if the article
    already has a summary (ON CONFLICT on ``article_id``).

    Args:
        article_id: The article's integer ID.
        summary_text: Markdown-formatted summary text.
        key_points: JSON string of attack flow steps or bullet points.
        tags: JSON array string of categorization tags.
        novelty_notes: What is novel about this threat, or None.
        model_used: The OpenAI model name, or ``"failed"``.
    """
    conn = get_connection()
    conn.execute(
        "INSERT INTO summaries (article_id, summary_text, key_points, tags, novelty_notes, model_used) "
        "VALUES (?, ?, ?, ?, ?, ?) "
        "ON CONFLICT(article_id) DO UPDATE SET "
        "summary_text=excluded.summary_text, key_points=excluded.key_points, "
        "tags=excluded.tags, novelty_notes=excluded.novelty_notes, "
        "model_used=excluded.model_used, created_date=CURRENT_TIMESTAMP",
        (article_id, summary_text, key_points, tags, novelty_notes, model_used),
    )
    conn.commit()


def get_unsummarized_articles(limit=10):
    """Fetch articles that have scraped content but no summary yet.

    Args:
        limit: Maximum number of articles to return.

    Returns:
        List of dicts with ``id``, ``title``, ``url``, and ``content_raw``.
    """
    conn = get_connection()
    rows = conn.execute(
        """
        SELECT a.id, a.title, a.url, a.content_raw
        FROM articles a
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.id IS NULL AND a.content_raw IS NOT NULL AND a.content_raw != ''
        ORDER BY a.fetched_date DESC
        LIMIT ?
        """,
        (limit,),
    ).fetchall()
    return [dict(r) for r in rows]


def get_unscraped_articles(limit=20):
    """Fetch articles that have not been scraped yet.

    Args:
        limit: Maximum number of articles to return.

    Returns:
        List of dicts with ``id`` and ``url``.
    """
    conn = get_connection()
    rows = conn.execute(
        """
        SELECT id, url FROM articles
        WHERE content_raw IS NULL
        ORDER BY fetched_date DESC
        LIMIT ?
        """,
        (limit,),
    ).fetchall()
    return [dict(r) for r in rows]


def get_sources():
    """Retrieve all feed sources ordered by name.

    Returns:
        List of source dicts with all columns.
    """
    conn = get_connection()
    rows = conn.execute("SELECT * FROM sources ORDER BY name").fetchall()
    return [dict(r) for r in rows]


def get_unsummarized_count():
    """Count articles with scraped content but no summary."""
    conn = get_connection()
    return conn.execute(
        """
        SELECT COUNT(*) as c FROM articles a
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.id IS NULL AND a.content_raw IS NOT NULL AND a.content_raw != ''
        """
    ).fetchone()["c"]


def get_scrape_failed_count():
    """Count articles where scraping was attempted but returned empty content."""
    conn = get_connection()
    return conn.execute(
        "SELECT COUNT(*) as c FROM articles WHERE content_raw = ''"
    ).fetchone()["c"]


def get_stats():
    """Compute aggregate statistics for the dashboard.

    Returns:
        A dict with keys ``total_articles``, ``total_sources``,
        ``total_summaries``, ``articles_last_24h``, ``unsummarized``,
        and ``scrape_failed``.
    """
    conn = get_connection()
    total_articles = conn.execute("SELECT COUNT(*) as c FROM articles").fetchone()["c"]
    total_sources = conn.execute("SELECT COUNT(*) as c FROM sources WHERE enabled=1").fetchone()["c"]
    total_summaries = conn.execute("SELECT COUNT(*) as c FROM summaries").fetchone()["c"]
    recent = conn.execute(
        "SELECT COUNT(*) as c FROM articles WHERE fetched_date >= datetime('now', '-24 hours')"
    ).fetchone()["c"]
    unsummarized = get_unsummarized_count()
    scrape_failed = get_scrape_failed_count()
    return {
        "total_articles": total_articles,
        "total_sources": total_sources,
        "total_summaries": total_summaries,
        "articles_last_24h": recent,
        "unsummarized": unsummarized,
        "scrape_failed": scrape_failed,
    }


def save_embedding(article_id, embedding_bytes, model_used):
    """Upsert an embedding BLOB for an article.

    Args:
        article_id: The article's integer ID.
        embedding_bytes: Raw bytes of the float32 numpy embedding vector.
        model_used: The embedding model name (e.g. ``"text-embedding-3-small"``).
    """
    conn = get_connection()
    conn.execute(
        "INSERT INTO article_embeddings (article_id, embedding, model_used) "
        "VALUES (?, ?, ?) "
        "ON CONFLICT(article_id) DO UPDATE SET "
        "embedding=excluded.embedding, model_used=excluded.model_used, "
        "created_date=CURRENT_TIMESTAMP",
        (article_id, embedding_bytes, model_used),
    )
    conn.commit()


def get_all_embeddings(model_used=None):
    """Fetch all stored embeddings from the database.

    Args:
        model_used: If provided, filter to embeddings generated by
            this model only.

    Returns:
        List of dicts with ``article_id`` and ``embedding`` (BLOB).
    """
    conn = get_connection()
    if model_used:
        rows = conn.execute(
            "SELECT article_id, embedding FROM article_embeddings WHERE model_used = ?",
            (model_used,),
        ).fetchall()
    else:
        rows = conn.execute(
            "SELECT article_id, embedding FROM article_embeddings"
        ).fetchall()
    return [dict(r) for r in rows]


def get_unembedded_articles(limit=50):
    """Fetch articles that have summaries but no embedding yet.

    Excludes articles whose summary has ``model_used='failed'``.

    Args:
        limit: Maximum number of articles to return.

    Returns:
        List of dicts with ``id``, ``title``, and ``summary_text``.
    """
    conn = get_connection()
    rows = conn.execute(
        """
        SELECT a.id, a.title, sm.summary_text
        FROM articles a
        JOIN summaries sm ON sm.article_id = a.id
        LEFT JOIN article_embeddings ae ON ae.article_id = a.id
        WHERE ae.article_id IS NULL
          AND sm.summary_text IS NOT NULL AND sm.summary_text != ''
          AND sm.model_used != 'failed'
        ORDER BY a.fetched_date DESC
        LIMIT ?
        """,
        (limit,),
    ).fetchall()
    return [dict(r) for r in rows]


def get_embedding_stats():
    """Get counts of embedded vs summarized articles.

    Returns:
        A dict with ``total_summarized`` and ``total_embedded`` counts.
    """
    conn = get_connection()
    total_summarized = conn.execute(
        "SELECT COUNT(*) as c FROM summaries WHERE model_used != 'failed' "
        "AND summary_text IS NOT NULL AND summary_text != ''"
    ).fetchone()["c"]
    total_embedded = conn.execute(
        "SELECT COUNT(*) as c FROM article_embeddings"
    ).fetchone()["c"]
    return {
        "total_summarized": total_summarized,
        "total_embedded": total_embedded,
    }


def get_articles_by_ids(article_ids):
    """Fetch full article and summary data for a list of IDs.

    Results are returned in the same order as the input ``article_ids``.

    Args:
        article_ids: List of article integer IDs.

    Returns:
        List of article dicts with source and summary fields,
        preserving the order of ``article_ids``.
    """
    if not article_ids:
        return []
    conn = get_connection()
    ph = ",".join("?" * len(article_ids))
    rows = conn.execute(
        f"""
        SELECT a.id, a.title, a.url, a.author, a.published_date, a.fetched_date,
               a.image_url, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags, sm.novelty_notes
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE a.id IN ({ph})
        """,
        list(article_ids),
    ).fetchall()
    # Preserve the ranked order from article_ids
    by_id = {r["id"]: dict(r) for r in rows}
    return [by_id[aid] for aid in article_ids if aid in by_id]


# Mapping from specific tags to broad categories.
# Tags not matching any pattern are ignored (the article still appears
# under whichever broad categories its other tags match).
_CATEGORY_RULES = [
    ("Malware", [
        "malware", "trojan", "backdoor", "infostealer", "info-stealer",
        "stealer", "loader", "dropper", "rootkit", "spyware", "adware",
        "keylogger", "rat", "worm", "cryptominer", "cryptojack", "miner",
        "ransomware", "lockbit", "blackcat", "alphv", "clop", "cl0p", "revil",
        "conti", "hive", "akira", "play", "medusa", "rhysida", "blackbasta",
        "black basta", "royal", "phobos", "babuk", "ragnar", "vice society",
        "bianlian", "8base", "noescape", "cactus", "hunters international",
        "emotet", "qakbot", "qbot", "trickbot", "icedid", "bumblebee",
        "pikabot", "darkgate", "asyncrat", "remcos", "redline", "raccoon",
        "vidar", "lumma", "stealc", "amadey", "smokeloader",
    ]),
    ("Vulnerabilities", [
        "vulnerability", "cve", "zero-day", "0-day", "0day", "exploit",
        "rce", "remote-code-execution", "buffer-overflow", "use-after-free",
        "deserialization", "proof-of-concept", "poc", "patch", "security-update",
        "security-flaw", "privilege-escalation", "code-execution",
    ]),
    ("Threat Actors", [
        "apt", "threat-actor", "nation-state", "cyber-espionage", "espionage",
        "campaign", "lazarus", "lazarus-group", "apt29", "apt28", "cozy-bear",
        "fancy-bear", "turla", "sandworm", "kimsuky", "mustang-panda",
        "charming-kitten", "hafnium", "nobelium", "volt-typhoon",
        "salt-typhoon", "scattered-spider", "lapsus",
    ]),
    ("Data Leaks", [
        "data-leak", "data-breach", "breach", "data-exposure",
        "data leak", "data breach", "data exposure", "exfiltration",
    ]),
    ("Phishing & Social Engineering", [
        "phishing", "spear-phishing", "social-engineering", "smishing",
        "vishing", "business-email-compromise", "bec", "credential-stuffing",
        "credential-theft",
    ]),
    ("Supply Chain", [
        "supply-chain", "supply chain", "dependency-confusion",
        "typosquatting", "malicious-package", "npm", "pypi",
    ]),
    ("Botnet & DDoS", [
        "botnet", "ddos", "dos", "mirai",
    ]),
    ("C2 & Offensive Tooling", [
        "c2", "command-and-control", "cobalt-strike", "metasploit",
        "sliver", "brute-ratel", "havoc", "mythic", "implant",
    ]),
    ("IoT & Hardware", [
        "iot", "firmware", "hardware", "embedded", "scada", "ics",
        "ot-security", "industrial",
    ]),
]

# Manual additions for entities commonly referenced in threat intelligence
# but absent from MITRE ATT&CK or listed under a different (versioned/full) name.
_EXTRA_THREAT_ACTORS = {
    "lapsus": "LAPSUS$",
    "lazarus": "Lazarus Group",
}

_EXTRA_SOFTWARE = {
    # Unversioned / short forms of MITRE entries
    "lockbit": "LockBit",
    "redline": "RedLine Stealer",
    "raccoon": "Raccoon Stealer",
    "lumma": "Lumma Stealer",
    "smokeloader": "SmokeLoader",
    # Alternate spellings
    "cl0p": "Clop",
    "blackbasta": "Black Basta",
    "ragnar": "Ragnar Locker",
    # Entities not (yet) catalogued in MITRE ATT&CK
    "hive": "Hive",
    "rhysida": "Rhysida",
    "phobos": "Phobos",
    "vice-society": "Vice Society",
    "bianlian": "BianLian",
    "8base": "8Base",
    "noescape": "NoEscape",
    "cactus": "Cactus",
    "hunters-international": "Hunters International",
    "stealc": "StealC",
    "vidar": "Vidar",
    "phorpiex": "Phorpiex",
    "globeimposter": "GlobeImposter",
    "medusalocker": "MedusaLocker",
    "trigona": "Trigona",
    "snatch": "Snatch",
    "mallox": "Mallox",
    "fog": "Fog",
    "interlock": "Interlock",
}

# Allowlist of known entities per subcategorizable category.
# Only tags that appear in these dicts become sub-categories.
# Everything else is grouped into "General".
# Core data from MITRE ATT&CK (enterprise-attack-18.1.json),
# supplemented with manual extras above.
_KNOWN_ENTITIES = {
    "Threat Actors": {**KNOWN_THREAT_ACTORS, **_EXTRA_THREAT_ACTORS},
    "Malware": {**KNOWN_SOFTWARE, **_EXTRA_SOFTWARE},
    "C2 & Offensive Tooling": {**KNOWN_SOFTWARE, **_EXTRA_SOFTWARE},
}


def _tag_to_category(tag):
    """Map a single tag to a broad category name.

    Uses safe matching for short keywords (<=3 chars): exact match,
    hyphen-component match, or prefix+digit match (e.g. ``"apt"``
    matches ``"apt29"``). Longer keywords use substring matching.
    Falls back to MITRE ATT&CK entity lookup for tags not covered
    by ``_CATEGORY_RULES``.

    Args:
        tag: A lowercase tag string.

    Returns:
        The category name string, or None if the tag is unmapped.
    """
    tag_lower = tag.strip().lower()
    parts = tag_lower.split("-")
    for category_name, keywords in _CATEGORY_RULES:
        for kw in keywords:
            if len(kw) <= 3:
                # Short keywords: exact, component, or prefix+digit
                if tag_lower == kw or kw in parts:
                    return category_name
                if tag_lower.startswith(kw) and tag_lower[len(kw):].isdigit():
                    return category_name
            else:
                if kw in tag_lower or tag_lower in kw:
                    return category_name
    # Fallback: check MITRE ATT&CK known entities (merged with extras)
    if tag_lower in _KNOWN_ENTITIES.get("Threat Actors", {}):
        return "Threat Actors"
    if tag_lower in _KNOWN_ENTITIES.get("Malware", {}):
        return "Malware"
    return None


# Regex to strip version suffixes from tags/display names.
# Matches: "-3.0", "-v2", "-_v2", " 2.0", etc. at end of string.
_VERSION_SUFFIX_RE = re.compile(r'[-_.\s]*(v?\d+(\.\d+)?|_v\d+)\s*$', re.IGNORECASE)


def _canonical_entity_tag(tag_lower, category_name):
    """Consolidate versioned entity variants under their base family name.

    Examples::

        'lockbit-3.0' -> 'lockbit'   (known entity base)
        'apt29'       -> 'apt29'     (no version suffix)
        'emotet'      -> 'emotet'    (unchanged)

    Only strips the version suffix if the resulting base name is itself
    a known entity, preventing over-stripping (e.g. ``'apt29'`` does
    NOT become ``'apt'``).

    Args:
        tag_lower: Lowercase tag string, possibly with version suffix.
        category_name: The broad category to look up known entities in.

    Returns:
        The canonical base tag string.
    """
    entities = _KNOWN_ENTITIES.get(category_name, {})

    # 1. Try stripping version directly from the tag
    m = _VERSION_SUFFIX_RE.search(tag_lower)
    if m:
        base = tag_lower[:m.start()].rstrip("-_. ")
        if base and base != tag_lower and base in entities:
            return base

    # 2. Try stripping version from the entity's display name
    #    Handles aliases like "lockbit-black" → display "LockBit 3.0" → "LockBit"
    if tag_lower in entities:
        display = entities[tag_lower]
        m2 = _VERSION_SUFFIX_RE.search(display)
        if m2:
            base_display = display[:m2.start()].strip()
            if base_display and base_display != display:
                base_tag = base_display.lower().replace(" ", "-")
                if base_tag in entities:
                    return base_tag

    return tag_lower


def _is_generic_tag(tag, category_name):
    """Check whether a tag is a generic keyword rather than a named entity.

    Uses an allowlist approach: only tags matching a known MITRE ATT&CK
    threat actor, malware family, or tool name are considered entities.
    Versioned variants (e.g. ``'lockbit-3.0'``) are recognized via
    canonicalization. Everything else is generic and falls into the
    ``"General"`` bucket.

    Args:
        tag: The tag string to classify.
        category_name: The broad category to check entities against.

    Returns:
        True if the tag is generic (not a known entity).
    """
    entities = _KNOWN_ENTITIES.get(category_name)
    if entities is None:
        return True  # not a subcategorizable category
    tag_lower = tag.strip().lower()
    # Direct lookup
    if tag_lower in entities:
        return False
    # Check if it's a versioned variant of a known entity
    canonical = _canonical_entity_tag(tag_lower, category_name)
    return canonical == tag_lower  # True (generic) if canonicalization didn't change it


def _format_entity_name(tag):
    """Return a human-friendly display name for a tag.

    Looks up the tag in the merged MITRE ATT&CK entity dicts (threat
    actors first, then software/tools). Falls back to title-cased
    formatting with hyphens replaced by spaces.

    Args:
        tag: The lowercase tag string to format.

    Returns:
        A display-friendly name string.
    """
    key = tag.strip().lower()
    for entities in _KNOWN_ENTITIES.values():
        if key in entities:
            return entities[key]
    return tag.replace("-", " ").title()


def get_subcategories(category_name, limit_per_sub=50, since_days=None):
    """Return sub-categories for a broad category based on named entities.

    Only works for categories listed in ``_KNOWN_ENTITIES`` (currently
    ``"Threat Actors"``, ``"Malware"``, ``"C2 & Offensive Tooling"``).
    Groups articles by their entity tags and returns named-entity
    sub-categories sorted by article count descending, plus a
    ``"General"`` bucket for unmatched articles.

    Args:
        category_name: Broad category name (e.g. ``"Malware"``).
        limit_per_sub: Maximum articles to include per sub-category.
        since_days: If provided, only include articles published within
            this many days.

    Returns:
        List of sub-category dicts with keys ``tag``, ``display_name``,
        ``count``, and ``articles``. Returns an empty list if the
        category is not subcategorizable.
    """
    if category_name not in _KNOWN_ENTITIES:
        return []

    articles = get_articles_for_category(category_name, since_days=since_days)

    # tag -> {article_id: article_dict}
    sub_map = {}
    sub_order = []
    matched_ids = set()

    for article in articles:
        try:
            tags = _json.loads(article.get("tags") or "[]")
        except (_json.JSONDecodeError, TypeError):
            tags = []

        for tag in tags:
            cat = _tag_to_category(tag)
            if cat == category_name and not _is_generic_tag(tag, category_name):
                # Canonicalize to base family name for grouping
                tag_lower = tag.strip().lower()
                canonical = _canonical_entity_tag(tag_lower, category_name)
                if canonical not in sub_map:
                    sub_map[canonical] = {}
                    sub_order.append(canonical)
                if article["id"] not in sub_map[canonical]:
                    sub_map[canonical][article["id"]] = article
                    matched_ids.add(article["id"])

    result = []
    for tag_key in sorted(sub_order, key=lambda t: -len(sub_map[t])):
        arts = list(sub_map[tag_key].values())
        result.append({
            "tag": tag_key,
            "display_name": _format_entity_name(tag_key),
            "count": len(arts),
            "articles": arts[:limit_per_sub],
        })

    # "General" bucket: articles not matched to any known entity
    general_articles = [a for a in articles if a["id"] not in matched_ids]
    if general_articles:
        result.append({
            "tag": "__general__",
            "display_name": "General",
            "count": len(general_articles),
            "articles": general_articles[:limit_per_sub],
        })

    return result


def get_categorized_articles(limit_per_category=10, since_days=None):
    """Return articles grouped into broad threat categories.

    Tags are consolidated into a small set of meaningful categories
    via ``_tag_to_category``. Articles with tags spanning multiple
    categories appear in each relevant category.

    Args:
        limit_per_category: Maximum articles to include per category.
        since_days: If provided, only include articles published within
            this many days.

    Returns:
        List of category dicts sorted by count descending, each with
        keys ``name``, ``count``, and ``articles``.
    """
    conn = get_connection()
    params = []
    date_filter = ""
    if since_days:
        date_filter = f" AND date(a.published_date) >= date('now', ?)"
        params.append(f"-{since_days} days")
    rows = conn.execute(
        f"""
        SELECT a.id, a.title, a.url, a.author, a.published_date, a.fetched_date,
               a.image_url, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags, sm.novelty_notes
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.tags IS NOT NULL AND sm.tags != '[]'{date_filter}
        ORDER BY a.published_date DESC NULLS LAST, a.fetched_date DESC
        LIMIT 500
        """,
        params,
    ).fetchall()

    # Build category -> articles map (deduplicate by article id)
    cat_map = {}      # category_name -> {article_id: article_dict}
    cat_order = []     # preserve insertion order for stable sorting

    for row in rows:
        article = dict(row)
        try:
            tags = _json.loads(article.get("tags") or "[]")
        except (_json.JSONDecodeError, TypeError):
            tags = []

        assigned = set()
        for tag in tags:
            cat = _tag_to_category(tag)
            if cat and cat not in assigned:
                assigned.add(cat)
                if cat not in cat_map:
                    cat_map[cat] = {}
                    cat_order.append(cat)
                if article["id"] not in cat_map[cat]:
                    cat_map[cat][article["id"]] = article

    # Build result sorted by article count descending; cap per category
    categories = []
    for cat_name in sorted(cat_order, key=lambda c: -len(cat_map[c])):
        articles = list(cat_map[cat_name].values())
        categories.append({
            "name": cat_name,
            "count": len(articles),
            "articles": articles[:limit_per_category],
        })

    return categories


def _compute_category_hash(articles):
    """Compute a content hash for cache invalidation.

    Produces a truncated SHA-256 hash of sorted ``(article_id,
    summary_length)`` tuples so that adding or modifying articles
    invalidates the cached insight.

    Args:
        articles: List of article dicts with ``id`` and ``summary_text``.

    Returns:
        A 16-character hex string.
    """
    tuples = sorted(
        (a["id"], len(a.get("summary_text") or "")) for a in articles
    )
    raw = str(tuples).encode()
    return hashlib.sha256(raw).hexdigest()[:16]


def get_articles_for_category(category_name, subcategory_tag=None, since_days=None):
    """Return all summarized articles belonging to a broad category.

    Scans all articles with tags and filters to those whose tags map
    to the given category via ``_tag_to_category``.

    Args:
        category_name: Broad category name (e.g. ``"Malware"``).
        subcategory_tag: If provided, further filters to articles
            whose tags contain a match for this specific entity.
        since_days: If provided, only include articles published within
            this many days.

    Returns:
        List of article dicts with summary data, ordered by
        publication date descending.
    """
    conn = get_connection()
    params = []
    date_filter = ""
    if since_days:
        date_filter = f" AND date(a.published_date) >= date('now', ?)"
        params.append(f"-{since_days} days")
    rows = conn.execute(
        f"""
        SELECT a.id, a.title, a.url, a.published_date,
               sm.summary_text, sm.tags
        FROM articles a
        JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.tags IS NOT NULL AND sm.tags != '[]'{date_filter}
        ORDER BY a.published_date DESC NULLS LAST, a.fetched_date DESC
        LIMIT 500
        """,
        params,
    ).fetchall()

    articles = []
    seen_ids = set()
    sub_lower = subcategory_tag.strip().lower() if subcategory_tag else None

    for row in rows:
        article = dict(row)
        try:
            tags = _json.loads(article.get("tags") or "[]")
        except (_json.JSONDecodeError, TypeError):
            tags = []

        for tag in tags:
            cat = _tag_to_category(tag)
            if cat != category_name:
                continue
            if sub_lower is not None:
                tag_lower = tag.strip().lower()
                if not (sub_lower in tag_lower or tag_lower in sub_lower):
                    continue
            if article["id"] not in seen_ids:
                seen_ids.add(article["id"])
                articles.append(article)
                break

    return articles


def get_category_insight(category_name):
    """Fetch a cached trend/forecast insight for a category.

    Args:
        category_name: The cache key (e.g. ``"Malware"`` or
            ``"Malware::lockbit"`` for subcategories).

    Returns:
        A dict with all ``category_insights`` columns, or None if
        no cached insight exists.
    """
    conn = get_connection()
    row = conn.execute(
        "SELECT * FROM category_insights WHERE category_name = ?",
        (category_name,),
    ).fetchone()
    return dict(row) if row else None


def save_category_insight(category_name, trend_text, forecast_text,
                          article_count, article_hash, model_used):
    """Upsert a category insight into the cache.

    Args:
        category_name: Cache key (e.g. ``"Malware"`` or
            ``"Malware::lockbit"``).
        trend_text: Markdown trend analysis text.
        forecast_text: Markdown forecast text.
        article_count: Number of articles used to generate the insight.
        article_hash: Content hash for cache invalidation.
        model_used: The OpenAI model that generated the insight.
    """
    conn = get_connection()
    conn.execute(
        "INSERT INTO category_insights "
        "(category_name, trend_text, forecast_text, article_count, article_hash, model_used, created_date) "
        "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) "
        "ON CONFLICT(category_name) DO UPDATE SET "
        "trend_text=excluded.trend_text, forecast_text=excluded.forecast_text, "
        "article_count=excluded.article_count, article_hash=excluded.article_hash, "
        "model_used=excluded.model_used, created_date=CURRENT_TIMESTAMP",
        (category_name, trend_text, forecast_text, article_count, article_hash, model_used),
    )
    conn.commit()


def delete_article(article_id):
    """Delete an article and all its related records.

    Removes the article's embeddings, summary, correlations, and
    finally the article row itself.

    Args:
        article_id: The article's integer ID.
    """
    conn = get_connection()
    conn.execute("DELETE FROM article_embeddings WHERE article_id = ?", (article_id,))
    conn.execute("DELETE FROM summaries WHERE article_id = ?", (article_id,))
    conn.execute(
        "DELETE FROM article_correlations WHERE article_id_1 = ? OR article_id_2 = ?",
        (article_id, article_id),
    )
    conn.execute("DELETE FROM articles WHERE id = ?", (article_id,))
    conn.commit()


def delete_file_url_articles():
    """Delete articles whose URLs point to downloadable files.

    Scans all articles and removes those with file-based URLs
    (PDF, DOC, ZIP, etc.) that cannot be meaningfully scraped.
    Also deletes their associated embeddings, summaries, and
    correlations.

    Returns:
        Number of articles deleted.
    """
    from urllib.parse import urlparse

    skip_ext = {
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".zip", ".rar", ".7z", ".gz", ".tar", ".tgz",
        ".exe", ".msi", ".dmg", ".apk", ".iso",
    }
    conn = get_connection()
    rows = conn.execute("SELECT id, url FROM articles").fetchall()

    ids = []
    for row in rows:
        try:
            path = urlparse(row["url"]).path.lower()
            if any(path.endswith(ext) for ext in skip_ext):
                ids.append(row["id"])
        except Exception:
            continue

    if not ids:
        return 0

    ph = ",".join("?" * len(ids))
    conn.execute(f"DELETE FROM article_embeddings WHERE article_id IN ({ph})", ids)
    conn.execute(f"DELETE FROM summaries WHERE article_id IN ({ph})", ids)
    conn.execute(
        f"DELETE FROM article_correlations WHERE article_id_1 IN ({ph}) OR article_id_2 IN ({ph})",
        ids + ids,
    )
    conn.execute(f"DELETE FROM articles WHERE id IN ({ph})", ids)
    conn.commit()
    return len(ids)


def clear_database():
    """Delete all data and reset source timestamps.

    Removes all articles, summaries, embeddings, correlations, and
    category insights. Resets every source's ``last_fetched`` to NULL.
    """
    conn = get_connection()
    conn.executescript("""
        DELETE FROM article_embeddings;
        DELETE FROM trend_analyses;
        DELETE FROM category_insights;
        DELETE FROM article_correlations;
        DELETE FROM summaries;
        DELETE FROM articles;
        UPDATE sources SET last_fetched = NULL;
    """)
    conn.commit()


def get_trend_analyses(category_name):
    """Return all trend analysis rows for a category, ordered by period.

    Args:
        category_name: Category name (e.g. ``"Malware"``).

    Returns:
        List of row dicts ordered by ``period_type, period_label``.
    """
    conn = get_connection()
    rows = conn.execute(
        "SELECT * FROM trend_analyses WHERE category_name = ? ORDER BY period_type, period_label",
        (category_name,),
    ).fetchall()
    return [dict(r) for r in rows]


def get_trend_analysis(category_name, period_type, period_label):
    """Return a single trend analysis row, or None if not cached.

    Args:
        category_name: Category name.
        period_type: ``"quarterly"`` or ``"yearly"``.
        period_label: Period string (e.g. ``"2024-Q1"`` or ``"2024"``).

    Returns:
        Row dict or None.
    """
    conn = get_connection()
    row = conn.execute(
        "SELECT * FROM trend_analyses WHERE category_name = ? AND period_type = ? AND period_label = ?",
        (category_name, period_type, period_label),
    ).fetchone()
    return dict(row) if row else None


def save_trend_analysis(category_name, period_type, period_label, trend_text, article_count, article_hash, model_used):
    """Upsert a trend analysis row.

    Args:
        category_name: Category name.
        period_type: ``"quarterly"`` or ``"yearly"``.
        period_label: Period string.
        trend_text: Markdown trend text.
        article_count: Number of articles used.
        article_hash: Content hash for cache invalidation.
        model_used: Model name string.
    """
    conn = get_connection()
    conn.execute(
        """INSERT INTO trend_analyses
               (category_name, period_type, period_label, trend_text, article_count, article_hash, model_used, created_date)
           VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
           ON CONFLICT(category_name, period_type, period_label) DO UPDATE SET
               trend_text = excluded.trend_text,
               article_count = excluded.article_count,
               article_hash = excluded.article_hash,
               model_used = excluded.model_used,
               created_date = CURRENT_TIMESTAMP""",
        (category_name, period_type, period_label, trend_text, article_count, article_hash, model_used),
    )
    conn.commit()
