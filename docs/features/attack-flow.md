# Attack Flow

The Attack Sequence visualization provides an interactive, phase-by-phase walkthrough of how an attack unfolds. It maps each stage to MITRE ATT&CK tactics and techniques, giving analysts a structured view of the kill chain.

## What It Shows

When an article describes an attack with identifiable phases, the summarizer generates an ordered attack flow. Each phase includes:

- **Phase name** — The MITRE ATT&CK tactic (Initial Access, Execution, Persistence, etc.)
- **Title** — A concise description of the action taken
- **Description** — 2-3 sentences explaining what happens in this phase
- **Technique ID** — The corresponding MITRE ATT&CK technique (e.g., T1566.001) when applicable

## Example Attack Flow

```
Phase 1: Initial Access
├── Title: Spearphishing with macro-enabled document
├── Description: Attacker sends targeted email to finance team
│   with weaponized DOCX attachment exploiting CVE-2024-XXXX
└── Technique: T1566.001

Phase 2: Execution
├── Title: PowerShell stager deployment
├── Description: Macro executes obfuscated PowerShell command
│   that downloads second-stage payload from C2 server
└── Technique: T1059.001

Phase 3: Persistence
├── Title: Scheduled task creation
├── Description: Payload installs as scheduled task running
│   every 15 minutes under SYSTEM context
└── Technique: T1053.005

Phase 4: Command & Control
├── Title: HTTPS beacon to C2 infrastructure
├── Description: Cobalt Strike beacon establishes encrypted
│   channel using domain fronting through CDN
└── Technique: T1071.001
```

## Interactive Controls

The attack flow visualization uses a progressive reveal mechanic:

### Phase Nodes

Each phase appears as a node on a vertical timeline. The first phase is revealed by default; subsequent phases are locked with a "CLASSIFIED" overlay.

### Navigation

- **Next Phase** — Reveals the next locked phase in sequence
- **Reveal All** — Unlocks all remaining phases at once
- **Progress Bar** — Animated bar showing how many phases have been revealed

### Sequence Complete

After all phases are revealed, a "Sequence Complete" banner appears, indicating the full attack chain has been reviewed.

## MITRE ATT&CK Tactics

Attack flow phases map to standard MITRE ATT&CK tactics:

| Tactic | Description |
|---|---|
| Reconnaissance | Gathering information for planning |
| Resource Development | Establishing infrastructure and capabilities |
| Initial Access | Gaining entry to the target environment |
| Execution | Running malicious code |
| Persistence | Maintaining foothold across restarts |
| Privilege Escalation | Gaining higher-level permissions |
| Defense Evasion | Avoiding detection |
| Credential Access | Stealing credentials |
| Discovery | Exploring the environment |
| Lateral Movement | Moving through the network |
| Collection | Gathering target data |
| Command & Control | Communicating with compromised systems |
| Exfiltration | Stealing data |
| Impact | Disrupting availability or integrity |

## When Attack Flow Is Empty

Not all articles describe attacks with identifiable phases. The attack flow section is omitted for:

- Vulnerability disclosures without exploitation details
- Policy and compliance articles
- General security news
- Product announcements
- Research without a concrete attack chain

In these cases, the article page shows the summary, tags, and key points without the timeline visualization.

## Article Card Preview

When browsing articles on the dashboard, expanded article cards show a compact attack sequence summary. Each phase is rendered as an ordered list item with the tactic name, step title, and MITRE technique ID. This gives a quick overview without navigating to the full interactive visualization on the article detail page.

## Visual Design

The attack flow timeline uses a cybersecurity-themed aesthetic:

- **Scanline header** — Animated CRT-style effect on the section title
- **Phase connectors** — Vertical line connecting phase nodes
- **Locked overlay** — "CLASSIFIED" text with blur effect on unrevealed phases
- **Progress animation** — Fills as phases are revealed
- **Dark theme** — Consistent with the application's overall design

!!! info "Screenshot"
    _A screenshot of the Attack Flow visualization can be added here._
