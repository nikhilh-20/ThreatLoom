package com.threatloom.app.util

import javax.inject.Inject
import javax.inject.Singleton

data class BibtexEntry(
    val title: String,
    val date: String?,
    val url: String,
    val author: String?,
    val organization: String?
)

@Singleton
class BibtexParser @Inject constructor() {

    private val entryRegex = Regex("""@\w+\{[^,]+,(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
    private val titleRegex = Regex("""title\s*=\s*\{\{(.+?)\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val dateRegex = Regex("""date\s*=\s*\{(\d{4}-\d{2}-\d{2})\}""")
    private val urlRegex = Regex("""url\s*=\s*\{(.+?)\}""")
    private val authorRegex = Regex("""author\s*=\s*\{(.+?)\}""")
    private val orgRegex = Regex("""organization\s*=\s*\{(.+?)\}""")

    fun parse(text: String): List<BibtexEntry> {
        return entryRegex.findAll(text).mapNotNull { match ->
            val body = match.groupValues[1]
            val url = urlRegex.find(body)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val title = titleRegex.find(body)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val date = dateRegex.find(body)?.groupValues?.get(1)
            val author = authorRegex.find(body)?.groupValues?.get(1)?.trim()
            val org = orgRegex.find(body)?.groupValues?.get(1)?.trim()
            BibtexEntry(title, date, url, author, org)
        }.toList()
    }

    fun formatAuthor(author: String?, organization: String?): String? {
        return when {
            author != null && organization != null -> "$author ($organization)"
            else -> author ?: organization
        }
    }
}
