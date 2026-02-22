import logging
import struct
import time

import numpy as np
from openai import APIError, RateLimitError

from config import load_config
from database import (
    get_all_embeddings,
    get_article_ids_since_days,
    get_articles_by_ids,
    get_unembedded_articles,
    save_embedding,
)

logger = logging.getLogger(__name__)

EMBEDDING_MODEL = "text-embedding-3-small"
EMBEDDING_DIMS = 1536


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


def embed_pending_articles(limit=50):
    """Fetch unembedded articles, generate embeddings, and store as BLOBs.

    Retrieves articles that have summaries but no embedding yet,
    generates embeddings for their title + summary text, and saves
    the resulting vectors to the database.

    Args:
        limit: Maximum number of articles to process in this batch.

    Returns:
        Total number of articles processed (0 means nothing left).
    """
    articles = get_unembedded_articles(limit=limit)
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
