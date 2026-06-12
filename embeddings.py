import logging
import struct
import time
from datetime import datetime, timedelta

import numpy as np
from openai import APIError, RateLimitError

from config import load_config
from database import (
    get_all_embeddings,
    get_article_ids_since_days,
    get_articles_by_ids,
    get_dedup_candidates,
    get_dedup_reference_embeddings,
    get_unembedded_articles,
    save_correlation,
    save_dedup_embedding,
    save_embedding,
    set_duplicate_of,
)

logger = logging.getLogger(__name__)

EMBEDDING_MODEL = "text-embedding-3-small"
EMBEDDING_DIMS = 1536

# Dedup tuning (mirrors the Android port's DeduplicateArticlesUseCase)
DEDUP_WINDOW_HOURS = 24
DEDUP_CONTENT_SNIPPET = 2000
DEDUP_EMBED_BATCH = 50


def _get_client():
    """Create an OpenAI client using the configured API key.

    Returns:
        An ``OpenAI`` client instance, or None if no API key is configured.
    """
    config = load_config()
    api_key = config.get("openai_api_key", "").strip()
    if not api_key:
        return None
    from openai import OpenAI
    return OpenAI(api_key=api_key)


def _floats_to_blob(floats):
    """Convert a list of floats to a raw bytes BLOB.

    Args:
        floats: List of float values (embedding vector).

    Returns:
        Bytes object containing the float32 numpy array.
    """
    return np.array(floats, dtype=np.float32).tobytes()


def _blob_to_array(blob):
    """Convert a raw bytes BLOB back to a numpy float32 array.

    Args:
        blob: Bytes object containing a float32 numpy array.

    Returns:
        A numpy ndarray of dtype float32.
    """
    return np.frombuffer(blob, dtype=np.float32)


def generate_embeddings_batch(texts):
    """Generate embeddings for a batch of texts via the OpenAI API.

    Retries up to 3 times on rate limits or API errors.

    Args:
        texts: List of text strings to embed.

    Returns:
        List of float lists (one embedding per input text), or None if
        the API key is missing or all retries fail.
    """
    client = _get_client()
    if client is None:
        logger.warning("OpenAI API key not configured, skipping embedding generation")
        return None

    for attempt in range(3):
        try:
            response = client.embeddings.create(
                model=EMBEDDING_MODEL,
                input=texts,
            )
            return [item.embedding for item in response.data]
        except RateLimitError:
            wait = 2 ** (attempt + 1)
            logger.warning(f"Rate limited, waiting {wait}s before retry")
            time.sleep(wait)
        except APIError as e:
            logger.error(f"Embedding API error (attempt {attempt + 1}): {e}")
            if attempt < 2:
                time.sleep(1)
        except Exception as e:
            logger.error(f"Unexpected error during embedding generation: {e}")
            return None

    return None


def embed_pending_articles(limit=50, article_ids=None):
    """Fetch unembedded articles, generate embeddings, and store as BLOBs.

    Retrieves articles that have summaries but no embedding yet,
    generates embeddings for their title + summary text, and saves
    the resulting vectors to the database.

    Args:
        limit: Maximum number of articles to process in this batch.
        article_ids: Optional list of article IDs to restrict processing to.

    Returns:
        Total number of articles processed (0 means nothing left).
    """
    articles = get_unembedded_articles(limit=limit, article_ids=article_ids)
    if not articles:
        return 0

    # Build texts: title + summary
    texts = []
    for art in articles:
        title = art["title"] or ""
        summary = art["summary_text"] or ""
        texts.append(f"{title}\n{summary}")

    embeddings = generate_embeddings_batch(texts)
    if embeddings is None:
        return 0

    stored = 0
    for art, emb in zip(articles, embeddings):
        try:
            blob = _floats_to_blob(emb)
            save_embedding(art["id"], blob, EMBEDDING_MODEL)
            stored += 1
        except Exception as e:
            logger.error(f"Failed to save embedding for article {art['id']}: {e}")

    logger.info(f"Generated embeddings for {stored}/{len(articles)} articles")
    return len(articles)


def _parse_ts(value):
    """Parse an ISO-ish timestamp string to a datetime, or None.

    Handles both feedparser ISO strings (``2026-06-12T18:09:48``) and SQLite
    ``CURRENT_TIMESTAMP`` strings (``2026-06-12 18:09:48``).
    """
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None


def _within_window(a, b):
    """Whether two timestamp strings are within the dedup window of each other."""
    da = _parse_ts(a)
    db = _parse_ts(b)
    if da is None or db is None:
        return False
    return abs((da - db).total_seconds()) <= DEDUP_WINDOW_HOURS * 3600


def deduplicate_pending_articles():
    """Mark near-duplicate scraped articles before the summarization step.

    Embeds each scraped-but-unsummarized article (title + content snippet),
    then marks an article as a duplicate when its embedding is at least the
    configured threshold similar to another article published within
    ``DEDUP_WINDOW_HOURS`` — comparing first against already-summarized
    articles from the last window (cross-run), then against other candidates
    in this run (same-run clustering, longest article kept).

    Duplicates are flagged via ``set_duplicate_of`` (excluding them from
    summarization) and linked via ``save_correlation`` so the kept article can
    surface an "also reported by" cross-reference.

    A silent no-op when dedup is disabled or no OpenAI key is configured.

    Returns:
        Number of articles marked as duplicates.
    """
    config = load_config()
    if not config.get("dedup_enabled", True):
        return 0

    client = _get_client()
    if client is None:
        logger.info("No OpenAI API key configured, skipping deduplication")
        return 0

    threshold = float(config.get("dedup_threshold", 0.85))

    candidates = get_dedup_candidates()
    if not candidates:
        return 0

    # 1. Embed every candidate (title + content snippet) and persist for future runs.
    embedded = []  # list of (candidate dict, unit vector)
    for i in range(0, len(candidates), DEDUP_EMBED_BATCH):
        chunk = candidates[i:i + DEDUP_EMBED_BATCH]
        texts = [
            f"{c['title'] or ''}\n{(c['content_raw'] or '')[:DEDUP_CONTENT_SNIPPET]}"
            for c in chunk
        ]
        embeddings = generate_embeddings_batch(texts)
        if embeddings is None:
            logger.warning("Embedding generation failed during dedup, aborting dedup pass")
            return 0
        for cand, emb in zip(chunk, embeddings):
            blob = _floats_to_blob(emb)
            try:
                save_dedup_embedding(cand["id"], blob, EMBEDDING_MODEL)
            except Exception as e:
                logger.error(f"Failed to save dedup embedding for article {cand['id']}: {e}")
            vec = _blob_to_array(blob).astype(np.float32)
            norm = np.linalg.norm(vec)
            if norm == 0:
                continue
            embedded.append((cand, vec / norm))

    if not embedded:
        return 0

    # Longest article first, so it becomes the kept representative for its cluster.
    embedded.sort(key=lambda e: len(e[0].get("content_raw") or ""), reverse=True)

    cutoff = (datetime.now() - timedelta(hours=DEDUP_WINDOW_HOURS)).isoformat()
    references = []  # list of (article_id, unit vector, date)
    for ref in get_dedup_reference_embeddings(cutoff):
        vec = _blob_to_array(ref["embedding"]).astype(np.float32)
        norm = np.linalg.norm(vec)
        if norm == 0:
            continue
        references.append((
            ref["article_id"],
            vec / norm,
            ref["published_date"] or ref["fetched_date"],
        ))

    def _cand_date(cand):
        return cand.get("published_date") or cand.get("fetched_date")

    marked = 0
    assigned = set()

    # 2. Cross-run pass: a candidate matching an already-summarized article is the
    #    duplicate (keep the reference regardless of length to avoid re-summarizing it).
    for cand, vec in embedded:
        cand_date = _cand_date(cand)
        for ref_id, ref_vec, ref_date in references:
            if not _within_window(cand_date, ref_date):
                continue
            sim = float(np.dot(vec, ref_vec))
            if sim >= threshold:
                _mark_duplicate(kept_id=ref_id, dup_id=cand["id"], similarity=sim)
                assigned.add(cand["id"])
                marked += 1
                break

    # 3. Same-run clustering: the first unassigned (longest) candidate is the kept
    #    representative; later candidates within the window that match it are duplicates.
    for i in range(len(embedded)):
        head_cand, head_vec = embedded[i]
        if head_cand["id"] in assigned:
            continue
        head_date = _cand_date(head_cand)
        for j in range(i + 1, len(embedded)):
            other_cand, other_vec = embedded[j]
            if other_cand["id"] in assigned:
                continue
            if not _within_window(head_date, _cand_date(other_cand)):
                continue
            sim = float(np.dot(head_vec, other_vec))
            if sim >= threshold:
                _mark_duplicate(kept_id=head_cand["id"], dup_id=other_cand["id"], similarity=sim)
                assigned.add(other_cand["id"])
                marked += 1

    return marked


def _mark_duplicate(kept_id, dup_id, similarity):
    """Flag ``dup_id`` as a duplicate of ``kept_id`` and link them."""
    set_duplicate_of(dup_id, kept_id)
    save_correlation(
        kept_id,
        dup_id,
        "duplicate",
        round(float(similarity), 4),
        "Duplicate coverage of the same topic within 24h",
    )


def cluster_articles_by_similarity(articles, threshold=0.82):
    """Group articles into story clusters using greedy centroid-based cosine similarity.

    Articles without an embedding blob are placed in their own singleton cluster.

    Args:
        articles: List of article dicts, each containing an ``embedding`` key
            with raw float32 bytes (as returned by ``get_articles_with_embeddings_since``).
        threshold: Cosine similarity threshold for merging into an existing cluster.

    Returns:
        List of clusters, where each cluster is a list of article dicts.
    """
    clusters = []        # list of lists of article dicts
    centroids = []       # list of numpy arrays (mean embedding per cluster)

    for article in articles:
        blob = article.get("embedding")
        if not blob:
            clusters.append([article])
            centroids.append(None)
            continue

        vec = _blob_to_array(blob).astype(np.float32)
        vec_norm = np.linalg.norm(vec)
        if vec_norm == 0:
            clusters.append([article])
            centroids.append(None)
            continue
        vec_unit = vec / vec_norm

        best_idx = -1
        best_sim = threshold - 1e-9  # must exceed threshold

        for i, centroid in enumerate(centroids):
            if centroid is None:
                continue
            sim = float(np.dot(centroid, vec_unit))
            if sim > best_sim:
                best_sim = sim
                best_idx = i

        if best_idx >= 0:
            # Merge into existing cluster and update centroid (running mean)
            clusters[best_idx].append(article)
            n = len(clusters[best_idx])
            centroids[best_idx] = (centroids[best_idx] * (n - 1) + vec_unit) / n
            c_norm = np.linalg.norm(centroids[best_idx])
            if c_norm > 0:
                centroids[best_idx] /= c_norm
        else:
            clusters.append([article])
            centroids.append(vec_unit.copy())

    return clusters


def semantic_search(query, top_k=15, since_days=None):
    """Perform semantic search over stored article embeddings.

    Embeds the query string, computes cosine similarity against all
    stored article embeddings, and returns the top-K most similar
    articles with their relevance scores.

    Args:
        query: The natural-language search query.
        top_k: Number of top results to return.
        since_days: If set, restrict search to articles published within
            this many days. None means search all articles.

    Returns:
        List of article dicts, each augmented with a ``relevance_score``
        float (0.0--1.0). Returns an empty list if no API key is
        configured or no embeddings exist.
    """
    client = _get_client()
    if client is None:
        return []

    # Embed the query
    try:
        response = client.embeddings.create(
            model=EMBEDDING_MODEL,
            input=[query],
        )
        query_embedding = np.array(response.data[0].embedding, dtype=np.float32)
    except Exception as e:
        logger.error(f"Failed to embed query: {e}")
        return []

    # Load all stored embeddings, optionally filtered by time period
    all_embs = get_all_embeddings(model_used=EMBEDDING_MODEL)
    if not all_embs:
        return []

    if since_days is not None:
        allowed_ids = get_article_ids_since_days(since_days, model_used=EMBEDDING_MODEL)
        all_embs = [e for e in all_embs if e["article_id"] in allowed_ids]
        if not all_embs:
            return []

    # Build matrix for vectorized cosine similarity
    article_ids = []
    matrix_rows = []
    for row in all_embs:
        article_ids.append(row["article_id"])
        matrix_rows.append(_blob_to_array(row["embedding"]))

    matrix = np.vstack(matrix_rows)  # shape: (N, 1536)

    # Cosine similarity: dot(q, M^T) / (|q| * |M_rows|)
    query_norm = np.linalg.norm(query_embedding)
    if query_norm == 0:
        return []
    row_norms = np.linalg.norm(matrix, axis=1)
    # Avoid division by zero
    row_norms = np.where(row_norms == 0, 1e-10, row_norms)
    similarities = matrix @ query_embedding / (row_norms * query_norm)

    # Get top-K indices
    top_indices = np.argsort(similarities)[::-1][:top_k]

    ranked_ids = [article_ids[i] for i in top_indices]
    ranked_scores = [float(similarities[i]) for i in top_indices]

    # Fetch full article data preserving rank order
    articles = get_articles_by_ids(ranked_ids)

    # Attach scores
    score_map = dict(zip(ranked_ids, ranked_scores))
    for art in articles:
        art["relevance_score"] = round(score_map.get(art["id"], 0), 4)

    return articles
