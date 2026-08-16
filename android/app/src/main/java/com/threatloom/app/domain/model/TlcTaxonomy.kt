package com.threatloom.app.domain.model

/**
 * Fixed taxonomy of "Threat Loom Catalogue" technique tags (prefix "tlc-"). Deliberately
 * separate from the MITRE ATT&CK technique IDs already extracted into attack_flow, to avoid
 * confusing the two. Kept as a single static list so the LLM prompt text embedding it stays
 * byte-identical across calls (required for prompt-cache hits).
 */
object TlcTaxonomy {
    data class Entry(val tag: String, val description: String)

    val entries: List<Entry> = listOf(
        // Initial Access / Delivery
        Entry("tlc-clickfix", "Fake CAPTCHA, verification, browser-update, or support prompt that tricks the victim into pasting and running attacker-supplied commands themselves"),
        Entry("tlc-phishing-brand-impersonation", "Spoofed email or website impersonating a legitimate brand, agency, or executive to harvest credentials or deliver malware"),
        Entry("tlc-fake-job-recruiter-lure", "Fraudulent recruiter or job-interview lure directing the victim to a trojanized coding assessment or repository"),
        Entry("tlc-malvertising", "Malicious or sponsored search ads redirecting victims to malware or phishing pages"),
        Entry("tlc-watering-hole", "Compromising legitimate infrastructure frequented by targets (e.g. a venue's Wi-Fi gateway or a niche site) to redirect them to malicious content"),
        Entry("tlc-typosquatting", "Lookalike or homograph domain/package names impersonating a legitimate one"),
        Entry("tlc-seo-poisoning", "SEO-manipulated fake pages or download sites ranked to appear prominently in search results"),
        Entry("tlc-malicious-package-supply-chain", "Malicious or trojanized package published to npm/PyPI/other registries via postinstall droppers, hijacked maintainer accounts, or dependency confusion"),
        Entry("tlc-malicious-ai-model-upload", "Malicious AI model, LoRA weights, or pickle-serialized artifact uploaded to a model hub to compromise on load"),
        Entry("tlc-trojanized-installer", "Fake or trojanized copy of legitimate popular software distributed via lookalike sites or bundlers"),
        Entry("tlc-quishing", "QR-code-based phishing"),
        Entry("tlc-vishing", "Voice-call-based social engineering, e.g. impersonating IT/support/an executive"),
        Entry("tlc-smishing", "SMS-based phishing"),
        Entry("tlc-device-code-phishing", "Abusing an OAuth device-authorization flow to harvest tokens via a legitimate authentication page"),
        Entry("tlc-aitm-phishing", "Adversary-in-the-middle phishing kit that intercepts session cookies/tokens to bypass MFA"),
        Entry("tlc-phishing-as-a-service", "Commercial phishing-as-a-service kit or platform sold to affiliates with ready-made lures and infrastructure"),
        Entry("tlc-oauth-consent-phishing", "Malicious OAuth application consent-grant phishing"),
        Entry("tlc-counterfeit-hardware-supply-chain", "Counterfeit or tampered hardware (USB drives, devices) introduced through retail or procurement channels"),
        Entry("tlc-compromised-vendor-script-injection", "Supply-chain compromise of a trusted third-party vendor or script injecting malicious code into an otherwise-legitimate site"),
        Entry("tlc-malicious-mcp-server", "Malicious or trojanized Model Context Protocol server, or AI-agent \"skill\", published to an open repository"),
        Entry("tlc-seeded-repo-compromise", "Trojanized git repository combining legitimate and hidden malicious code, executed during development, build, or CI"),
        Entry("tlc-sim-swap", "Social engineering a telecom/carrier employee or insider to hijack a victim's phone number"),
        Entry("tlc-credential-stuffing", "Automated login attempts using credentials leaked from unrelated breaches"),
        Entry("tlc-password-spraying", "Low-and-slow brute force against exposed accounts/interfaces using common or default credentials"),
        Entry("tlc-exposed-management-interface-exploit", "Direct exploitation of an internet-facing device/appliance management interface (VPN gateway, firewall, hypervisor console) via a known vulnerability or default access"),

        // Execution / Delivery Mechanics
        Entry("tlc-html-smuggling", "Malicious payload assembled or decoded client-side inside HTML/CSS/SVG to evade gateway scanning"),
        Entry("tlc-iso-lnk-smuggling", "Payload delivered via an ISO/archive file or an LNK/VBS file disguised as a document, bypassing mark-of-the-web or email filters"),
        Entry("tlc-macro-document", "Malicious Office macro that executes on document open"),
        Entry("tlc-dll-sideloading", "Malicious DLL loaded via a legitimate signed executable's search-order or plugin mechanism"),
        Entry("tlc-lolbin-abuse", "Abuse of built-in or signed OS admin binaries (PowerShell, mshta, certutil, regsvr32, WMIC, PsExec, etc.) to blend in with normal activity"),
        Entry("tlc-fileless-execution", "Payload decrypted or reflectively loaded and run entirely in memory, never written to disk"),
        Entry("tlc-process-injection", "Injecting and executing code inside another process's memory space"),
        Entry("tlc-process-hollowing", "Replacing a suspended legitimate process's memory with malicious code before resuming it"),
        Entry("tlc-steganography", "Payload or instructions hidden inside an image or other media file"),
        Entry("tlc-polyglot-file", "File crafted to be valid under multiple format interpreters to slip past type-based filtering"),
        Entry("tlc-multistage-obfuscated-loader", "Payload delivered through several chained, independently-obfuscated loader stages"),
        Entry("tlc-payload-obfuscation-packing", "Heavy code obfuscation or packing (junk code, string splitting, custom packers) to defeat static analysis"),
        Entry("tlc-vibe-coded-malware", "Malware or attack tooling visibly generated via LLM prompting, producing unique one-off code that evades signature detection"),
        Entry("tlc-ai-agent-toolset-poisoning", "Malicious or repurposed AI-agent skill/toolset that exfiltrates data or hijacks behavior once installed"),

        // Defense Evasion
        Entry("tlc-amsi-bypass", "In-memory patching or disabling of Windows AMSI to stop script scanning"),
        Entry("tlc-etw-patching", "Disabling or patching Event Tracing for Windows providers to blind logging"),
        Entry("tlc-edr-unhooking", "Unhooking or bypassing EDR/AV API hooks in core system DLLs"),
        Entry("tlc-direct-syscall-evasion", "Issuing direct/raw syscalls, or techniques like Heaven's Gate, to bypass userland API hooks"),
        Entry("tlc-byovd", "Bring-Your-Own-Vulnerable-Driver: loading a signed but exploitable or malicious driver to kill security processes from kernel mode"),
        Entry("tlc-code-signing-abuse", "Using stolen, fraudulently obtained, or improperly issued code-signing/notarization certificates to make malware appear trusted"),
        Entry("tlc-anti-sandbox-detection", "Checking for VM/sandbox/analysis-tool artifacts and aborting execution if found"),
        Entry("tlc-uac-bypass", "Bypassing or silently satisfying a User Account Control elevation prompt"),
        Entry("tlc-edr-av-disabling", "Directly disabling Defender/EDR/AV protections via built-in tooling or policy changes"),
        Entry("tlc-event-log-clearing", "Clearing or tampering with system event logs to remove evidence"),
        Entry("tlc-persistence-mechanism-abuse", "Establishing persistence via scheduled tasks, registry run keys, services, cron, or LaunchAgents"),
        Entry("tlc-symlink-approval-bypass", "Crafting a symlink with a benign display name so an approval/consent dialog shows a harmless target while the real target is sensitive"),

        // Credential Access
        Entry("tlc-lsass-credential-dumping", "Dumping credentials from the LSASS process, e.g. via Mimikatz"),
        Entry("tlc-browser-credential-theft", "Extracting saved passwords, cookies, or autofill data from browsers"),
        Entry("tlc-session-token-theft", "Stealing session cookies/tokens to inherit an authenticated session and bypass MFA"),
        Entry("tlc-keylogging", "Capturing keystrokes to harvest credentials or sensitive typed information"),
        Entry("tlc-clipboard-hijacking", "Monitoring or rewriting clipboard contents, e.g. swapping crypto addresses or stealing pasted secrets"),
        Entry("tlc-crypto-wallet-theft", "Targeting browser-extension or desktop cryptocurrency wallet files/seed phrases"),
        Entry("tlc-mfa-fatigue", "Bombarding a user with repeated MFA push prompts to coerce approval"),
        Entry("tlc-pass-the-hash", "Reusing a captured password hash for authentication without cracking it"),
        Entry("tlc-kerberoasting", "Requesting Kerberos service tickets for offline cracking of service-account passwords"),
        Entry("tlc-dcsync-adcs-abuse", "Abusing AD Certificate Services or DCSync to impersonate a domain controller and pull domain secrets"),
        Entry("tlc-oauth-token-theft", "Stealing OAuth access/refresh tokens from a compromised app or CI integration to reach downstream systems"),
        Entry("tlc-ssh-key-theft", "Stealing SSH private keys from developer or administrative systems"),
        Entry("tlc-cloud-credential-harvesting", "Targeting cloud-provider keys, tokens, or configuration files"),
        Entry("tlc-infostealer-log-marketplace", "Commoditized resale of bulk-harvested infostealer credential \"logs\" on underground markets"),
        Entry("tlc-otp-interception-relay", "Real-time interception or relay of one-time passcodes during authentication"),
        Entry("tlc-default-credential-exploit", "Logging in using default, unchanged, or hardcoded vendor credentials"),

        // Privilege Escalation
        Entry("tlc-kernel-exploit-privesc", "Exploiting a kernel vulnerability to gain SYSTEM/root privileges"),
        Entry("tlc-token-impersonation", "Stealing or reusing an access token to assume a higher-privileged process's identity"),
        Entry("tlc-service-abuse-privesc", "Abusing a privileged service's insecure file-monitoring or update workflow to escalate from a low-privileged user"),
        Entry("tlc-cloud-iam-trust-exploitation", "Exploiting a misconfigured trust relationship between cloud managed identities/roles to escalate privileges or bypass IAM boundaries"),

        // Lateral Movement
        Entry("tlc-rmm-tool-abuse", "Deploying or abusing legitimate remote monitoring & management software for persistent remote access"),
        Entry("tlc-admin-tool-lateral-movement", "Using PsExec, WMI, or RDP with harvested credentials to execute commands across networked systems"),
        Entry("tlc-domain-controller-compromise", "Targeting a domain controller to extract NTDS.dit/credential databases and push domain-wide persistence"),
        Entry("tlc-network-self-propagation", "Malware that spreads autonomously across a network once initial access is gained"),
        Entry("tlc-ldap-ad-reconnaissance", "Discovering Active Directory structure/objects via LDAP queries, often without credentials"),
        Entry("tlc-network-connection-hijacking", "Spoofing or hijacking a network connection, e.g. behind NAT, to intercept or redirect traffic"),

        // Command & Control
        Entry("tlc-c2-framework-abuse", "Deploying a commercial or open-source post-exploitation framework (e.g. Cobalt Strike) for command and control"),
        Entry("tlc-dns-tunneling", "Wrapping C2 traffic inside DNS queries/responses"),
        Entry("tlc-dga-fast-flux-infrastructure", "Domain-generation algorithms or rapid DNS-record rotation producing resilient, hard-to-block C2 infrastructure"),
        Entry("tlc-legitimate-service-c2", "Abusing a trusted SaaS/cloud service (Telegram bots, GitHub raw content, calendar events) as a C2 or dead-drop channel"),
        Entry("tlc-blockchain-c2", "Storing or rotating C2 configuration inside blockchain transactions for resilient, takedown-resistant infrastructure"),
        Entry("tlc-tunneling-service-c2-abuse", "Abusing a tunneling service (ngrok, Cloudflare Tunnel) to expose a C2 endpoint through trusted infrastructure"),
        Entry("tlc-websocket-c2", "Using a WebSocket/Socket.IO channel for persistent, encrypted C2 communication"),
        Entry("tlc-proxy-botnet-network", "Turning compromised devices into a residential-proxy/ORB network resold to mask other actors' traffic"),

        // Exfiltration & Impact
        Entry("tlc-double-extortion", "Combining data theft with encryption and a threat to publicly leak the data"),
        Entry("tlc-data-destruction-wiper", "Purpose-built wiper functionality that destroys data rather than, or in addition to, encrypting it for ransom"),
        Entry("tlc-cloud-storage-exfiltration", "Bulk exfiltration of stolen data to attacker-controlled cloud storage (rclone, S3, Mega, etc.)"),
        Entry("tlc-dns-exfiltration", "Exfiltrating data encoded inside DNS queries"),
        Entry("tlc-web-shell-deployment", "Deploying a backdoored web shell to a compromised server for persistent access"),
        Entry("tlc-ransomware-service-termination", "Killing backup, database, or virtualization services before encryption to prevent recovery"),
        Entry("tlc-ics-ot-process-manipulation", "Malicious logic embedded in ICS/OT project files that overrides safe operating parameters while showing normal values to operators"),
        Entry("tlc-identity-document-theft-fraud", "Bulk theft of identity/KYC documents to enable synthetic-identity or mule-account fraud"),

        // Vulnerability Exploitation Classes
        Entry("tlc-command-injection", "Injecting OS-level commands through insufficient input validation"),
        Entry("tlc-sql-injection", "Injecting SQL through insufficient input validation"),
        Entry("tlc-ssrf", "Server-Side Request Forgery: tricking a server into making requests to internal or restricted resources"),
        Entry("tlc-insecure-deserialization", "Exploiting unsafe deserialization of attacker-controlled data to achieve code execution"),
        Entry("tlc-path-traversal", "Exploiting insufficient path sanitization to read or write files outside an intended directory"),
        Entry("tlc-authentication-bypass", "Circumventing an authentication check via a logic flaw rather than stealing credentials"),
        Entry("tlc-broken-access-control", "Accessing resources or functions that should be restricted, due to a missing or flawed authorization check"),
        Entry("tlc-malicious-file-upload", "Uploading a crafted file that is later executed or parsed unsafely by the server"),
        Entry("tlc-race-condition-exploit", "Exploiting a timing window between check and use to achieve an unintended outcome"),
        Entry("tlc-container-escape", "Breaking out of a container or sandbox boundary to reach the host or other tenants"),
        Entry("tlc-xxe-injection", "XML External Entity injection abusing an XML parser to read files or reach internal resources"),
        Entry("tlc-csrf-exploit", "Cross-Site Request Forgery tricking a victim's browser into making an unwanted authenticated request"),
        Entry("tlc-dom-based-xss", "Client-side script injection via DOM manipulation or HTML-parser inconsistencies that bypass sanitization"),

        // AI / LLM-Specific
        Entry("tlc-prompt-injection-direct", "Malicious instructions submitted directly to an LLM or agent to override its intended behavior"),
        Entry("tlc-prompt-injection-indirect", "Malicious instructions hidden in content an AI agent later reads or browses (web pages, files, images) that hijack its behavior"),
        Entry("tlc-llm-jailbreak", "Systematic techniques (roleplay, context warming, cross-lingual prompts, guardrail-stripped local models) used to bypass an LLM's safety alignment"),
        Entry("tlc-ai-model-data-poisoning", "Poisoning training/fine-tuning data or a RAG knowledge base to implant a triggerable backdoor or bias in model output"),
        Entry("tlc-ai-agent-sandbox-escape", "An AI model or agent exploiting a flaw in its own containment/sandbox to reach systems or data outside its intended boundary"),
        Entry("tlc-agentic-ai-attack-automation", "A largely autonomous, LLM-driven attack chain (recon through impact) run with minimal human step-by-step direction"),
        Entry("tlc-mcp-tool-poisoning", "Malicious or altered MCP tool definitions that redirect an AI agent's data flows or trigger command execution"),
        Entry("tlc-ai-slopsquatting", "Registering package, domain, or repo names that AI coding assistants predictably hallucinate, so agents auto-install or fetch attacker content"),
        Entry("tlc-shadow-ai", "Unauthorized or ungoverned use of AI tools or agents within an organization, outside security visibility"),
        Entry("tlc-non-human-identity-abuse", "Abusing AI-agent or service-account identities (standing access, disabled approval prompts) to bypass identity-based controls"),
        Entry("tlc-ai-generated-attack-content", "Phishing lures, malware, or reconnaissance payloads generated using LLMs to scale or personalize attacks"),
        Entry("tlc-deepfake-social-engineering", "AI-generated deepfake audio/video used in real-time social engineering, e.g. a fake recruiter or meeting impersonation"),
        Entry("tlc-gpu-side-channel-attack", "Extracting co-tenant data or model weights from shared GPU infrastructure via covert hardware channels"),

        // Other
        Entry("tlc-business-email-compromise", "BEC-themed phishing impersonating a trusted party to redirect payments or invoices"),
        Entry("tlc-physical-coercion-extortion", "Physical-world threats or coercion (e.g. a \"wrench attack\") used to force a victim to hand over funds or access"),
        Entry("tlc-insider-threat-collusion", "A trusted insider (employee, contractor, negotiator) secretly aiding attackers for personal gain"),

        // Fallback
        Entry("tlc-unknown", "The article clearly describes a specific attack/defense technique, but none of the catalogue entries above match it")
    )

    val allTags: Set<String> = entries.map { it.tag }.toSet()

    /** Rendered once and reused verbatim by every prompt that embeds it, so prompt caching hits. */
    val promptBlock: String = entries.joinToString("\n") { "- \"${it.tag}\": ${it.description}" }
}
