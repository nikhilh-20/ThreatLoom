package com.threatloom.app.domain.category

/**
 * 9-category keyword mapping for threat categorization.
 * Ported from database.py _CATEGORY_RULES and associated logic.
 */
object CategoryRules {

    private val CATEGORY_RULES = listOf(
        "Malware" to listOf(
            "malware", "trojan", "backdoor", "infostealer", "info-stealer",
            "stealer", "loader", "dropper", "rootkit", "spyware", "adware",
            "keylogger", "rat", "worm", "cryptominer", "cryptojack", "miner",
            "ransomware", "lockbit", "blackcat", "alphv", "clop", "cl0p", "revil",
            "conti", "hive", "akira", "play", "medusa", "rhysida", "blackbasta",
            "black basta", "royal", "phobos", "babuk", "ragnar", "vice society",
            "bianlian", "8base", "noescape", "cactus", "hunters international",
            "emotet", "qakbot", "qbot", "trickbot", "icedid", "bumblebee",
            "pikabot", "darkgate", "asyncrat", "remcos", "redline", "raccoon",
            "vidar", "lumma", "stealc", "amadey", "smokeloader"
        ),
        "Vulnerabilities" to listOf(
            "vulnerability", "cve", "zero-day", "0-day", "0day", "exploit",
            "rce", "remote-code-execution", "buffer-overflow", "use-after-free",
            "deserialization", "proof-of-concept", "poc", "patch", "security-update",
            "security-flaw", "privilege-escalation", "code-execution"
        ),
        "Threat Actors" to listOf(
            "apt", "threat-actor", "nation-state", "cyber-espionage", "espionage",
            "campaign", "lazarus", "lazarus-group", "apt29", "apt28", "cozy-bear",
            "fancy-bear", "turla", "sandworm", "kimsuky", "mustang-panda",
            "charming-kitten", "hafnium", "nobelium", "volt-typhoon",
            "salt-typhoon", "scattered-spider", "lapsus"
        ),
        "Data Leaks" to listOf(
            "data-leak", "data-breach", "breach", "data-exposure",
            "data leak", "data breach", "data exposure", "exfiltration"
        ),
        "Phishing & Social Engineering" to listOf(
            "phishing", "spear-phishing", "social-engineering", "smishing",
            "vishing", "business-email-compromise", "bec", "credential-stuffing",
            "credential-theft"
        ),
        "Supply Chain" to listOf(
            "supply-chain", "supply chain", "dependency-confusion",
            "typosquatting", "malicious-package", "npm", "pypi"
        ),
        "Botnet & DDoS" to listOf(
            "botnet", "ddos", "dos", "mirai"
        ),
        "C2 & Offensive Tooling" to listOf(
            "c2", "command-and-control", "cobalt-strike", "metasploit",
            "sliver", "brute-ratel", "havoc", "mythic", "implant"
        ),
        "IoT & Hardware" to listOf(
            "iot", "firmware", "hardware", "embedded", "scada", "ics",
            "ot-security", "industrial"
        ),
    )

    private val EXTRA_THREAT_ACTORS = mapOf(
        "lapsus" to "LAPSUS$",
        "lazarus" to "Lazarus Group",
    )

    private val EXTRA_SOFTWARE = mapOf(
        "lockbit" to "LockBit", "redline" to "RedLine Stealer",
        "raccoon" to "Raccoon Stealer", "lumma" to "Lumma Stealer",
        "smokeloader" to "SmokeLoader", "cl0p" to "Clop",
        "blackbasta" to "Black Basta", "ragnar" to "Ragnar Locker",
        "hive" to "Hive", "rhysida" to "Rhysida", "phobos" to "Phobos",
        "vice-society" to "Vice Society", "bianlian" to "BianLian",
        "8base" to "8Base", "noescape" to "NoEscape", "cactus" to "Cactus",
        "hunters-international" to "Hunters International", "stealc" to "StealC",
        "vidar" to "Vidar", "phorpiex" to "Phorpiex",
        "globeimposter" to "GlobeImposter", "medusalocker" to "MedusaLocker",
        "trigona" to "Trigona", "snatch" to "Snatch", "mallox" to "Mallox",
        "fog" to "Fog", "interlock" to "Interlock",
    )

    val KNOWN_ENTITIES: Map<String, Map<String, String>> = mapOf(
        "Threat Actors" to (MitreData.KNOWN_THREAT_ACTORS + EXTRA_THREAT_ACTORS),
        "Malware" to (MitreData.KNOWN_SOFTWARE + EXTRA_SOFTWARE),
        "C2 & Offensive Tooling" to (MitreData.KNOWN_SOFTWARE + EXTRA_SOFTWARE),
    )

    private val VERSION_SUFFIX_REGEX = Regex("""[-_.\s]*(v?\d+(\.\d+)?|_v\d+)\s*$""", RegexOption.IGNORE_CASE)

    fun tagToCategory(tag: String): String? {
        val tagLower = tag.trim().lowercase()
        val parts = tagLower.split("-")
        for ((categoryName, keywords) in CATEGORY_RULES) {
            for (kw in keywords) {
                if (kw.length <= 3) {
                    if (tagLower == kw || kw in parts) return categoryName
                    if (tagLower.startsWith(kw) && tagLower.substring(kw.length).all { it.isDigit() } && tagLower.length > kw.length) {
                        return categoryName
                    }
                } else {
                    if (kw in tagLower || tagLower in kw) return categoryName
                }
            }
        }
        // Fallback: check MITRE ATT&CK known entities
        if (tagLower in KNOWN_ENTITIES.getOrDefault("Threat Actors", emptyMap())) return "Threat Actors"
        if (tagLower in KNOWN_ENTITIES.getOrDefault("Malware", emptyMap())) return "Malware"
        return null
    }

    fun canonicalEntityTag(tagLower: String, categoryName: String): String {
        val entities = KNOWN_ENTITIES[categoryName] ?: return tagLower

        val m = VERSION_SUFFIX_REGEX.find(tagLower)
        if (m != null) {
            val base = tagLower.substring(0, m.range.first).trimEnd('-', '_', '.', ' ')
            if (base.isNotEmpty() && base != tagLower && base in entities) return base
        }

        if (tagLower in entities) {
            val display = entities[tagLower]!!
            val m2 = VERSION_SUFFIX_REGEX.find(display)
            if (m2 != null) {
                val baseDisplay = display.substring(0, m2.range.first).trim()
                if (baseDisplay.isNotEmpty() && baseDisplay != display) {
                    val baseTag = baseDisplay.lowercase().replace(" ", "-")
                    if (baseTag in entities) return baseTag
                }
            }
        }

        return tagLower
    }

    fun isGenericTag(tag: String, categoryName: String): Boolean {
        val entities = KNOWN_ENTITIES[categoryName] ?: return true
        val tagLower = tag.trim().lowercase()
        if (tagLower in entities) return false
        val canonical = canonicalEntityTag(tagLower, categoryName)
        return canonical == tagLower
    }

    fun formatEntityName(tag: String): String {
        val key = tag.trim().lowercase()
        for (entities in KNOWN_ENTITIES.values) {
            if (key in entities) return entities[key]!!
        }
        return tag.replace("-", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    val ALL_CATEGORIES: List<String> = CATEGORY_RULES.map { it.first }
}
