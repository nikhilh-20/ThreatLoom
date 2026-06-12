package com.threatloom.app.util

import net.dankito.readability4j.Readability4J
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HtmlTextExtractor @Inject constructor() {

    fun extract(url: String, html: String): String? {
        return try {
            // Strip script/style tags before readability processing — many sites embed
            // inline JS (ad loaders, newsletter forms) directly inside <article>, causing
            // Readability4J's textContent to include minified JS in the output.
            val cleaned = SCRIPT_RE.replace(html, "").let { STYLE_RE.replace(it, "") }
            val readability = Readability4J(url, cleaned)
            val article = readability.parse()
            val text = article.textContent?.trim()
            if (text != null && text.length > 100 && !looksBinary(text)) text else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Guards against feeding binary/mis-decoded content (e.g. an undecompressed gzip body
     * read as UTF-8) to the LLM. Returns true if the text has a high share of replacement
     * chars (U+FFFD) or non-printable control bytes (excluding \t, \n, \r).
     */
    private fun looksBinary(text: String): Boolean {
        val sample = if (text.length > 4000) text.substring(0, 4000) else text
        if (sample.isEmpty()) return false
        val bad = sample.count { c ->
            c == '�' || (c.code != 9 && c.code != 10 && c.code != 13 &&
                (c.code in 0..31 || c.code == 127))
        }
        return bad.toDouble() / sample.length > 0.05
    }

    companion object {
        private val SCRIPT_RE = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
        private val STYLE_RE = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
    }
}
