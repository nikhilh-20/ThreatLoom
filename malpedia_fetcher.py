import logging
import re
from datetime import datetime, timedelta
from urllib.parse import urlparse

import requests

from config import load_config
from database import (
    article_exists,
    insert_article,
    update_source_fetched,
    upsert_source,
)
from summarizer import check_relevance

logger = logging.getLogger(__name__)

MALPEDIA_BASE = "https://malpedia.caad.fkie.fraunhofer.de"
MALPEDIA_BIB_URL = f"{MALPEDIA_BASE}/api/get/bib"
MALPEDIA_SOURCE_URL = f"{MALPEDIA_BASE}/library"
MALPEDIA_TIMEOUT = 60  # BibTeX payload is ~4.5 MB

_SKIP_EXTENSIONS = frozenset({
    ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
    ".zip", ".rar", ".7z", ".gz", ".tar", ".tgz",
    ".exe", ".msi", ".dmg", ".apk", ".iso",
})


def _is_file_url(url):
    """Check whether a URL points to a downloadable file.

    Args:
        url: The URL string to inspect.

    Returns:
        bool: True if the URL path ends with a known file extension.
    """
    try:
        path = urlparse(url).path.lower()
        return any(path.endswith(ext) for ext in _SKIP_EXTENSIONS)
    except Exception:
        return False


# Regex patterns for BibTeX field extraction
_RE_ENTRY = re.compile(r"@\w+\{[^,]+,(.*?)\n\}", re.DOTALL)
_RE_TITLE = re.compile(r"title\s*=\s*\{\{(.+?)\}\}", re.DOTALL)
_RE_DATE = re.compile(r"date\s*=\s*\{(\d{4}-\d{2}-\d{2})\}")
_RE_URL = re.compile(r"url\s*=\s*\{(.+?)\}")
_RE_AUTHOR = re.compile(r"author\s*=\s*\{(.+?)\}")
_RE_ORG = re.compile(r"organization\s*=\s*\{(.+?)\}")


def _parse_bibtex(text):
    """Parse BibTeX text and yield article metadata dicts.

    Args:
        text: Raw BibTeX string from the Malpedia API.

    Yields:
        dict: Entries with keys ``title``, ``date``, ``url``, ``author``,
        and ``organization``.  Entries missing a URL or title are skipped.
    """
    for match in _RE_ENTRY.finditer(text):
        body = match.group(1)

        url_m = _RE_URL.search(body)
        if not url_m:
            continue
        url = url_m.group(1).strip()

        title_m = _RE_TITLE.search(body)
        title = title_m.group(1).strip() if title_m else None
        if not title:
            continue

        date_m = _RE_DATE.search(body)
        date_str = date_m.group(1) if date_m else None

        author_m = _RE_AUTHOR.search(body)
        author = author_m.group(1).strip() if author_m else None

        org_m = _RE_ORG.search(body)
        org = org_m.group(1).strip() if org_m else None

        yield {
            "title": title,
            "date": date_str,
            "url": url,
            "author": author,
            "organization": org,
        }


def _format_author(author, organization):
    """Build a display author string like ``Author Name (Organization)``.

    Args:
        author: Author name string or None.
        organization: Organization name string or None.

    Returns:
        str or None: Combined author string, or None if both are absent.
    """
    if author and organization:
        return f"{author} ({organization})"
    return author or organization or None


def fetch_malpedia(lookback_days=1, since_last_fetch=False):
    """Fetch articles from Malpedia's BibTeX bibliography.

    Downloads the full BibTeX library, parses entries, filters by date
    and LLM relevance, and inserts new articles into the database.

    Args:
        lookback_days: Number of days to look back when
            ``since_last_fetch`` is False.
        since_last_fetch: If True, use the Malpedia source's
            ``last_fetched`` timestamp as the cutoff.

    Returns:
        int: Number of newly inserted articles, or 0 if the API key is
        not configured or the request fails.
    """
    config = load_config()
    api_key = config.get("malpedia_api_key", "").strip()
    if not api_key:
        logger.info("Malpedia API key not configured, skipping")
        return 0

    source_id = upsert_source("Malpedia", MALPEDIA_SOURCE_URL)

    logger.info("Fetching Malpedia library...")
    try:
        resp = requests.get(
            MALPEDIA_BIB_URL,
            headers={"Authorization": f"APIToken {api_key}"},
            timeout=MALPEDIA_TIMEOUT,
        )
        resp.raise_for_status()
    except requests.RequestException as e:
        logger.error(f"Failed to fetch Malpedia BibTeX: {e}")
        return 0

    if since_last_fetch:
        from database import get_source_last_fetched

        last_fetched = get_source_last_fetched(source_id)
        if last_fetched:
            cutoff = datetime.fromisoformat(last_fetched)
        else:
            cutoff = datetime.utcnow() - timedelta(days=lookback_days)
    else:
        cutoff = datetime.utcnow() - timedelta(days=lookback_days)
    candidates = []

    for entry in _parse_bibtex(resp.text):
        # Filter by date
        if not entry["date"]:
            continue
        try:
            pub_date = datetime.strptime(entry["date"], "%Y-%m-%d")
        except ValueError:
            continue
        if pub_date < cutoff:
            continue

        if _is_file_url(entry["url"]):
            continue

        # Deduplicate against existing articles
        if article_exists(entry["url"]):
            continue

        candidates.append({
            "title": entry["title"],
            "url": entry["url"],
            "pub_date": pub_date,
            "author": _format_author(entry["author"], entry["organization"]),
        })

    if not candidates:
        logger.info("Malpedia: no new candidates after filtering")
        update_source_fetched(source_id)
        return 0

    # Batch-classify candidate titles for relevance
    titles = [c["title"] for c in candidates]
    relevance = check_relevance(titles)

    new_count = 0
    skipped_irrelevant = 0
    for candidate, is_relevant in zip(candidates, relevance):
        if not is_relevant:
            skipped_irrelevant += 1
            continue

        article_id = insert_article(
            source_id=source_id,
            title=candidate["title"],
            url=candidate["url"],
            author=candidate["author"],
            published_date=candidate["pub_date"].isoformat(),
        )
        if article_id:
            new_count += 1

    update_source_fetched(source_id)
    logger.info(
        f"Malpedia: {new_count} new, {skipped_irrelevant} irrelevant, "
        f"{len(candidates) - new_count - skipped_irrelevant} other skipped"
    )
    return new_count
