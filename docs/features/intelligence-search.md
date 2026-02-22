# Intelligence Search

The Intelligence page provides a RAG-powered (Retrieval-Augmented Generation) chat interface for querying your collected threat intelligence. Ask questions in natural language and get AI-generated answers grounded in your article database.

## How It Works

```
User Query
    │
    ▼
Extract Time Reference (optional)
    │  ("last 24 hours", "past 3 days", "last week", …)
    ▼
Generate Query Embedding
    │  (text-embedding-3-small)
    ▼
Semantic Search
    │  (cosine similarity — optionally restricted to articles
    │   published within the detected/specified time window)
    ▼
Retrieve Top-K Articles
    │  (default: 15 most relevant within time window)
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

1. **Detect time reference** — If your query contains a time phrase (e.g., "last 24 hours", "past 3 days", "yesterday", "last week", "last month"), the system extracts it automatically and restricts article retrieval to that window
2. **Embed the query** — Your question is converted to a 1536-dimensional vector using `text-embedding-3-small`
3. **Semantic search** — The query vector is compared against article embeddings (optionally filtered by publication date) using cosine similarity
4. **Retrieve articles** — The top 15 most relevant articles within the time window are selected
5. **Build context** — Article metadata and summaries are formatted into a context block (capped at 30,000 characters, roughly 11 articles)
6. **Generate response** — The LLM receives the context, conversation history (last 6 messages), and a system prompt instructing it to answer based on the retrieved articles
7. **Return with citations** — The response includes markdown text plus the retrieved articles as citation cards

## Time-Period Filtering

The Intelligence search automatically detects natural-language time references in your query and restricts the article search to that window. This allows queries like:

- *"Tell me novel things in the malware world that happened in the last 24 hours"*
- *"What new vulnerabilities were disclosed in the past 3 days?"*
- *"Show me threat actor activity from yesterday"*
- *"Summarise ransomware news from last week"*

### Recognised Time Phrases

| Phrase | Lookback Window |
|---|---|
| `last N hours` / `past N hours` / `N hours ago` | ⌈N/24⌉ days (minimum 1) |
| `last N days` / `past N days` | N days |
| `yesterday` | 1 day |
| `last week` / `past week` / `this week` | 7 days |
| `last month` / `past month` / `this month` | 30 days |

If no time reference is detected, all embedded articles are searched (no time restriction).

### API Override

You can explicitly set the time window via the `since_days` field in the `/api/intelligence/chat` request body. Pass `0` to search all articles regardless of any time phrase in the query:

```json
POST /api/intelligence/chat
{
  "messages": [{"role": "user", "content": "What happened with LockBit recently?"}],
  "since_days": 7
}
```

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
- **Time filtering accuracy** — Natural-language time detection uses regex matching; unusual phrasings may not be recognised. Use the explicit `since_days` API parameter for reliable filtering.

## Safety Guardrails

The Intelligence chat system prompt includes explicit allow/block rules:

**Allowed:**
- Explaining how attack techniques work (defensive context)
- Analysing threat actor TTPs, malware behaviour, and CVE details from ingested articles
- Recommending mitigations and defensive tooling
- General threat intelligence Q&A

**Blocked:**
- Requests to perform attacks on external systems
- Generating exploit code or attack tools
- Detailed operational guidance for offensive activities

Attempts to bypass these rules via prompt injection or jailbreak patterns are explicitly guarded against. Blocked requests receive a brief refusal explaining what is and isn't supported.
