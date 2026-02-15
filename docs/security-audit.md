# Security Audit Report

**Date:** 2026-02-14
**Scope:** Threat Loom application — dependency vulnerabilities, static code analysis, secret scanning, container image security
**Tools:** pip-audit 2.10.0, bandit 1.9.3, detect-secrets 1.5.0, Docker Scout (Docker 29.2.0)

---

## Summary

| Scan | Tool | Findings | Critical / High | Status |
|---|---|---|---|---|
| Dependency vulnerabilities | pip-audit | 1 | 0 / 0 | Remediated |
| Static code analysis | bandit | 6 | 0 / 0 | Reviewed — all false positives |
| Secret scanning | detect-secrets | 0 | — | Pass |
| Container image | Docker Scout (Docker 29.2.0) | 21 | 0 / 0 | Accepted — all in OS base image, no fixes available |

**Overall assessment:** No critical or high-severity issues. One medium-severity dependency CVE remediated. All static analysis findings are false positives confirmed by manual review. Container image scan found 21 OS-level vulnerabilities (1 medium, 20 low) — all in Debian base packages with no upstream fixes available. No application-level container vulnerabilities.

---

## 1. Dependency Vulnerability Scan (pip-audit)

**Command:**
```bash
pip-audit --format json
```

**Total packages scanned:** 82

### Findings

| Package | Version | CVE | Severity | Description | Status |
|---|---|---|---|---|---|
| pip | 25.3 | CVE-2026-1703 | Medium | Path traversal when extracting maliciously crafted wheel archives. Files may be extracted outside the installation directory, limited to prefixes of the install path. | Remediated |

### Remediation

- **Dockerfile:** Added `pip install --upgrade pip` before installing dependencies, ensuring the container always uses the latest patched pip.
- **Local development:** Developers should run `pip install --upgrade pip` in their virtual environment.
- **Note:** pip is a build-time tool, not a runtime dependency. This CVE requires a maliciously crafted wheel to be installed, making exploitation unlikely in normal operation.

### Clean packages (notable)

All runtime dependencies are vulnerability-free as of this scan date:

| Package | Version | Status |
|---|---|---|
| flask | 3.1.2 | Clean |
| openai | 2.20.0 | Clean |
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
bandit -r . --exclude ./venv,./site,./__pycache__ -f json
```

**Total lines scanned:** 4,519 across 12 files

### Findings

#### B608 — SQL Injection (5 instances in `database.py`) — FALSE POSITIVE

| Location | Severity | Confidence |
|---|---|---|
| database.py:525 | Medium | Medium |
| database.py:1090 | Medium | Medium |
| database.py:1091 | Medium | Medium |
| database.py:1093 | Medium | Medium |
| database.py:1096 | Medium | Medium |

**Analysis:** All five flagged lines use the pattern:
```python
ph = ",".join("?" * len(ids))
conn.execute(f"DELETE FROM articles WHERE id IN ({ph})", ids)
```
The f-string interpolates only the `?` placeholder string (e.g., `?,?,?`), not user data. Actual values are passed through SQLite's parameterized query binding. This is the standard, safe pattern for dynamic `IN` clauses in SQLite, which does not support array parameters. **No user-controllable input reaches the SQL string.**

**Verdict:** False positive. No fix required.

#### B112 — Try/Except/Continue (1 instance in `database.py`) — ACCEPTABLE

| Location | Severity | Confidence |
|---|---|---|
| database.py:1083 | Low | High |

**Analysis:** The code catches exceptions during `urlparse()` on article URLs and continues to the next row. This is intentional defensive coding to skip malformed URLs stored in the database without crashing the cleanup function.

**Verdict:** Acceptable pattern. No fix required.

### Files with zero findings

| File | Lines |
|---|---|
| app.py | 547 |
| config.py | 107 |
| notifier.py | 164 |
| article_scraper.py | 127 |
| embeddings.py | 153 |
| feed_fetcher.py | 192 |
| intelligence.py | 143 |
| malpedia_fetcher.py | 169 |
| mitre_data.py | 957 |
| scheduler.py | 109 |
| summarizer.py | 446 |

---

## 3. Secret Scanning (detect-secrets)

**Command:**
```bash
detect-secrets scan --exclude-files 'venv/.*' --exclude-files 'site/.*'
```

**Result:** No secrets detected.

The scan checked for:

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
**Image:** `threat-loom:latest` (121 MB, 191 packages indexed)
**Base image:** `python:3.13-slim` (Debian Trixie 13)

### Summary

| Severity | Count |
|---|---|
| Critical | 0 |
| High | 0 |
| Medium | 1 |
| Low | 20 |
| **Total** | **21** |

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

Docker Scout recommends `python:3.14-alpine` as an alternative base image, which would reduce vulnerabilities from 21 to 2 (1M, 1L) and shrink image size from 121 MB to ~18 MB. Trade-off: Alpine uses musl libc instead of glibc, which may cause compatibility issues with some compiled Python packages (e.g., numpy). This should be evaluated in a future iteration.

---

## 5. Recommendations

### Immediate (completed)

- [x] Upgrade pip in Dockerfile to fix CVE-2026-1703
- [x] Run container as non-root user
- [x] Exclude secrets and data files from Docker image via `.dockerignore`
- [x] Support API keys via environment variables (no hardcoding needed)

### Ongoing

- [ ] Re-run `pip-audit` periodically or add it to CI to catch new CVEs in dependencies
- [ ] Run container image scan (Docker Scout) — completed; 0 critical/high findings
- [ ] Pin dependency versions in `requirements.txt` for reproducible builds (currently using `>=` minimum version constraints)
- [ ] Consider adding `bandit` to a pre-commit hook or CI pipeline
- [ ] Consider adding a `.secrets.baseline` file for `detect-secrets` to track known false positives over time
- [ ] Consider encrypting SMTP credentials at rest in `config.json` (currently plaintext, matching the existing API key pattern)

---

## Appendix: Tool Versions

| Tool | Version |
|---|---|
| pip-audit | 2.10.0 |
| bandit | 1.9.3 |
| detect-secrets | 1.5.0 |
| Python | 3.13 |
| Docker | 29.2.0 |
| Docker Scout | built-in |
| pip | 25.3 (26.0.1 in Docker) |
