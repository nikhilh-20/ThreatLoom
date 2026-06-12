package com.threatloom.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class LogLevel(val label: String) {
    DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
}

@Singleton
class AppLogger @Inject constructor() {

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, msg: String) { android.util.Log.d(tag, msg); append(LogLevel.DEBUG, tag, msg) }
    fun i(tag: String, msg: String) { android.util.Log.i(tag, msg); append(LogLevel.INFO, tag, msg) }
    fun w(tag: String, msg: String) { android.util.Log.w(tag, msg); append(LogLevel.WARN, tag, msg) }
    fun e(tag: String, msg: String) { android.util.Log.e(tag, msg); append(LogLevel.ERROR, tag, msg) }
    fun e(tag: String, msg: String, t: Throwable) {
        android.util.Log.e(tag, msg, t)
        append(LogLevel.ERROR, tag, "$msg: ${t.message}")
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private fun append(level: LogLevel, tag: String, msg: String) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(LogEntry(level, tag, msg))
            _entries.value = buffer.toList()
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
