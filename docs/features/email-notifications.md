# Email Notifications

Threat Loom can send an email alert for every newly summarized article. Each email contains the full structured analysis: executive summary, novelty assessment, technical details, and mitigations.

## Setup

1. Go to **Settings** in the web UI
2. Enable **Email Notifications**
3. Fill in your SMTP server details and recipient address
4. Click **Send Test Email** to verify
5. Click **Save Settings**

## SMTP Configuration

| Field | Description |
|---|---|
| Recipient Email | Address that receives notifications |
| SMTP Host | Your mail server hostname |
| SMTP Port | Usually `587` (STARTTLS) or `465` (SSL) |
| SMTP Username | Login username (often your email address) |
| SMTP Password | App password or SMTP password |
| Use STARTTLS | Enable TLS encryption (recommended) |

### Common SMTP Providers

#### Gmail

| Setting | Value |
|---|---|
| Host | `smtp.gmail.com` |
| Port | `587` |
| Username | `you@gmail.com` |
| Password | [App Password](https://support.google.com/accounts/answer/185833) |
| TLS | Enabled |

!!! warning "Gmail App Passwords"
    Gmail requires an **App Password** if you have 2-Factor Authentication enabled. Regular account passwords will not work. Go to [Google App Passwords](https://myaccount.google.com/apppasswords) to generate one.

#### Outlook / Microsoft 365

| Setting | Value |
|---|---|
| Host | `smtp.office365.com` |
| Port | `587` |
| Username | `you@outlook.com` |
| Password | Your account password |
| TLS | Enabled |

#### SendGrid

| Setting | Value |
|---|---|
| Host | `smtp.sendgrid.net` |
| Port | `587` |
| Username | `apikey` |
| Password | Your SendGrid API key |
| TLS | Enabled |

## Email Content

Each notification email includes:

- **Article title** with a link to the original source
- **Executive Summary** — concise overview of the threat
- **Novelty** — what is new or noteworthy about the reported activity
- **Details** — technical findings, IOCs, CVEs, timelines
- **Mitigations** — actionable defensive recommendations

## Environment Variables

For Docker deployments, SMTP settings can be configured via environment variables in `docker-compose.yml`:

```yaml
environment:
  - SMTP_HOST=smtp.gmail.com
  - SMTP_PORT=587
  - SMTP_USERNAME=you@gmail.com
  - SMTP_PASSWORD=your-app-password
  - NOTIFICATION_EMAIL=you@gmail.com
```

Setting `NOTIFICATION_EMAIL` automatically enables email notifications.

## Behavior Notes

- **Emails are sent per article** — one email for each successfully summarized article
- **Failures never block the pipeline** — if an email fails to send, the error is logged and processing continues
- **No external dependencies** — uses Python's built-in `smtplib` and `email.mime` modules
- **TLS is enabled by default** on port 587 using STARTTLS
- **SMTP password is stored in plaintext** in `config.json`, consistent with how API keys are stored
