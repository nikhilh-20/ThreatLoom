package com.threatloom.app.util

import java.text.SimpleDateFormat
import java.util.*

object DateUtils {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val displayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    private val displayFormatWithTime = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)

    fun parseIso(dateStr: String?): Date? {
        if (dateStr == null) return null
        return try {
            // Normalize: SQLite datetime('now') uses space separator; ISO 8601 uses 'T'
            isoFormat.parse(dateStr.replace(" ", "T").replace("Z", "").take(19))
        } catch (e: Exception) {
            null
        }
    }

    fun formatDisplay(dateStr: String?): String {
        val date = parseIso(dateStr) ?: return "Unknown date"
        return displayFormat.format(date)
    }

    fun formatDisplayWithTime(dateStr: String?): String {
        val date = parseIso(dateStr) ?: return "Unknown date"
        return displayFormatWithTime.format(date)
    }

    fun formatIso(date: Date): String = isoFormat.format(date)

    fun nowIso(): String = isoFormat.format(Date())

    fun cutoffIso(lookbackDays: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, -lookbackDays)
        return isoFormat.format(cal.time)
    }

    fun isAfter(dateStr: String?, cutoff: String): Boolean {
        val date = parseIso(dateStr) ?: return false
        val cutoffDate = parseIso(cutoff) ?: return false
        return date.after(cutoffDate)
    }

    fun relativeTime(dateStr: String?): String {
        val date = parseIso(dateStr) ?: return "Unknown"
        val diff = System.currentTimeMillis() - date.time
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> displayFormat.format(date)
        }
    }
}
