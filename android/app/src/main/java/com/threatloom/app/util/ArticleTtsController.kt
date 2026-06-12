package com.threatloom.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class TtsState { Idle, Playing, Paused }

/**
 * Compose-scoped wrapper around Android's built-in [TextToSpeech] engine.
 *
 * Recites an ordered list of "utterances" (typically a section title followed by one
 * utterance per bullet/paragraph). Pause stops playback but remembers the index of the
 * utterance that was in progress; resume re-queues from the start of that utterance.
 */
class ArticleTtsController(context: Context) {

    var state by mutableStateOf(TtsState.Idle)
        private set

    var currentTitle by mutableStateOf<String?>(null)
        private set

    private var utterances: List<String> = emptyList()
    private var currentIndex by mutableIntStateOf(0)

    /** True once the engine has initialized and a usable voice is available. */
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            // Honour the voice the user selected in Settings → Text-to-speech.
            // defaultVoice reflects that choice; setLanguage() would ignore it and
            // pick an arbitrary voice for the system locale instead.
            val defaultVoice = tts.defaultVoice
            if (defaultVoice != null) {
                tts.voice = defaultVoice
                ttsReady = true
            } else {
                // No default voice configured — fall back to device locale.
                val langResult = tts.setLanguage(Locale.getDefault())
                ttsReady = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val index = utteranceId?.toIntOrNull() ?: return
                mainHandler.post {
                    currentIndex = index
                    state = TtsState.Playing
                }
            }

            override fun onDone(utteranceId: String?) {
                val index = utteranceId?.toIntOrNull() ?: return
                if (index >= utterances.lastIndex) {
                    mainHandler.post { reset() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { reset() }
            }
        })
    }

    /**
     * Start reciting [utterances], labelled with [title].
     *
     * @return false if text-to-speech is unavailable (engine failed to initialize or no
     * voice is installed), so the caller can surface a message. Returns true otherwise.
     */
    fun speak(title: String, utterances: List<String>): Boolean {
        if (!ttsReady) return false
        if (utterances.isEmpty()) return true
        this.utterances = utterances
        currentTitle = title
        currentIndex = 0
        state = TtsState.Playing
        enqueueFrom(0, flushFirst = true)
        return true
    }

    fun pause() {
        if (state != TtsState.Playing) return
        tts.stop()
        // currentIndex is preserved so resume() restarts from this bullet.
        state = TtsState.Paused
    }

    fun resume() {
        if (state != TtsState.Paused) return
        state = TtsState.Playing
        enqueueFrom(currentIndex, flushFirst = true)
    }

    fun stop() {
        tts.stop()
        reset()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun reset() {
        state = TtsState.Idle
        currentTitle = null
        currentIndex = 0
    }

    private fun enqueueFrom(startIndex: Int, flushFirst: Boolean) {
        for (i in startIndex until utterances.size) {
            val queueMode = if (i == startIndex && flushFirst) {
                TextToSpeech.QUEUE_FLUSH
            } else {
                TextToSpeech.QUEUE_ADD
            }
            tts.speak(utterances[i], queueMode, null, i.toString())
            // Small gap between utterances for more natural pacing between bullets.
            if (i < utterances.lastIndex) {
                tts.playSilentUtterance(150, TextToSpeech.QUEUE_ADD, "silence-$i")
            }
        }
    }
}

@Composable
fun rememberArticleTtsController(): ArticleTtsController {
    val context = LocalContext.current
    val controller = remember { ArticleTtsController(context) }
    DisposableEffect(Unit) {
        onDispose { controller.shutdown() }
    }
    return controller
}

private val BOLD_REGEX = Regex("""\*\*(.*?)\*\*""")
private val LINK_REGEX = Regex("""\[([^\]]+)]\([^)]*\)""")

/**
 * Converts a Markdown section into a list of speakable strings: the title first, then one
 * utterance per bullet, with consecutive non-bullet lines grouped into paragraph utterances.
 */
fun sectionToUtterances(title: String, markdownContent: String): List<String> {
    val result = mutableListOf<String>()
    cleanMarkdown(title).takeIf { it.isNotEmpty() }?.let { result.add(it) }

    val paragraph = StringBuilder()
    fun flushParagraph() {
        val text = cleanMarkdown(paragraph.toString())
        if (text.isNotEmpty()) result.add(text)
        paragraph.clear()
    }

    for (raw in markdownContent.lines()) {
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                cleanMarkdown(trimmed).takeIf { it.isNotEmpty() }?.let { result.add(it) }
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    flushParagraph()

    return result
}

private fun cleanMarkdown(text: String): String {
    var s = text.trim()
    s = s.removePrefix("### ").removePrefix("## ").removePrefix("# ")
    s = s.removePrefix("- ").removePrefix("* ")
    s = LINK_REGEX.replace(s) { it.groupValues[1] }
    s = BOLD_REGEX.replace(s) { it.groupValues[1] }
    s = s.replace("`", "").replace("*", "").replace("#", "")
    return s.trim()
}
