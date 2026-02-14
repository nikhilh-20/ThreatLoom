import json
import logging
import os
import socket
import webbrowser
import threading

from flask import Flask, jsonify, render_template, request

from config import load_config, save_config
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
    init_db,
    save_category_insight,
    upsert_source,
)
from scheduler import is_refreshing, start_scheduler, trigger_manual_refresh

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

    Returns:
        JSON array of category objects with nested article arrays.
    """
    limit = request.args.get("limit", 10, type=int)
    categories = get_categorized_articles(limit_per_category=limit)
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
    """Check whether a pipeline refresh is currently running.

    Returns:
        JSON with ``is_refreshing`` boolean.
    """
    return jsonify({"is_refreshing": is_refreshing()})


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

        if "openai_api_key" in data:
            config["openai_api_key"] = data["openai_api_key"]
        if "malpedia_api_key" in data:
            config["malpedia_api_key"] = data["malpedia_api_key"]
        if "openai_model" in data:
            config["openai_model"] = data["openai_model"]
        if "fetch_interval_minutes" in data:
            config["fetch_interval_minutes"] = int(data["fetch_interval_minutes"])
        if "feeds" in data:
            config["feeds"] = data["feeds"]

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


@app.route("/api/subcategories")
def api_subcategories():
    """Return sub-categories for a broad threat category as JSON.

    Query params:
        category: The broad category name (required).
        limit: Max articles per sub-category (default 50).

    Returns:
        JSON array of sub-category objects.
    """
    category = request.args.get("category", "").strip()
    if not category:
        return jsonify([])
    limit = request.args.get("limit", 50, type=int)
    subs = get_subcategories(category, limit_per_sub=limit)
    return jsonify(subs)


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

    # Build cache key: "Category" or "Category::subtag"
    cache_key = f"{category}::{subcategory}" if subcategory else category

    # Get articles for this category/subcategory and check minimum count
    articles = get_articles_for_category(category, subcategory_tag=subcategory)
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
                "generated_at": cached["created_date"],
            })

    # Generate fresh insight
    result = generate_category_insight(category, subcategory_tag=subcategory)
    if result is None:
        return jsonify({"error": "generation_failed"}), 500

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
