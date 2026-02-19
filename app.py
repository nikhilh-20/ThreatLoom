import json
import logging
import os
import socket
import webbrowser
import threading

from flask import Flask, jsonify, render_template, request

from config import load_config, save_config
from cost_tracker import cost_tracker, _lookup_pricing
from database import (
    _compute_category_hash,
    clear_database,
    get_article,
    get_articles,
    get_articles_for_category,
    get_categorized_articles,
    get_category_insight,
    get_embedding_stats,
    get_sources,
    get_stats,
    get_subcategories,
    get_trend_analyses,
    init_db,
    save_category_insight,
    upsert_source,
)
from scheduler import (
    approve_cost,
    decline_cost,
    dismiss_actual_cost,
    get_actual_cost,
    get_cost_estimate,
    get_pipeline_stage,
    is_refreshing,
    start_scheduler,
    trigger_manual_refresh,
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

app = Flask(__name__)


# === Page Routes ===

@app.route("/")
def index():
    """Render the main dashboard page."""
    return render_template("index.html")


@app.route("/article/<int:article_id>")
def article_detail(article_id):
    """Render the article detail page.

    Parses JSON ``tags`` and ``key_points`` fields for the template.

    Args:
        article_id: The article's integer ID from the URL path.

    Returns:
        Rendered HTML, or a 404 response if the article is not found.
    """
    article = get_article(article_id)
    if not article:
        return "Article not found", 404

    # Parse JSON fields for template
    try:
        article["tags"] = json.loads(article.get("tags") or "[]")
    except (json.JSONDecodeError, TypeError):
        article["tags"] = []
    try:
        article["key_points"] = json.loads(article.get("key_points") or "[]")
    except (json.JSONDecodeError, TypeError):
        article["key_points"] = []

    return render_template("article.html", article=article)


@app.route("/settings")
def settings_page():
    """Render the settings configuration page."""
    config = load_config()
    sources = get_sources()
    source_map = {s["url"]: s for s in sources}
    return render_template("settings.html", config=config, source_map=source_map)


@app.route("/intelligence")
def intelligence_page():
    """Render the intelligence search and chat page."""
    return render_template("intelligence.html")


# === API Routes ===

@app.route("/api/articles")
def api_articles():
    """Return a paginated JSON list of articles.

    Query params:
        source_id: Filter by source ID.
        search: Full-text search substring.
        tag: Filter by exact tag.
        page: Page number (default 1).
        limit: Results per page (default 20, max 100).

    Returns:
        JSON array of article objects.
    """
    source_id = request.args.get("source_id", type=int)
    search = request.args.get("search", "").strip() or None
    tag = request.args.get("tag", "").strip() or None
    page = request.args.get("page", 1, type=int)
    limit = request.args.get("limit", 20, type=int)
    limit = min(limit, 100)

    articles = get_articles(source_id=source_id, search=search, tag=tag, page=page, limit=limit)
    return jsonify(articles)


@app.route("/api/articles/<int:article_id>")
def api_article(article_id):
    """Return a single article as JSON.

    Args:
        article_id: The article's integer ID from the URL path.

    Returns:
        JSON object with article data, or 404 error.
    """
    article = get_article(article_id)
    if not article:
        return jsonify({"error": "Not found"}), 404
    return jsonify(article)


@app.route("/api/articles/categorized")
def api_articles_categorized():
    """Return articles grouped by threat category as JSON.

    Query params:
        limit: Max articles per category (default 10).
        days: Only include articles from the last N days (0 = all).

    Returns:
        JSON array of category objects with nested article arrays.
    """
    limit = request.args.get("limit", 10, type=int)
    days = request.args.get("days", 0, type=int)
    since_days = days if days > 0 else None
    categories = get_categorized_articles(limit_per_category=limit, since_days=since_days)
    return jsonify(categories)


@app.route("/api/sources")
def api_sources():
    """Return all feed sources as JSON."""
    sources = get_sources()
    return jsonify(sources)


@app.route("/api/stats")
def api_stats():
    """Return aggregate dashboard statistics as JSON."""
    stats = get_stats()
    return jsonify(stats)


@app.route("/api/refresh", methods=["POST"])
def api_refresh():
    """Trigger a manual pipeline refresh.

    Request body (JSON):
        since_last_fetch: If true, use incremental fetch mode.
        days: Lookback period in days (default 1, max 365).

    Returns:
        JSON with ``status`` (``"started"`` or ``"already_running"``).
    """
    data = request.get_json(silent=True) or {}
    since_last_fetch = bool(data.get("since_last_fetch", False))
    days = data.get("days", 1)
    try:
        days = max(1, min(int(days), 365))
    except (ValueError, TypeError):
        days = 1
    started = trigger_manual_refresh(lookback_days=days, since_last_fetch=since_last_fetch)
    if started:
        return jsonify({"status": "started", "days": days, "since_last_fetch": since_last_fetch})
    return jsonify({"status": "already_running"})


@app.route("/api/clear-db", methods=["POST"])
def api_clear_db():
    """Clear all data from the database.

    Returns:
        JSON with ``status`` (``"ok"`` or ``"error"``). Returns 409
        if a refresh is currently running.
    """
    if is_refreshing():
        return jsonify({"status": "error", "error": "Cannot clear while refresh is running"}), 409
    try:
        clear_database()
        return jsonify({"status": "ok"})
    except Exception as e:
        return jsonify({"status": "error", "error": str(e)}), 500


@app.route("/api/refresh-status")
def api_refresh_status():
    """Check pipeline refresh status including cost confirmation state.

    Returns:
        JSON with ``is_refreshing``, ``stage``, ``cost_estimate``, ``actual_cost``.
    """
    return jsonify({
        "is_refreshing": is_refreshing(),
        "stage": get_pipeline_stage(),
        "cost_estimate": get_cost_estimate(),
        "actual_cost": get_actual_cost(),
    })


@app.route("/api/cost/approve", methods=["POST"])
def api_cost_approve():
    """Approve the pending cost estimate to proceed with summarization."""
    approve_cost()
    return jsonify({"status": "ok"})


@app.route("/api/cost/decline", methods=["POST"])
def api_cost_decline():
    """Decline the pending cost estimate to skip summarization."""
    decline_cost()
    return jsonify({"status": "ok"})


@app.route("/api/cost/dismiss", methods=["POST"])
def api_cost_dismiss():
    """Dismiss the actual cost dialog after summarization."""
    dismiss_actual_cost()
    return jsonify({"status": "ok"})


@app.route("/api/settings", methods=["POST"])
def api_settings():
    """Update application settings.

    Accepts a JSON body with any subset of config keys: ``openai_api_key``,
    ``malpedia_api_key``, ``openai_model``, ``fetch_interval_minutes``,
    ``feeds``. Syncs feed sources to the database after saving.

    Returns:
        JSON with ``status`` (``"ok"`` or ``"error"``).
    """
    try:
        data = request.get_json()
        config = load_config()

        if "llm_provider" in data:
            config["llm_provider"] = data["llm_provider"]
        if "openai_api_key" in data:
            config["openai_api_key"] = data["openai_api_key"]
        if "openai_model" in data:
            config["openai_model"] = data["openai_model"]
        if "anthropic_api_key" in data:
            config["anthropic_api_key"] = data["anthropic_api_key"]
        if "anthropic_model" in data:
            config["anthropic_model"] = data["anthropic_model"]
        if "malpedia_api_key" in data:
            config["malpedia_api_key"] = data["malpedia_api_key"]
        if "fetch_interval_minutes" in data:
            config["fetch_interval_minutes"] = int(data["fetch_interval_minutes"])
        if "feeds" in data:
            safe_feeds = [
                f for f in data["feeds"]
                if isinstance(f.get("url"), str)
                and (f["url"].startswith("http://") or f["url"].startswith("https://"))
            ]
            config["feeds"] = safe_feeds

        # Email notification settings
        for key in ("smtp_host", "smtp_username", "smtp_password", "notification_email"):
            if key in data:
                config[key] = data[key]
        if "smtp_port" in data:
            config["smtp_port"] = int(data["smtp_port"])
        if "smtp_use_tls" in data:
            config["smtp_use_tls"] = bool(data["smtp_use_tls"])
        if "email_notifications_enabled" in data:
            config["email_notifications_enabled"] = bool(data["email_notifications_enabled"])

        save_config(config)

        # Sync sources to database
        for feed in config.get("feeds", []):
            upsert_source(feed["name"], feed["url"], feed.get("enabled", True))

        return jsonify({"status": "ok"})
    except Exception as e:
        return jsonify({"status": "error", "error": str(e)}), 400


@app.route("/api/test-key", methods=["POST"])
def api_test_key():
    """Validate an OpenAI API key by listing models.

    Request body (JSON):
        api_key: The OpenAI API key to test.

    Returns:
        JSON with ``valid`` boolean and optional ``error`` string.
    """
    data = request.get_json()
    api_key = data.get("api_key", "").strip()
    if not api_key:
        return jsonify({"valid": False, "error": "No API key provided"})

    try:
        from openai import OpenAI

        client = OpenAI(api_key=api_key)
        client.models.list()
        return jsonify({"valid": True})
    except Exception as e:
        return jsonify({"valid": False, "error": str(e)})


@app.route("/api/test-anthropic-key", methods=["POST"])
def api_test_anthropic_key():
    """Validate an Anthropic API key by sending a minimal message.

    Request body (JSON):
        api_key: The Anthropic API key to test.

    Returns:
        JSON with ``valid`` boolean and optional ``error`` string.
    """
    data = request.get_json()
    api_key = data.get("api_key", "").strip()
    if not api_key:
        return jsonify({"valid": False, "error": "No API key provided"})

    try:
        import anthropic

        client = anthropic.Anthropic(api_key=api_key)
        client.messages.create(
            model="claude-haiku-4-5-20251001",
            max_tokens=10,
            messages=[{"role": "user", "content": "Hi"}],
        )
        return jsonify({"valid": True})
    except Exception as e:
        return jsonify({"valid": False, "error": str(e)})


@app.route("/api/test-malpedia-key", methods=["POST"])
def api_test_malpedia_key():
    """Validate a Malpedia API key by hitting the check endpoint.

    Request body (JSON):
        api_key: The Malpedia API key to test.

    Returns:
        JSON with ``valid`` boolean and optional ``error`` string.
    """
    data = request.get_json()
    api_key = data.get("api_key", "").strip()
    if not api_key:
        return jsonify({"valid": False, "error": "No API key provided"})

    try:
        import requests as req

        resp = req.get(
            "https://malpedia.caad.fkie.fraunhofer.de/api/check/apikey",
            headers={"Authorization": f"APIToken {api_key}"},
            timeout=15,
        )
        if resp.status_code == 200:
            return jsonify({"valid": True})
        return jsonify({"valid": False, "error": f"HTTP {resp.status_code}"})
    except Exception as e:
        return jsonify({"valid": False, "error": str(e)})


@app.route("/api/test-email", methods=["POST"])
def api_test_email():
    """Send a test email notification.

    Returns:
        JSON with ``success`` boolean and optional ``error`` string.
    """
    from notifier import send_test_email

    data = request.get_json(silent=True) or {}
    smtp_cfg = None
    if data.get("smtp_host"):
        smtp_cfg = {
            "host": data["smtp_host"],
            "port": int(data.get("smtp_port", 587)),
            "username": data.get("smtp_username", ""),
            "password": data.get("smtp_password", ""),
            "use_tls": bool(data.get("smtp_use_tls", True)),
            "recipient": data.get("notification_email", ""),
        }

    success, error = send_test_email(smtp_cfg=smtp_cfg)
    return jsonify({"success": success, "error": error})


@app.route("/api/subcategories")
def api_subcategories():
    """Return sub-categories for a broad threat category as JSON.

    Query params:
        category: The broad category name (required).
        limit: Max articles per sub-category (default 50).
        days: Only include articles from the last N days (0 = all).

    Returns:
        JSON array of sub-category objects.
    """
    category = request.args.get("category", "").strip()
    if not category:
        return jsonify([])
    limit = request.args.get("limit", 50, type=int)
    days = request.args.get("days", 0, type=int)
    since_days = days if days > 0 else None
    subs = get_subcategories(category, limit_per_sub=limit, since_days=since_days)
    return jsonify(subs)


@app.route("/api/insight-estimate")
def api_insight_estimate():
    """Return a cost estimate before running a Trend Analysis or Forecast.

    Query params:
        category: The broad category name (required).
        subcategory: Optional entity tag for narrower focus.
        days: Only include articles from the last N days (0 = all).
        type: ``"trend"`` or ``"forecast"`` (default ``"forecast"``).

    Returns:
        JSON with ``article_count``, ``estimated_cost``, ``model``,
        and for trend: ``n_quarters``, ``n_years``.
    """
    from llm_client import get_model_name, has_api_key
    from summarizer import estimate_insight_cost, estimate_trend_cost

    category = request.args.get("category", "").strip()
    if not category:
        return jsonify({"error": "missing category parameter"}), 400

    subcategory = request.args.get("subcategory", "").strip() or None
    days = request.args.get("days", 0, type=int)
    since_days = days if days > 0 else None
    insight_type = request.args.get("type", "forecast")

    if not has_api_key():
        return jsonify({"error": "api_key_missing"})

    articles = get_articles_for_category(category, subcategory_tag=subcategory, since_days=since_days)
    if len(articles) < 3:
        return jsonify({"error": "insufficient_data", "article_count": len(articles)})

    model = get_model_name()

    if insight_type == "trend":
        estimated_cost, n_quarters, n_years = estimate_trend_cost(articles, model)
        return jsonify({
            "article_count": len(articles),
            "estimated_cost": estimated_cost,
            "model": model,
            "n_quarters": n_quarters,
            "n_years": n_years,
        })
    else:
        estimated_cost = estimate_insight_cost(len(articles), model)
        return jsonify({
            "article_count": len(articles),
            "estimated_cost": estimated_cost,
            "model": model,
        })


@app.route("/api/trend-analysis")
def api_trend_analysis():
    """Generate or return cached quarterly and yearly trend analyses for a category.

    Query params:
        category: The broad category name (required).
        subcategory: Optional entity tag for narrower focus.

    Returns:
        JSON with ``quarterly`` list, ``yearly`` list, ``model_used``,
        or an error object.
    """
    from summarizer import generate_trend_analysis

    category = request.args.get("category", "").strip()
    if not category:
        return jsonify({"error": "missing category parameter"}), 400

    subcategory = request.args.get("subcategory", "").strip() or None
    days = request.args.get("days", 0, type=int)
    since_days = days if days > 0 else None

    articles = get_articles_for_category(category, subcategory_tag=subcategory, since_days=since_days)
    if len(articles) < 3:
        return jsonify({"error": "insufficient_data", "article_count": len(articles)})

    pre_it, pre_ot = cost_tracker.get_tokens()
    result = generate_trend_analysis(category, subcategory_tag=subcategory, since_days=since_days)
    if result is None:
        return jsonify({"error": "generation_failed"}), 500

    post_it, post_ot = cost_tracker.get_tokens()
    inp_price, out_price = _lookup_pricing(result["model_used"])
    actual_cost = ((post_it - pre_it) * inp_price + (post_ot - pre_ot) * out_price) / 1_000_000
    result["actual_cost"] = actual_cost

    return jsonify(result)


@app.route("/api/category-insight")
def api_category_insight():
    """Return or generate a trend/forecast insight for a category.

    Checks the cache first (valid for 24 hours if article hash
    matches). Generates a fresh insight via LLM if the cache is stale
    or missing.

    Query params:
        category: The broad category name (required).
        subcategory: Optional entity tag for narrower focus.

    Returns:
        JSON with ``trend``, ``forecast``, ``article_count``,
        ``model_used``, ``cached`` boolean, and ``generated_at``.
    """
    from datetime import datetime, timedelta
    from summarizer import generate_category_insight

    category = request.args.get("category", "").strip()
    if not category:
        return jsonify({"error": "missing category parameter"}), 400

    subcategory = request.args.get("subcategory", "").strip() or None
    days = request.args.get("days", 0, type=int)
    since_days = days if days > 0 else None

    # Build cache key: include time-filter suffix so filtered results don't
    # overwrite the all-time cache entry.
    cache_key = f"{category}::{subcategory}" if subcategory else category
    if since_days:
        cache_key = f"{cache_key}::days{since_days}"

    # Get articles for this category/subcategory and check minimum count
    articles = get_articles_for_category(category, subcategory_tag=subcategory, since_days=since_days)
    if len(articles) < 3:
        return jsonify({"error": "insufficient_data", "article_count": len(articles)})

    current_hash = _compute_category_hash(articles)

    # Check cache
    cached = get_category_insight(cache_key)
    if cached:
        cache_age_ok = False
        if cached["created_date"]:
            try:
                created = datetime.fromisoformat(cached["created_date"])
                cache_age_ok = (datetime.utcnow() - created) < timedelta(hours=24)
            except (ValueError, TypeError):
                pass

        if cached["article_hash"] == current_hash and cache_age_ok:
            return jsonify({
                "trend": cached["trend_text"],
                "forecast": cached["forecast_text"],
                "article_count": cached["article_count"],
                "model_used": cached["model_used"],
                "cached": True,
                "actual_cost": 0.0,
                "generated_at": cached["created_date"],
            })

    # Generate fresh insight, snapshot tokens to compute actual cost
    pre_it, pre_ot = cost_tracker.get_tokens()
    result = generate_category_insight(category, subcategory_tag=subcategory, since_days=since_days)
    if result is None:
        return jsonify({"error": "generation_failed"}), 500

    post_it, post_ot = cost_tracker.get_tokens()
    inp_price, out_price = _lookup_pricing(result["model_used"])
    actual_cost = ((post_it - pre_it) * inp_price + (post_ot - pre_ot) * out_price) / 1_000_000

    # Save to cache
    save_category_insight(
        category_name=cache_key,
        trend_text=result["trend"],
        forecast_text=result["forecast"],
        article_count=result["article_count"],
        article_hash=current_hash,
        model_used=result["model_used"],
    )

    return jsonify({
        "trend": result["trend"],
        "forecast": result["forecast"],
        "article_count": result["article_count"],
        "model_used": result["model_used"],
        "cached": False,
        "actual_cost": actual_cost,
        "generated_at": datetime.utcnow().isoformat(),
    })


# === Intelligence API ===

@app.route("/api/intelligence/chat", methods=["POST"])
def api_intelligence_chat():
    """RAG-based chat endpoint for threat intelligence queries.

    Request body (JSON):
        messages: List of conversation message objects.

    Returns:
        JSON with ``response``, ``articles``, ``model_used``, ``error``.
    """
    from intelligence import chat as intelligence_chat
    data = request.get_json(silent=True) or {}
    messages = data.get("messages", [])
    if not messages:
        return jsonify({"error": "No messages provided"}), 400
    result = intelligence_chat(messages)
    return jsonify(result)


@app.route("/api/intelligence/search", methods=["POST"])
def api_intelligence_search():
    """Semantic search endpoint over article embeddings.

    Request body (JSON):
        query: The search query string.
        top_k: Number of results to return (default 15, max 50).

    Returns:
        JSON with ``articles`` array and ``error`` field.
    """
    from embeddings import semantic_search
    data = request.get_json(silent=True) or {}
    query = data.get("query", "").strip()
    if not query:
        return jsonify({"articles": [], "error": "No query provided"})
    top_k = min(data.get("top_k", 15), 50)
    articles = semantic_search(query, top_k=top_k)
    return jsonify({"articles": articles, "error": None})


@app.route("/api/intelligence/status")
def api_intelligence_status():
    """Return embedding generation progress statistics.

    Returns:
        JSON with ``total_summarized`` and ``total_embedded`` counts.
    """
    stats = get_embedding_stats()
    return jsonify(stats)


# === Startup ===

def find_free_port(start=5000):
    """Find an available TCP port starting from the given port number.

    Tries ``start``, ``start + 1``, and falls back to ``start + 2``.

    Args:
        start: The first port number to try.

    Returns:
        An available port number.
    """
    for port in (start, start + 1):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    return start + 2


def open_browser(port):
    """Open the application in the default web browser.

    Args:
        port: The port number the server is listening on.
    """
    webbrowser.open(f"http://127.0.0.1:{port}")


if __name__ == "__main__":
    # Initialize
    init_db()
    config = load_config()

    # Sync configured feeds into database
    for feed in config.get("feeds", []):
        upsert_source(feed["name"], feed["url"], feed.get("enabled", True))

    # Start background scheduler
    start_scheduler(app)

    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", 0)) or find_free_port()

    logging.info(f"Starting Threat Loom on http://{host}:{port}")

    # Only open browser for local development (not inside Docker)
    if host == "127.0.0.1":
        threading.Timer(1.5, open_browser, args=[port]).start()

    app.run(host=host, port=port, debug=False)
