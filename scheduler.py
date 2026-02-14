import logging
import threading

from apscheduler.schedulers.background import BackgroundScheduler

from config import load_config

logger = logging.getLogger(__name__)

_scheduler = None
_refresh_lock = threading.Lock()
_is_refreshing = False


def _run_pipeline(lookback_days=1, since_last_fetch=False):
    """Execute the full ingestion pipeline under an exclusive lock.

    Atomically acquires ``_refresh_lock``; if another run already holds it
    the call returns immediately.  Stages run in order: clean-up, feed
    fetch, Malpedia fetch, scrape, summarise, embed.

    Args:
        lookback_days: Number of days to look back for articles.
        since_last_fetch: If True, only fetch articles newer than each
            source's ``last_fetched`` timestamp.
    """
    global _is_refreshing

    if not _refresh_lock.acquire(blocking=False):
        logger.info("Refresh already in progress, skipping")
        return

    _is_refreshing = True
    try:
        from feed_fetcher import fetch_all_feeds
        from malpedia_fetcher import fetch_malpedia
        from article_scraper import scrape_unscraped_articles
        from summarizer import summarize_pending
        from database import delete_file_url_articles

        mode = "since last retrieval" if since_last_fetch else f"lookback={lookback_days}d"
        logger.info(f"Starting fetch pipeline ({mode})...")

        # Clean up any file-based articles (PDF, DOC, etc.) before processing
        deleted = delete_file_url_articles()
        if deleted:
            logger.info(f"Cleaned up {deleted} file-URL articles")

        new_articles = fetch_all_feeds(lookback_days=lookback_days, since_last_fetch=since_last_fetch)
        logger.info(f"Fetched {new_articles} new articles from RSS feeds")

        malpedia_new = fetch_malpedia(lookback_days=lookback_days, since_last_fetch=since_last_fetch)
        logger.info(f"Fetched {malpedia_new} new articles from Malpedia")
        new_articles += malpedia_new

        # Scrape all pending articles in batches
        total_scraped = 0
        while True:
            batch = scrape_unscraped_articles(limit=10)
            if batch == 0:
                break
            total_scraped += batch
        logger.info(f"Scraped {total_scraped} articles total")

        # Summarize all pending articles in batches
        total_summarized = 0
        while True:
            batch = summarize_pending()
            if batch == 0:
                break
            total_summarized += batch
        logger.info(f"Summarized {total_summarized} articles total")

        # Generate embeddings for summarized articles
        from embeddings import embed_pending_articles
        total_embedded = 0
        while True:
            batch = embed_pending_articles(limit=50)
            if batch == 0:
                break
            total_embedded += batch
        logger.info(f"Generated embeddings for {total_embedded} articles total")

        logger.info("Pipeline complete")
    except Exception as e:
        logger.error(f"Pipeline error: {e}")
    finally:
        _is_refreshing = False
        _refresh_lock.release()


def start_scheduler(app=None):
    """Start the APScheduler background job that runs the pipeline periodically.

    The interval is read from ``config.fetch_interval_minutes`` (default 30).
    Calling this more than once is a no-op.

    Args:
        app: Optional Flask application instance (unused, reserved for
            future middleware integration).
    """
    global _scheduler
    if _scheduler is not None:
        return

    config = load_config()
    interval = config.get("fetch_interval_minutes", 30)

    _scheduler = BackgroundScheduler(daemon=True)
    _scheduler.add_job(_run_pipeline, "interval", minutes=interval, id="fetch_pipeline")
    _scheduler.start()
    logger.info(f"Scheduler started: fetching every {interval} minutes")


def trigger_manual_refresh(lookback_days=1, since_last_fetch=False):
    """Spawn a daemon thread to run the pipeline and return immediately.

    Args:
        lookback_days: Number of days to look back for articles.
        since_last_fetch: If True, only fetch articles newer than each
            source's ``last_fetched`` timestamp.

    Returns:
        bool: True if a new refresh was started, False if one is already
        running.
    """
    if _is_refreshing:
        return False
    thread = threading.Thread(
        target=_run_pipeline,
        kwargs={"lookback_days": lookback_days, "since_last_fetch": since_last_fetch},
        daemon=True,
    )
    thread.start()
    return True


def is_refreshing():
    """Check whether a pipeline run is currently in progress.

    Returns:
        bool: True if the pipeline is running.
    """
    return _is_refreshing
