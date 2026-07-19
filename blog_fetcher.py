import logging
import re
from datetime import datetime

import requests

from config import load_config
from database import (
    article_exists,
    delete_article,
    get_articles_by_source,
    insert_article,
    update_source_fetched,
    upsert_source,
)

logger = logging.getLogger(__name__)

GITHUB_OWNER = "nikhilh-20"
GITHUB_REPO = "nikhilh-20.github.io"
BLOG_PATH = "blog"
BLOG_BASE_URL = "https://nikhilh-20.github.io/blog/"
GITHUB_COMMITS_API = f"https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}/commits"
BLOG_SOURCE_NAME = "Kaido's Blog"
BLOG_SOURCE_URL = BLOG_BASE_URL
MAX_DEPTH = 4

_HEADERS = {"User-Agent": "ThreatLoom/1.0 (+https://github.com/nikhilh-20/ThreatLoom)"}
_GH_HEADERS = {**_HEADERS, "Accept": "application/vnd.github+json"}
_REQUEST_TIMEOUT = 20

# Matches "* [Title](./relative/path)" — the literal "./" prefix is required so
# absolute/external links (e.g. the TOOLING section's GitHub repo link) are skipped.
_RE_LINK = re.compile(r"^\s*[-*]\s*\[(.+?)\]\(\./([^)]+)\)\s*$", re.MULTILINE)


def _parse_readme(text):
    """Extract (title, relative_path) pairs for every list link in a README.md.

    Args:
        text: Raw Markdown content of a blog README.md (root or nested).

    Yields:
        tuple: (title, relative_path) for each relative link found.
    """
    for match in _RE_LINK.finditer(text):
        title = match.group(1).strip()
        rel_path = match.group(2).strip().strip("/")
        if title and rel_path:
            yield title, rel_path


def _fetch_readme(gh_path):
    """Fetch raw README.md content at a given repo path.

    Args:
        gh_path: Repo-relative directory path, e.g. ``"blog"`` or
            ``"blog/monthly_malware_analysis/2026/june"``.

    Returns:
        str or None: The README's raw text, or None on any failure.
    """
    url = f"https://raw.githubusercontent.com/{GITHUB_OWNER}/{GITHUB_REPO}/main/{gh_path}/README.md"
    try:
        resp = requests.get(url, headers=_HEADERS, timeout=_REQUEST_TIMEOUT)
        resp.raise_for_status()
        return resp.text
    except requests.RequestException as e:
        logger.warning(f"Failed to fetch README at {gh_path}: {e}")
        return None


def _resolve_leaf_posts(gh_path, live_prefix, links, depth=0):
    """Recursively expand README links into leaf post tuples.

    A link is treated as an index/container page — and expanded instead of
    ingested — when its own README.md contains further relative list links
    (e.g. blog/README.md links to a "June" page whose README.md is itself a
    list of that month's individual analyses). A link whose README.md has no
    further list links is a genuine leaf post.

    Args:
        gh_path: Repo path of the README.md that produced ``links``.
        live_prefix: Live URL prefix corresponding to ``gh_path``.
        links: (title, relative_path) pairs parsed from that README.
        depth: Current recursion depth, guarded by MAX_DEPTH.

    Yields:
        tuple: (title, live_url, gh_path) for each resolved leaf post.
    """
    for title, rel_path in links:
        child_gh_path = f"{gh_path}/{rel_path}"
        child_live_url = f"{live_prefix}{rel_path}/"

        if depth >= MAX_DEPTH:
            yield title, child_live_url, child_gh_path
            continue

        child_readme = _fetch_readme(child_gh_path)
        if child_readme is None:
            continue  # Couldn't verify whether this is a container — skip rather than guess.

        child_links = list(_parse_readme(child_readme))
        if child_links:
            yield from _resolve_leaf_posts(child_gh_path, f"{live_prefix}{rel_path}/", child_links, depth + 1)
        else:
            yield title, child_live_url, child_gh_path


def _lookup_published_date(gh_path):
    """Resolve a post's publish date via one GitHub commit-history API call.

    Uses the most recent commit touching ``gh_path`` as an accessible proxy
    for "publish date" (finding the true earliest commit would require
    paginating to the last page, which isn't worth the extra API calls here).

    Args:
        gh_path: Full repo-relative path of the post, e.g.
            ``"blog/monthly_malware_analysis/2026/june/telegramstealer"``.

    Returns:
        str or None: ISO datetime string, or None on any failure (rate
        limit, network error, no commits found) — callers should treat a
        None date as "unknown" and fall back to fetch time.
    """
    try:
        resp = requests.get(
            GITHUB_COMMITS_API,
            params={"path": gh_path, "per_page": 1},
            headers=_GH_HEADERS,
            timeout=_REQUEST_TIMEOUT,
        )
        if resp.status_code == 403:
            logger.warning(f"GitHub API rate-limited during blog date lookup for {gh_path}")
            return None
        resp.raise_for_status()
        data = resp.json()
        if not data:
            return None
        date_str = data[0]["commit"]["committer"]["date"]  # e.g. "2023-05-18T02:43:08Z"
        return datetime.strptime(date_str, "%Y-%m-%dT%H:%M:%SZ").isoformat()
    except Exception as e:
        logger.warning(f"Failed to resolve publish date for {gh_path}: {e}")
        return None


def _prune_stale_containers(source_id, leaves):
    """Delete existing articles that are confirmed, right now, to be container pages.

    Earlier versions of this fetcher treated index/container pages (e.g. a
    monthly round-up) as leaf articles. Those pages are never produced by the
    current resolution logic, so any existing article not in this refresh's
    leaf set is re-checked directly — only deleted if its README.md still
    demonstrably contains further list links, so a transient fetch failure
    elsewhere can never cause a real article to be pruned.

    Args:
        source_id: The Kaido's Blog source's database ID.
        leaves: The (title, live_url, gh_path) tuples resolved this refresh.
    """
    leaf_urls = {url for _, url, _ in leaves}
    for article in get_articles_by_source(source_id):
        url = article["url"]
        if url in leaf_urls or not url.startswith(BLOG_BASE_URL):
            continue

        rel = url[len(BLOG_BASE_URL):].rstrip("/")
        gh_path = f"{BLOG_PATH}/{rel}" if rel else BLOG_PATH
        readme = _fetch_readme(gh_path)
        if readme and list(_parse_readme(readme)):
            logger.info(f"Kaido's Blog: pruning stale container article {url}")
            delete_article(article["id"])


def fetch_kaido_blog():
    """Fetch new posts from Kaido's personal blog table of contents.

    The blog has no RSS feed, so ``blog/README.md`` (a hand-maintained
    Markdown table of contents) is parsed instead, recursively expanding any
    linked page that is itself an index of further posts. Every discovered
    leaf post is unconditionally ingested — no relevance gate — since this is
    the site owner's own curated content. Published date is looked up via the
    GitHub commits API, but only for posts not already in the database, to
    stay well within the unauthenticated rate limit.

    Returns:
        int: Number of newly inserted articles, or 0 on fetch failure.
    """
    config = load_config()
    if not config.get("kaido_blog_enabled", True):
        logger.info("Kaido's Blog disabled, skipping")
        return 0

    source_id = upsert_source(BLOG_SOURCE_NAME, BLOG_SOURCE_URL)

    readme_text = _fetch_readme(BLOG_PATH)
    if readme_text is None:
        return 0

    top_links = list(_parse_readme(readme_text))
    leaves = list(_resolve_leaf_posts(BLOG_PATH, BLOG_BASE_URL, top_links))

    _prune_stale_containers(source_id, leaves)

    new_count = 0
    for title, url, gh_path in leaves:
        if article_exists(url):
            continue

        published_date = _lookup_published_date(gh_path)
        article_id = insert_article(
            source_id=source_id,
            title=title,
            url=url,
            author="Kaido",
            published_date=published_date,
        )
        if article_id:
            new_count += 1

    update_source_fetched(source_id)
    logger.info(f"Kaido's Blog: {new_count} new posts")
    return new_count
