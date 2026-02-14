# Contributing

Thank you for your interest in contributing to Threat Loom. This guide covers the development setup, code structure, and how to add new functionality.

## Development Setup

### Prerequisites

- Python 3.10+
- An OpenAI API key (for testing summarization and search features)
- Git

### Getting Started

```bash
# Clone the repository
git clone https://github.com/nikhilh-20/ThreatLoom.git
cd ThreatLoom

# Create a virtual environment
python -m venv venv
source venv/bin/activate  # Linux/macOS
# or: venv\Scripts\activate  # Windows

# Install dependencies
pip install -r requirements.txt

# Run the application
python app.py
```

### Running with MkDocs (Documentation)

```bash
pip install mkdocs-material
mkdocs serve
```

The documentation site will be available at `http://localhost:8000`.

## Code Structure

```
ThreatLoom/
├── app.py                  # Flask web server, routes, API endpoints
├── config.py               # Configuration loading/saving
├── config.json             # User configuration (API keys, feeds)
├── database.py             # SQLite interface, schema, categorization
├── scheduler.py            # Background pipeline orchestration
├── feed_fetcher.py         # RSS/Atom feed ingestion
├── malpedia_fetcher.py     # Malpedia BibTeX ingestion
├── article_scraper.py      # HTML download and text extraction
├── summarizer.py           # LLM summarization, relevance, insights
├── embeddings.py           # Vector embedding generation and search
├── intelligence.py         # RAG chat system
├── mitre_data.py           # MITRE ATT&CK entity lookups
├── requirements.txt        # Python dependencies
├── run.bat                 # Windows launch script
├── templates/              # Jinja2 HTML templates
│   ├── base.html           # Layout (sidebar, top bar)
│   ├── index.html          # Dashboard (categories, articles)
│   ├── article.html        # Article detail (summary, attack flow)
│   ├── intelligence.html   # RAG chat interface
│   └── settings.html       # Configuration UI
├── static/
│   ├── css/style.css       # Application styles (dark theme)
│   └── js/app.js           # Client-side logic
├── docs/                   # MkDocs documentation source
└── mkdocs.yml              # MkDocs configuration
```

## Style Guidelines

### Python

- Follow PEP 8 conventions
- Use type hints for function signatures
- Keep functions focused — one clear responsibility each
- Use f-strings for string formatting
- Handle API errors with retry logic and exponential backoff
- Use thread-local database connections (never share connections across threads)

### JavaScript

- Use `const` and `let` (no `var`)
- Prefix unused callback parameters with `_`
- DOM manipulation via `document.getElementById` / `querySelector`
- API calls via `fetch()` with async/await

### CSS

- Use CSS custom properties (variables) for theming
- BEM-like naming for component classes
- Dark theme as the default

### General

- No trailing whitespace
- UTF-8 encoding for all files
- LF line endings

## How to Add a New Feed Source

### 1. Add to Default Config

In `config.py`, add the feed to the `feeds` array in `get_default_config()`:

```python
{
    "name": "New Security Blog",
    "url": "https://newsecurityblog.com/feed/",
    "enabled": True
}
```

### 2. Test the Feed

Run the application, navigate to Settings, and trigger a manual refresh. Verify:

- The feed is fetched without errors
- Articles are parsed correctly (title, date, URL)
- Relevance filtering works as expected

### 3. Handle Edge Cases

Some feeds may need special handling:

- **Non-standard date formats** — Check `_parse_date()` in `feed_fetcher.py`
- **Missing fields** — Ensure graceful fallbacks for missing authors or images
- **Rate limiting** — The fetcher uses a 20-second timeout per feed

## How to Extend Categorization Rules

The categorization system lives in `database.py` in the `_CATEGORY_RULES` dictionary.

### Adding a New Category

```python
_CATEGORY_RULES = {
    # ... existing categories ...
    "New Category": {
        "keywords": {"keyword1", "keyword2", "keyword3"},
    },
}
```

### Adding Keywords to an Existing Category

Add terms to the `keywords` set in the relevant category entry:

```python
"Malware": {
    "keywords": {
        "malware", "trojan", "backdoor",
        # Add new terms here:
        "new-malware-family",
    },
},
```

### Adding MITRE Entities

For subcategorizable categories (Threat Actors, Malware, C2), entity names are validated against MITRE ATT&CK data in `mitre_data.py`. To add a new entity that isn't in the MITRE dataset:

1. Add it to the relevant set in `mitre_data.py`
2. Or add it to the custom alias handling in `_canonical_entity_tag()` in `database.py`

## How to Add a New API Endpoint

1. **Define the route** in `app.py`:

    ```python
    @app.route('/api/new-endpoint', methods=['GET'])
    def new_endpoint():
        # Implementation
        return jsonify({"result": data})
    ```

2. **Add database queries** in `database.py` if needed

3. **Update the API documentation** in `docs/api-reference.md`

4. **Add client-side support** in `static/js/app.js` if the UI needs to call the endpoint

## Pull Request Process

1. **Fork** the repository
2. **Create a feature branch** from `main`:
    ```bash
    git checkout -b feature/your-feature-name
    ```
3. **Make your changes** with clear, focused commits
4. **Test locally** — verify the application starts, feeds are fetched, and your changes work as expected
5. **Update documentation** if your changes affect configuration, API endpoints, or user-facing features
6. **Submit a pull request** with:
    - A clear title describing the change
    - A description of what was changed and why
    - Any testing steps for reviewers

## Reporting Issues

If you find a bug or have a feature request:

1. Check existing issues to avoid duplicates
2. Open a new issue with:
    - Steps to reproduce (for bugs)
    - Expected vs. actual behavior
    - Python version and OS
    - Relevant error messages or logs
