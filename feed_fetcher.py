import logging
from datetime import datetime, timedelta
from time import mktime
from urllib.parse import urlparse

import feedparser
import requests

from config import load_config
from database import (
    article_exists,
    get_source_id,
    get_source_last_fetched,
    insert_article,
    update_source_fetched,
    upsert_source,
)
from summarizer import check_relevance

logger = logging.getLogger(__name__)

FEED_FETCH_TIMEOUT = 20  # seconds per feed download

# Feed-reader UA — RSS/Atom endpoints expect this; browser UAs trigger WAF challenges
FEED_HEADERS = {
    "User-Agent": "ThreatLoom/1.0 (+https://github.com/nikhilh-20/ThreatLoom; feed reader)",
    "Accept": "application/rss+xml, application/xml, text/xml, application/atom+xml, */*;q=0.8",
}

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


def _parse_date(entry):
    """Extract a UTC datetime from a feedparser entry.

    Checks ``published_parsed`` first, then ``updated_parsed``.

    Args:
        entry: A feedparser entry object.

    Returns:
        datetime or None: The parsed publication date in UTC, or None if
        no parseable date is found.
    """
    for attr in ("published_parsed", "updated_parsed"):
        parsed = getattr(entry, attr, None)
        if parsed:
            try:
                return datetime.utcfromtimestamp(mktime(parsed))
            except (ValueError, OverflowError):
                continue
    return None


def _get_cutoff(lookback_days=1):
    """Return a UTC cutoff datetime for filtering old articles.

    Args:
        lookback_days: Number of days to look back from now.

    Returns:
        datetime: UTC datetime representing the oldest acceptable
        publication date.
    """
    return datetime.utcnow() - timedelta(days=lookback_days)


def _extract_image(entry):
    """Extract a thumbnail or hero image URL from a feedparser entry.

    Tries ``media:thumbnail``, then ``media:content`` with image medium,
    then enclosures with an ``image/*`` MIME type.

    Args:
        entry: A feedparser entry object.

    Returns:
        str or None: The image URL, or None if no image is found.
    """
    # Try media:thumbnail
    media = entry.get("media_thumbnail")
    if media and len(media) > 0:
        return media[0].get("url")
    # Try media:content
    media_content = entry.get("media_content")
    if media_content:
        for m in media_content:
            if m.get("medium") == "image" or (m.get("url", "").endswith((".jpg", ".png", ".webp"))):
                return m.get("url")
    # Try enclosures
    enclosures = entry.get("enclosures", [])
    for enc in enclosures:
        if enc.get("type", "").startswith("image/"):
            return enc.get("href") or enc.get("url")
    return None


def fetch_all_feeds(lookback_days=1, since_last_fetch=False):
    """Download and process all enabled RSS/Atom feeds.

    For each enabled feed the function downloads the XML, parses entries,
    filters by date and relevance (via LLM batch classification), and
    inserts new articles into the database.

    Args:
        lookback_days: Number of days to look back for articles when
            ``since_last_fetch`` is False.
        since_last_fetch: If True, use each source's ``last_fetched``
            timestamp as the cutoff instead of ``lookback_days``.

    Returns:
        int: Total number of newly inserted articles across all feeds.
    """
    config = load_config()
    total_new = 0

    for feed_cfg in config.get("feeds", []):
        if not feed_cfg.get("enabled", True):
            continue

        name = feed_cfg["name"]
        url = feed_cfg["url"]

        try:
            source_id = upsert_source(name, url, enabled=True)
            logger.info(f"Fetching feed: {name}")

            # Try fetching with feed-reader UA first, then fall back to
            # feedparser's built-in fetcher (uses its own UA + etag/modified handling)
            parsed = None
            try:
                resp = requests.get(
                    url,
                    headers=FEED_HEADERS,
                    timeout=FEED_FETCH_TIMEOUT,
                )
                resp.raise_for_status()
                parsed = feedparser.parse(resp.content)
            except requests.RequestException as e:
                logger.debug(f"Feed download with requests failed for {name}: {e}")

            # If requests failed or returned unparseable content, let feedparser try
            if parsed is None or (parsed.bozo and not parsed.entries):
                if parsed and parsed.bozo:
                    logger.debug(f"Parse failed for {name}, retrying with feedparser fetcher")
                try:
                    parsed = feedparser.parse(url)
                except Exception as e:
                    logger.warning(f"Failed to fetch feed {name}: {e}")
                    continue

            if parsed.bozo and not parsed.entries:
                logger.warning(f"Failed to parse feed {name}: {parsed.bozo_exception}")
                continue

            if since_last_fetch:
                last_fetched = get_source_last_fetched(source_id)
                if last_fetched:
                    cutoff = datetime.fromisoformat(last_fetched)
                else:
                    cutoff = _get_cutoff(lookback_days)
            else:
                cutoff = _get_cutoff(lookback_days)
            new_count = 0
            skipped_old = 0
            skipped_irrelevant = 0

            # Collect candidates first, then batch-classify via LLM
            candidates = []

            for entry in parsed.entries:
                link = entry.get("link", "").strip()
                title = entry.get("title", "").strip()
                if not link or not title:
                    continue

                if _is_file_url(link):
                    continue

                pub_date = _parse_date(entry)

                # Skip articles older than the cutoff
                if pub_date and pub_date < cutoff:
                    skipped_old += 1
                    continue

                # Skip articles with no date if we've fetched this source before
                if pub_date is None and get_source_last_fetched(source_id) is not None:
                    skipped_old += 1
                    continue

                if article_exists(link):
                    continue

                candidates.append({
                    "title": title,
                    "link": link,
                    "pub_date": pub_date,
                    "author": entry.get("author"),
                    "image_url": _extract_image(entry),
                })

            # Batch-classify candidate titles using OpenAI LLM
            if candidates:
                titles = [c["title"] for c in candidates]
                relevance = check_relevance(titles)

                for candidate, is_relevant in zip(candidates, relevance):
                    if not is_relevant:
                        skipped_irrelevant += 1
                        continue

                    article_id = insert_article(
                        source_id=source_id,
                        title=candidate["title"],
                        url=candidate["link"],
                        author=candidate["author"],
                        published_date=candidate["pub_date"].isoformat() if candidate["pub_date"] else None,
                        image_url=candidate["image_url"],
                    )
                    if article_id:
                        new_count += 1

            update_source_fetched(source_id)
            logger.info(f"  {name}: {new_count} new, {skipped_old} old, {skipped_irrelevant} irrelevant")
            total_new += new_count

        except Exception as e:
            logger.error(f"Error fetching feed {name}: {e}")
            continue

    logger.info(f"Total new articles fetched: {total_new}")
    return total_new
