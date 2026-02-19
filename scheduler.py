import logging
import threading

from apscheduler.schedulers.background import BackgroundScheduler

from config import load_config
from cost_tracker import CostTracker, cost_tracker

logger = logging.getLogger(__name__)

_scheduler = None
_refresh_lock = threading.Lock()
_is_refreshing = False

# Cost confirmation gate
_cost_estimate = None       # dict: {article_count, estimated_cost, model} or None
_cost_decision = None       # "approved" | "declined" | None (waiting)
_cost_event = threading.Event()
_actual_cost = None          # dict: {article_count, actual_cost, model} or None
_pipeline_stage = None       # current stage string for status polling


def _run_pipeline(lookback_days=1, since_last_fetch=False):
    """Execute the full ingestion pipeline under an exclusive lock."""
    global _is_refreshing, _cost_estimate, _cost_decision, _actual_cost, _pipeline_stage

    if not _refresh_lock.acquire(blocking=False):
        logger.info("Refresh already in progress, skipping")
        return

    _is_refreshing = True
    _cost_estimate = None
    _cost_decision = None
    _actual_cost = None
    _pipeline_stage = "fetch"
    cost_tracker.reset()

    try:
        from feed_fetcher import fetch_all_feeds
        from malpedia_fetcher import fetch_malpedia
        from article_scraper import scrape_unscraped_articles
        from summarizer import summarize_pending
        from database import delete_file_url_articles, get_unsummarized_count

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
        _pipeline_stage = "scrape"
        total_scraped = 0
        while True:
            batch = scrape_unscraped_articles(limit=10)
            if batch == 0:
                break
            total_scraped += batch
        logger.info(f"Scraped {total_scraped} articles total")

        # Cost confirmation before summarization
        to_summarize = get_unsummarized_count()
        total_summarized = 0
        summarization_skipped = False

        if to_summarize > 0:
            from llm_client import has_api_key, get_model_name
            model = get_model_name()

            if has_api_key():
                estimated = CostTracker.estimate_summarization_cost(to_summarize, model)
                _cost_estimate = {
                    "article_count": to_summarize,
                    "estimated_cost": round(estimated, 4),
                    "model": model,
                }
                _pipeline_stage = "confirm"
                _cost_event.clear()
                _cost_decision = None

                logger.info(f"Awaiting cost confirmation: {to_summarize} articles, ~${estimated:.4f} ({model})")

                # Wait up to 5 minutes for user decision
                _cost_event.wait(timeout=300)

                if _cost_decision == "declined":
                    summarization_skipped = True
                    logger.info("Summarization declined by user")
                elif _cost_decision != "approved":
                    # Timeout — auto-approve
                    logger.info("Cost confirmation timed out, auto-approving")

                _cost_estimate = None

        if not summarization_skipped and to_summarize > 0:
            _pipeline_stage = "summarize"
            while True:
                batch = summarize_pending()
                if batch == 0:
                    break
                total_summarized += batch
            logger.info(f"Summarized {total_summarized} articles total")

            from llm_client import get_model_name
            model = get_model_name()
            actual = cost_tracker.get_session_cost(model)
            _actual_cost = {
                "article_count": total_summarized,
                "actual_cost": round(actual, 4),
                "model": model,
            }
            logger.info(f"Pipeline complete (actual cost: ${actual:.4f})")
        elif summarization_skipped:
            logger.info("Pipeline complete (summarization skipped)")
        else:
            logger.info("Pipeline complete")

        # Generate embeddings for summarized articles
        _pipeline_stage = "embed"
        from embeddings import embed_pending_articles
        total_embedded = 0
        while True:
            batch = embed_pending_articles(limit=50)
            if batch == 0:
                break
            total_embedded += batch
        logger.info(f"Generated embeddings for {total_embedded} articles total")

        _pipeline_stage = "done"
    except Exception as e:
        logger.error(f"Pipeline error: {e}")
        _pipeline_stage = "error"
    finally:
        _is_refreshing = False
        _refresh_lock.release()


def start_scheduler(app=None):
    """Start the APScheduler background job."""
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
    """Spawn a daemon thread to run the pipeline and return immediately."""
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
    return _is_refreshing


def get_pipeline_stage():
    return _pipeline_stage


def get_cost_estimate():
    """Return the pending cost estimate, or None if not waiting."""
    return _cost_estimate


def approve_cost():
    """Approve the pending cost estimate."""
    global _cost_decision
    _cost_decision = "approved"
    _cost_event.set()


def decline_cost():
    """Decline the pending cost estimate."""
    global _cost_decision
    _cost_decision = "declined"
    _cost_event.set()


def get_actual_cost():
    """Return the actual cost after summarization, or None."""
    return _actual_cost


def dismiss_actual_cost():
    """Clear the actual cost so the dialog isn't shown again."""
    global _actual_cost
    _actual_cost = None
