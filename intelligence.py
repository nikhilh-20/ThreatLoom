import json
import logging
import re
import time

from openai import APIError, RateLimitError

from config import load_config
from embeddings import semantic_search

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are an expert cybersecurity threat intelligence analyst with deep knowledge of malware, vulnerabilities, threat actors, attack techniques, and defensive strategies.

You have been provided with a set of relevant threat intelligence articles retrieved from a curated database. Use these articles as your PRIMARY source of information when answering the user's question.

Guidelines:
- Answer based primarily on the provided articles. Cite article titles in **bold** when referencing specific information from them.
- You may use your own knowledge to explain concepts, provide context, or fill gaps, but clearly distinguish between article-sourced facts and your general knowledge.
- For search-like queries (e.g., "show me articles about X", "find reports on Y"):
  - Provide a brief introductory sentence summarizing what was found
  - The article cards will be displayed separately, so don't list every article — focus on key themes and patterns
- For analytical queries (e.g., "what are common techniques for X", "how do threat actors do Y"):
  - Provide a comprehensive synthesis drawing from multiple articles
  - Cite specific articles that support your points
  - Organize your response with clear structure (use markdown headings, bullet points)
  - Include actionable insights where relevant
- If no relevant articles are found, say so honestly and offer what you can from general knowledge.
- Be concise but thorough. Use markdown formatting for readability.
- Do not fabricate article titles or content that wasn't provided."""

MAX_CONTEXT_CHARS = 30000
MAX_CONVERSATION_MESSAGES = 6


def _build_context(articles):
    """Build a context string from retrieved articles for LLM input.

    Formats each article's title, source, date, relevance score, tags,
    and summary text into a numbered block. Stops adding articles once
    ``MAX_CONTEXT_CHARS`` is exceeded.

    Args:
        articles: List of article dicts from ``semantic_search``.

    Returns:
        A formatted context string describing the retrieved articles,
        or a fallback message if no articles were found.
    """
    if not articles:
        return "No relevant articles were found in the database."

    parts = []
    total_chars = 0
    for i, art in enumerate(articles, 1):
        title = art.get("title", "Untitled")
        source = art.get("source_name", "Unknown")
        date = art.get("published_date", "Unknown date")
        summary = art.get("summary_text", "")
        score = art.get("relevance_score", 0)
        tags = art.get("tags", "[]")
        if isinstance(tags, str):
            try:
                tags = json.loads(tags)
            except (json.JSONDecodeError, TypeError):
                tags = []
        tags_str = ", ".join(tags) if tags else ""

        entry = f"---\nArticle {i}: {title}\nSource: {source} | Date: {date} | Relevance: {score}\nTags: {tags_str}\n\n{summary}\n"
        if total_chars + len(entry) > MAX_CONTEXT_CHARS:
            break
        parts.append(entry)
        total_chars += len(entry)

    return f"Retrieved {len(parts)} relevant articles:\n\n" + "\n".join(parts)


def chat(messages, top_k=15):
    """RAG-based chat: retrieve relevant articles, then generate a response.

    Extracts the latest user message, performs semantic search to find
    relevant articles, builds a context window, and sends everything
    to the OpenAI API for a synthesized response. Retries up to 3
    times on rate limits or API errors.

    Args:
        messages: List of conversation message dicts, each with
            ``role`` (``"user"`` or ``"assistant"``) and ``content``.
        top_k: Number of articles to retrieve for context.

    Returns:
        A dict with keys:
            - ``response``: The LLM-generated answer string, or None.
            - ``articles``: List of retrieved article dicts.
            - ``model_used``: The OpenAI model name used.
            - ``error``: Error string or None on success.
    """
    config = load_config()
    api_key = config.get("openai_api_key", "").strip()
    if not api_key:
        return {
            "response": None,
            "articles": [],
            "model_used": None,
            "error": "no_api_key",
        }

    model = config.get("openai_model", "gpt-4o-mini")

    # Extract latest user message for retrieval
    user_messages = [m for m in messages if m.get("role") == "user"]
    if not user_messages:
        return {
            "response": "Please ask a question about threat intelligence.",
            "articles": [],
            "model_used": model,
            "error": None,
        }

    query = user_messages[-1]["content"]

    # Semantic search for relevant articles
    articles = semantic_search(query, top_k=top_k)

    # Build context from retrieved articles
    context = _build_context(articles)

    # Build LLM messages
    llm_messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "system", "content": f"RETRIEVED ARTICLES:\n\n{context}"},
    ]

    # Add last N conversation messages for follow-up context
    recent = messages[-MAX_CONVERSATION_MESSAGES:]
    llm_messages.extend(recent)

    # Call OpenAI
    from openai import OpenAI
    client = OpenAI(api_key=api_key)

    for attempt in range(3):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=llm_messages,
                temperature=0.3,
                max_tokens=2000,
            )
            answer = response.choices[0].message.content
            return {
                "response": answer,
                "articles": articles,
                "model_used": model,
                "error": None,
            }
        except RateLimitError:
            wait = 2 ** (attempt + 1)
            logger.warning(f"Rate limited, waiting {wait}s before retry")
            time.sleep(wait)
        except APIError as e:
            logger.error(f"Chat API error (attempt {attempt + 1}): {e}")
            if attempt < 2:
                time.sleep(1)
        except Exception as e:
            logger.error(f"Unexpected error during chat: {e}")
            return {
                "response": None,
                "articles": articles,
                "model_used": model,
                "error": str(e),
            }

    return {
        "response": None,
        "articles": articles,
        "model_used": model,
        "error": "Failed after 3 retries",
    }
