# Security Audit Report

**Date:** 2026-02-22 (updated from 2026-02-19)
**Scope:** Threat Loom application — staged-file secret scanning, dependency vulnerabilities, static code analysis, secret scanning, container image security
**Tools:** gitleaks 8.30.0, pip-audit 2.10.0, bandit 1.9.3, detect-secrets 1.5.0, Docker Scout (Docker 29.2.0)

---

## Change Log

| Date | Change |
|---|---|
| 2026-02-14 | Initial audit |
| 2026-02-19 | Re-audit after feature additions (Anthropic provider, time-period filter, cost estimation, URL validation, sidebar logo). Added gitleaks staged-file scan. |
| 2026-02-22 | Re-audit after feature additions (generate-embeddings button, abort pipeline, clear DB by period, ingest URLs modal, LLM provider-aware API key check, sidebar stats redesign, failed-summary count, Anthropic rate-limit backoff, intelligence system prompt guardrails). |

---

## Summary

| Scan | Tool | Findings | Critical / High | Status |
|---|---|---|---|---|
| Staged-file secret scan | gitleaks 8.30.0 | 0 | 0 / 0 | Pass |
| Dependency vulnerabilities | pip-audit | 1 | 0 / 0 | Remediated (unchanged) |
| Static code analysis | bandit | 15 | 0 / 0 | Reviewed — all false positives or acceptable patterns |
| Secret scanning | detect-secrets | 6 | — | Reviewed — all documentation placeholders |
| Container image | Docker Scout (Docker 29.2.0) | 21 | 0 / 0 | Accepted — all in OS base image, no fixes available |

**Overall assessment:** No critical or high-severity issues. No secrets leaked in staged files. One medium-severity dependency CVE (pip) remains remediated in Docker. All static analysis findings are false positives or intentional exception-handling patterns. All detect-secrets findings are example/placeholder values in documentation files. Container image findings are unchanged (21 OS-level vulnerabilities, no application-level vulnerabilities).

---

## 0. Staged-File Secret Scan (gitleaks)

**Command:**
```bash
D:/gitleaks/gitleaks.exe protect --staged --verbose
```

**Staged files scanned:** 13 files (~31.83 KB)

**Result:** No leaks found.

Gitleaks scanned the following staged files for API keys, tokens, credentials, and other secrets:

| File | Description |
|---|---|
| `app.py` | Flask routes |
| `config.py` | Configuration defaults |
| `database.py` | SQLite data layer |
| `intelligence.py` | RAG chat |
| `llm_client.py` | LLM provider abstraction |
| `notifier.py` | Email notification sender |
| `scheduler.py` | Background task scheduler |
| `static/css/style.css` | Frontend styles |
| `static/js/app.js` | Frontend JavaScript |
| `templates/article.html` | Article detail page template |
| `templates/base.html` | Base HTML template |
| `templates/index.html` | Home page template |
| `templates/settings.html` | Settings page template |

No secrets were detected in any of the staged files. All API keys and credentials continue to be stored exclusively in `data/config.json` (excluded from git and Docker image).

---

## 1. Dependency Vulnerability Scan (pip-audit)

**Command:**
```bash
pip-audit --format json
```

**Total packages scanned:** 91 (unchanged)

### Findings

| Package | Version | CVE | Severity | Description | Status |
|---|---|---|---|---|---|
| pip | 25.3 | CVE-2026-1703 | Medium | Path traversal when extracting maliciously crafted wheel archives. Files may be extracted outside the installation directory, limited to prefixes of the install path. | Remediated in Docker |

**No new CVEs** were introduced by any of the newly added code. All runtime dependencies remain clean.

### Remediation

- **Dockerfile:** `pip install --upgrade pip` runs before installing dependencies, ensuring the container always uses the latest patched pip.
- **Local development:** Developers should run `pip install --upgrade pip` in their virtual environment.
- **Note:** pip is a build-time tool, not a runtime dependency. This CVE requires a maliciously crafted wheel to be installed, making exploitation unlikely in normal operation.

### Clean packages (notable runtime deps)

| Package | Version | Status |
|---|---|---|
| flask | 3.1.2 | Clean |
| openai | 2.20.0 | Clean |
| anthropic | 0.82.0 | Clean |
| requests | 2.32.5 | Clean |
| trafilatura | 2.0.0 | Clean |
| numpy | 2.4.2 | Clean |
| jinja2 | 3.1.6 | Clean |
| werkzeug | 3.1.5 | Clean |
| lxml | 6.0.2 | Clean |
| certifi | 2026.1.4 | Clean |
| urllib3 | 2.6.3 | Clean |

---

## 2. Static Code Analysis (bandit)

**Command:**
```bash
bandit -r . --exclude ./venv,./site,./__pycache__,./android,./docs -f json
```

**Total lines scanned:** 4,964 across 14 files

> Note: LOC decreased from 5,529 in the previous audit because bandit's `loc` metric counts executable lines only and excludes blank lines and comments; the file count is unchanged at 14. All files have grown in size.

### Findings

#### B608 — SQL Injection (11 instances in `database.py`) — FALSE POSITIVE

| Location | Severity | Confidence | Context |
|---|---|---|---|
| database.py:563 | Medium | Medium | `get_articles_by_ids()` — `IN ({ph})` placeholder construction |
| database.py:914 | Medium | Medium | `get_categorized_articles()` — date_filter f-string |
| database.py:1007 | Medium | Medium | `get_articles_for_category()` — date_filter f-string |
| database.py:1146 | Medium | Medium | `delete_file_url_articles()` — `IN ({ph})` placeholder |
| database.py:1147 | Medium | Medium | `delete_file_url_articles()` — `IN ({ph})` placeholder |
| database.py:1149 | Medium | Medium | `delete_file_url_articles()` — `IN ({ph})` placeholder |
| database.py:1152 | Medium | Medium | `delete_file_url_articles()` — `IN ({ph})` placeholder |
| database.py:1174 | Medium | Medium | `delete_article_summary()` — `IN ({ph})` placeholder *(new)* |
| database.py:1219 | Medium | Medium | `clear_articles_before_days()` — `IN ({ph})` placeholder *(new)* |
| database.py:1220 | Medium | Medium | `clear_articles_before_days()` — `IN ({ph})` placeholder *(new)* |
| database.py:1222 | Medium | Medium | `clear_articles_before_days()` — `IN ({ph})` placeholder *(new)* |

**Analysis:**

All eleven flagged lines fall into two safe patterns:

**Pattern A — Dynamic `IN` clause (lines 563, 1146–1152, 1174, 1219–1222):**
```python
ph = ",".join("?" * len(ids))
conn.execute(f"DELETE FROM articles WHERE id IN ({ph})", ids)
```
The f-string interpolates only the `?` placeholder string (e.g., `?,?,?`), never user data. Values are passed via SQLite's parameterized binding. This is the standard safe pattern for dynamic `IN` clauses in SQLite.

**Pattern B — Optional date filter (lines 914, 1007):**
```python
date_filter = ""
if since_days:
    date_filter = " AND date(a.published_date) >= date('now', ?)"
    params.append(f"-{since_days} days")
rows = conn.execute(f"...{date_filter}...", params)
```
The `{date_filter}` interpolated into the query is a hardcoded SQL string constant, not user input. The user-supplied `since_days` is cast to `int` in the Flask route (`type=int`) and then formatted as `-N days` — passed as a parameterized value to SQLite, never interpolated into the SQL string.

The four new findings (lines 1174, 1219–1222) are from two new functions added in this iteration:
- `delete_article_summary(article_id)` — removes one article's summary and embeddings by its integer primary key
- `clear_articles_before_days(days)` — bulk-deletes articles older than N days using pre-computed article IDs

Both exclusively use Pattern A.

**No user-controllable input reaches the SQL string in any of these locations.**

**Verdict:** False positives. No fix required.

#### B112 — Try/Except/Continue (1 instance in `database.py`) — ACCEPTABLE

| Location | Severity | Confidence |
|---|---|---|
| database.py:1139 | Low | High |

**Analysis:** The code catches exceptions during `urlparse()` on article URLs stored in the database and continues to the next row. This is intentional defensive coding to skip malformed URLs without crashing the cleanup function `delete_file_url_articles()`.

**Verdict:** Acceptable pattern. No fix required.

#### B105 — Hardcoded Password (2 instances in `config.py`) — FALSE POSITIVE

| Location | Severity | Confidence | Context |
|---|---|---|---|
| config.py:54 | Low | Medium | `"smtp_password": ""` |
| config.py:58 | Low | Medium | `"report_token": ""` *(new)* |

**Analysis:** Both flags are empty string defaults in the configuration template. `smtp_password` was present in the previous audit. `report_token` is the new optional pre-shared token for the `/api/report` endpoint added in this iteration. Neither contains an actual credential — both are placeholders that prompt the user to configure their own value. Real values are stored in `data/config.json` (excluded from git and Docker image) or passed via environment variables.

**Verdict:** False positives. No fix required.

#### B110 — Try/Except/Pass (1 instance in `llm_client.py`) — ACCEPTABLE *(new)*

| Location | Severity | Confidence |
|---|---|---|
| llm_client.py:136 | Low | High |

**Analysis:**
```python
try:
    ra = getattr(getattr(e, "response", None), "headers", {}).get("retry-after")
    if ra:
        wait = max(int(ra), retry_delay)
except Exception:
    pass
```
The `except Exception: pass` is intentional and narrowly scoped: if parsing the `Retry-After` response header fails for any reason (missing attribute, non-integer value, malformed header), the code silently falls back to the default `retry_delay` value. The outer retry loop is unaffected and still executes the exponential backoff correctly. There is no meaningful action to take on a header-parsing failure other than ignoring it.

**Verdict:** Acceptable pattern. No fix required.

### Files with zero findings

| File | Lines |
|---|---|
| app.py | 689 |
| article_scraper.py | 127 |
| cost_tracker.py | 46 |
| embeddings.py | 153 |
| feed_fetcher.py | 209 |
| intelligence.py | 161 |
| malpedia_fetcher.py | 169 |
| mitre_data.py | 957 |
| notifier.py | 172 |
| scheduler.py | 320 |
| summarizer.py | 650 |

---

## 3. Secret Scanning (detect-secrets)

**Command:**
```bash
detect-secrets scan \
  --exclude-files 'venv/.*' \
  --exclude-files 'android/.*' \
  --exclude-files '.*\.(jpg|jpeg|png|ico|gif|svg)$'
```

**Result:** 6 findings across 3 files — all documentation placeholder values.

| File | Line | Type | Content | Verdict |
|---|---|---|---|---|
| `docs/api-reference.md` | 485 | Secret Keyword | `"openai_api_key": "sk-proj-..."` | False positive — example placeholder in API docs (line shifted from 399 due to new endpoint docs) |
| `docs/api-reference.md` | 503 | Secret Keyword | `"smtp_password": "app-password"` | False positive — label string in API docs (line shifted from 415) |
| `docs/getting-started.md` | 75 | Secret Keyword | `"openai_api_key": "sk-proj-your-key-here"` | False positive — placeholder in getting-started guide (unchanged) |
| `docs/security-audit.md` | 221 | Secret Keyword | *(same hash as api-reference.md:485)* | False positive — this audit document quoting previous findings |
| `docs/security-audit.md` | 222 | Secret Keyword | *(same hash as api-reference.md:503)* | False positive — this audit document quoting previous findings |
| `docs/security-audit.md` | 223 | Secret Keyword | *(same hash as getting-started.md:75)* | False positive — this audit document quoting previous findings |

The 3 new findings in `docs/security-audit.md` are self-referential: the audit document itself quotes the placeholder strings from the previous findings table, which detect-secrets flags. This is expected behaviour when an audit document records prior findings.

The 2 findings in `docs/api-reference.md` are the same placeholders as the previous audit; line numbers shifted because new endpoint documentation was added above them.

All 6 findings are in documentation files and contain clearly non-secret placeholder text. The `sk-proj-...` and `sk-proj-your-key-here` strings are not valid OpenAI API keys (real keys have 51+ character random suffixes). The string `app-password` is a generic label used in documentation examples, not a real credential.

**No secrets detected in application source code, templates, or static assets.**

The scan checks for:

- AWS keys, Azure storage keys
- OpenAI API keys, GitHub/GitLab tokens
- High-entropy Base64/hex strings
- JWT tokens, private keys
- Basic auth credentials
- Slack, Stripe, Twilio, SendGrid, Telegram tokens
- Artifactory, Cloudant, IBM Cloud, Mailchimp, npm, PyPI tokens

**Design notes:**

- API keys and SMTP credentials are stored in `data/config.json` (excluded from Docker image via `.dockerignore`) or passed via environment variables
- No secrets are hardcoded in source code
- The `config.json` template in docs uses placeholder values (`sk-proj-your-key-here`)
- SMTP passwords are stored in plaintext in `config.json`, consistent with the existing pattern for API keys. For Docker deployments, prefer environment variables (`SMTP_PASSWORD`)

---

## 4. Container Image Scan (Docker Scout)

**Command:**
```bash
docker build -t threat-loom .
docker scout cves threat-loom
```

**Scanner:** Docker Scout (Docker 29.2.0)
**Image:** `threat-loom:latest` (124 MB, 193 packages indexed)
**Base image:** `python:3.13-slim` (Debian Trixie 13)

> Container image was not rebuilt in this iteration (no changes to `requirements.txt`, `Dockerfile`, or base image). Results are unchanged from the 2026-02-19 audit.

### Summary

| Severity | Count | Change from v2 |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 1 | Unchanged |
| Low | 20 | Unchanged |
| **Total** | **21** | **Unchanged** |

### Findings

All 21 vulnerabilities are in **Debian OS base packages** shipped with `python:3.13-slim`. None are in application code or Python dependencies. All have **no fix available** from Debian upstream.

| Package | Version | CVEs | Severity | Notes |
|---|---|---|---|---|
| tar | 1.35+dfsg-3.1 | CVE-2025-45582 | Medium | Exploiting requires crafting malicious tar archives; application does not process tar files |
| tar | 1.35+dfsg-3.1 | CVE-2005-2541 | Low | Permissions-preserving behavior; known for 20+ years, considered by-design |
| glibc | 2.41-12+deb13u1 | 7 CVEs | Low | CVE-2019-9192, CVE-2019-1010025, CVE-2019-1010024, CVE-2019-1010023, CVE-2019-1010022, CVE-2018-20796, CVE-2010-4756 — all disputed or negligible impact |
| systemd | 257.9-1~deb13u1 | 4 CVEs | Low | CVE-2023-31439, CVE-2023-31438, CVE-2023-31437, CVE-2013-4392 — container does not use systemd as init |
| coreutils | 9.7-3 | 2 CVEs | Low | CVE-2025-5278, CVE-2017-18018 — local attack vector only |
| shadow | 1:4.17.4-2 | CVE-2007-5686 | Low | Informational only |
| openssl | 3.5.4-1~deb13u2 | CVE-2010-0928 | Low | Theoretical cache-timing attack; not practically exploitable |
| apt | 3.0.3 | CVE-2011-3374 | Low | Repository signature validation edge case |
| perl | 5.40.1-6 | CVE-2011-4116 | Low | Temp file race condition in File::Temp; application does not use Perl |
| util-linux | 2.41-5 | CVE-2022-0563 | Low | chfn/chsh READLINE variable disclosure; not relevant in containers |
| sqlite3 | 3.46.1-7 | CVE-2021-45346 | Low | Disputed; considered a non-issue by SQLite maintainers |

### Risk assessment

- **No critical or high vulnerabilities** in the container image
- The single **medium** CVE (tar) requires an attacker to supply a malicious tar archive for extraction — the application does not process tar files at runtime
- All **20 low** CVEs are in OS packages with no upstream fixes and negligible practical risk in a containerized Python web application
- **No Python package vulnerabilities** detected in the container (0 findings in application layer)
- **No new vulnerabilities** introduced by application code changes in this iteration

### Container hardening applied

| Measure | Details |
|---|---|
| Minimal base image | `python:3.13-slim` — reduced attack surface vs full image |
| Non-root user | `appuser` created and set via `USER` directive |
| No cached packages | `pip install --no-cache-dir` — no pip cache in image |
| pip upgraded | Latest pip installed to avoid CVE-2026-1703 |
| No secrets baked in | `config.json`, `*.db`, and `data/` excluded via `.dockerignore` |
| Minimal file copy | Only `.py`, `templates/`, `static/`, and `requirements.txt` copied |

### Alternative: Alpine-based image

Docker Scout recommends `python:3.14-alpine` as an alternative base image, which would reduce vulnerabilities from 21 to 2 (1M, 1L) and shrink image size significantly. Trade-off: Alpine uses musl libc instead of glibc, which may cause compatibility issues with some compiled Python packages (e.g., numpy). This should be evaluated in a future iteration.

---

## 5. Recommendations

### Immediate (completed)

- [x] Upgrade pip in Dockerfile to fix CVE-2026-1703
- [x] Run container as non-root user
- [x] Exclude secrets and data files from Docker image via `.dockerignore`
- [x] Support API keys via environment variables (no hardcoding needed)
- [x] Validate feed URLs (http/https only) client-side and server-side before saving
- [x] Validate article URLs before rendering as `href` links (prevent `javascript:` / `data:` injection)

### Ongoing

- [ ] Re-run `pip-audit` periodically or add it to CI to catch new CVEs in dependencies
- [ ] Add gitleaks `protect --staged` as a git pre-commit hook to catch secrets before every commit
- [ ] Create a `.secrets.baseline` file for `detect-secrets` to formally track the documentation false positives and prevent re-alerting on them
- [ ] Pin dependency versions in `requirements.txt` for reproducible builds (currently using `>=` minimum version constraints)
- [ ] Consider adding `bandit` to a pre-commit hook or CI pipeline
- [ ] Consider encrypting SMTP credentials at rest in `config.json` (currently plaintext, matching the existing API key pattern)
- [ ] Evaluate Alpine-based Docker image to reduce OS-level CVE surface

---

## Appendix: Tool Versions

| Tool | Version |
|---|---|
| gitleaks | 8.30.0 |
| pip-audit | 2.10.0 |
| bandit | 1.9.3 |
| detect-secrets | 1.5.0 |
| Python | 3.13 |
| Docker | 29.2.0 |
| Docker Scout | built-in |
| pip | 25.3 (26.0.1 in Docker) |
