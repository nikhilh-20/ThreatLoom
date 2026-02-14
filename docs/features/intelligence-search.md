# Intelligence Search

The Intelligence page provides a RAG-powered (Retrieval-Augmented Generation) chat interface for querying your collected threat intelligence. Ask questions in natural language and get AI-generated answers grounded in your article database.

## How It Works

```
User Query
    │
    ▼
Generate Query Embedding
    │  (text-embedding-3-small)
    ▼
Semantic Search
    │  (cosine similarity against all article embeddings)
    ▼
Retrieve Top-K Articles
    │  (default: 15 most relevant)
    ▼
Build Context Window
    │  (article summaries, max 30,000 chars)
    ▼
LLM Response Generation
    │  (grounded in retrieved articles)
    ▼
Response + Citation Cards
```

### Step-by-Step

1. **Embed the query** — Your question is converted to a 1536-dimensional vector using `text-embedding-3-small`
2. **Semantic search** — The query vector is compared against all stored article embeddings using cosine similarity
3. **Retrieve articles** — The top 15 most relevant articles are selected
4. **Build context** — Article metadata and summaries are formatted into a context block (capped at 30,000 characters, roughly 11 articles)
5. **Generate response** — The LLM receives the context, conversation history (last 6 messages), and a system prompt instructing it to answer based on the retrieved articles
6. **Return with citations** — The response includes markdown text plus the retrieved articles as citation cards

## Embedding Generation

Embeddings are generated as part of the standard pipeline, after summarization:

| Setting | Value |
|---|---|
| Model | `text-embedding-3-small` (OpenAI) |
| Dimensions | 1,536 |
| Input | Article title + summary text |
| Storage | SQLite BLOB (numpy float32 bytes) |
| Batch size | Up to 50 articles per pipeline run |

### Embedding Status

The Intelligence page shows an embedding status indicator:

- **"X of Y articles embedded"** — Shows coverage of your article database
- Embeddings are generated incrementally as new articles are summarized

## Semantic Search vs. Keyword Search

Threat Loom offers two search modes:

| | Keyword Search (Dashboard) | Semantic Search (Intelligence) |
|---|---|---|
| **How** | SQL `LIKE` matching on title and content | Cosine similarity on vector embeddings |
| **Matches** | Exact or substring matches | Conceptually similar content |
| **Example** | "LockBit" finds articles mentioning "LockBit" | "ransomware targeting hospitals" finds articles about healthcare ransomware even without those exact words |
| **Best for** | Finding specific articles | Exploring themes and asking analytical questions |

## Chat Interface

### Sending Messages

Type your question in the text area and press **Enter** or click the send button. The interface supports multi-turn conversations — the LLM receives the last 6 messages for context continuity.

### Response Format

The AI responds with structured markdown:

- **Search queries** — Brief intro followed by article citation cards
- **Analytical questions** — Comprehensive synthesis with headings, bullet points, and article references (cited in **bold**)

### Citation Cards

Each retrieved article appears as a citation card showing:

- **Relevance score** — Percentage indicating semantic similarity (0-100%)
- **Source** — The feed source name
- **Date** — Publication date
- **Title** — Article title (clickable link to detail page)
- **Tags** — Category and entity tags

### Suggested Queries

The welcome screen shows suggestion chips for common query types:

- Threat actor activity
- Recent vulnerability trends
- Malware family analysis
- Defensive recommendations

## Standalone Semantic Search

The `/api/intelligence/search` endpoint provides raw semantic search without LLM generation:

```json
POST /api/intelligence/search
{
  "query": "supply chain attacks targeting npm packages",
  "top_k": 10
}
```

Returns ranked articles with relevance scores, without generating a chat response. Useful for programmatic access to the search index.

## Limitations

- **Embedding coverage** — Only summarized articles have embeddings. Articles that haven't been processed yet won't appear in search results.
- **Context window** — The LLM sees at most ~11 articles per query. Highly broad queries may miss relevant articles beyond the top 15.
- **No real-time data** — Answers are based on ingested articles, not live internet searches.
- **Conversation length** — Only the last 6 messages are sent to the LLM. Long conversations lose early context.
