import logging
import signal
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout
from urllib.parse import urlparse

import requests
import trafilatura
from trafilatura.settings import use_config as trafilatura_config

from database import delete_article, get_unscraped_articles, update_article_content

logger = logging.getLogger(__name__)

REQUEST_TIMEOUT = 20
SCRAPE_PER_ARTICLE_TIMEOUT = 30

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
        True if the URL path ends with a known file extension.
    """
    try:
        path = urlparse(url).path.lower()
        return any(path.endswith(ext) for ext in _SKIP_EXTENSIONS)
    except Exception:
        return False

# Full browser-like headers to avoid 403s from WAFs (Cloudflare, Akamai, etc.)
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate",
    "Connection": "keep-alive",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
    "DNT": "1",
    "Cache-Control": "max-age=0",
}

# Persistent session for cookie handling (needed for Cloudflare challenges)
_session = requests.Session()
_session.headers.update(HEADERS)

# Configure trafilatura with generous timeouts
_traf_config = trafilatura_config()
_traf_config.set("DEFAULT", "DOWNLOAD_TIMEOUT", str(REQUEST_TIMEOUT))


def _fetch_and_extract(url):
    """Download and extract article text from a URL.

    Runs inside a thread with a hard timeout. Tries the requests session
    first (full browser headers + cookies), then falls back to
    trafilatura's built-in fetcher.

    Args:
        url: The article URL to fetch and extract text from.

    Returns:
        The extracted article text as a string, or None if fetching or
        extraction fails or the result is too short (< 100 chars).
    """
    html = None

    # Attempt 1: requests session with full browser headers
    try:
        resp = _session.get(url, timeout=REQUEST_TIMEOUT, allow_redirects=True)
        resp.raise_for_status()
        html = resp.text
    except Exception as e:
        logger.debug(f"Session fetch failed for {url}: {e}")

    # Attempt 2: trafilatura's native fetcher (different networking stack)
    if not html:
        try:
            html = trafilatura.fetch_url(url, config=_traf_config)
        except Exception as e:
            logger.debug(f"Trafilatura fetch failed for {url}: {e}")

    if not html:
        return None

    # Extract article text from HTML
    text = trafilatura.extract(html, include_comments=False, include_tables=False)
    if text and len(text) > 100:
        return text

    return None


def scrape_article(url):
    """Extract full article text from a URL with a hard timeout.

    Wraps ``_fetch_and_extract`` in a ``ThreadPoolExecutor`` to enforce
    a per-article timeout of ``SCRAPE_PER_ARTICLE_TIMEOUT`` seconds.

    Args:
        url: The article URL to scrape.

    Returns:
        The cleaned article text, or None if scraping times out or fails.
    """
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(_fetch_and_extract, url)
        try:
            return future.result(timeout=SCRAPE_PER_ARTICLE_TIMEOUT)
        except (FuturesTimeout, Exception) as e:
            logger.warning(f"Scrape timed out or failed for {url}: {e}")
            future.cancel()
            return None


def scrape_unscraped_articles(limit=20):
    """Batch-process articles that have no ``content_raw`` yet.

    Fetches up to ``limit`` unscraped articles from the database,
    scrapes each one, and stores the result. Articles pointing to
    file URLs are deleted instead of scraped. Articles that fail
    extraction are marked with an empty string to prevent retries.

    Args:
        limit: Maximum number of articles to process in this batch.

    Returns:
        Total number of articles processed (scraped + failed + deleted).
    """
    articles = get_unscraped_articles(limit=limit)
    scraped = 0
    total = len(articles)

    for i, article in enumerate(articles):
        url = article["url"]
        article_id = article["id"]

        if _is_file_url(url):
            logger.info(f"Skipping file URL, removing article {article_id}: {url}")
            delete_article(article_id)
            continue

        logger.info(f"Scraping [{i+1}/{total}]: {url}")
        content = scrape_article(url)

        if content:
            update_article_content(article_id, content)
            scraped += 1
            logger.info(f"  Scraped {len(content)} chars for article {article_id}")
        else:
            # Mark as empty string so we don't retry indefinitely
            update_article_content(article_id, "")
            logger.warning(f"  Could not extract content for article {article_id}")

    logger.info(f"Scraped {scraped}/{total} articles")
    return total
