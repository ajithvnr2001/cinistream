package com.example.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SecondarySubtitleTrack(
    val label: String,
    val sourceUri: String,
    val cues: List<SubtitleCue>
)

/**
 * Pure SRT & WebVTT parser and binary-search cue lookup for Dual Subtitles (synchronized secondary
 * caption track rendered cleanly at the top of the screen alongside ExoPlayer's primary captions).
 */
object SecondarySubtitleEngine {

    private val TIME_LINE_REGEX = Regex(
        """(\d{1,2}:)?(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}:)?(\d{2}):(\d{2})[,.](\d{3})"""
    )
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val ASS_TAG_REGEX = Regex("""\{[^}]+\}""")

    fun parseSrtOrVtt(rawContent: String): List<SubtitleCue> {
        if (rawContent.isBlank()) return emptyList()
        val normalized = rawContent.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        val cues = ArrayList<SubtitleCue>(256)
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val match = TIME_LINE_REGEX.find(line)
            if (match != null) {
                val startMs = parseTimestampMs(
                    hours = match.groupValues[1],
                    minutes = match.groupValues[2],
                    seconds = match.groupValues[3],
                    millis = match.groupValues[4]
                )
                val endMs = parseTimestampMs(
                    hours = match.groupValues[5],
                    minutes = match.groupValues[6],
                    seconds = match.groupValues[7],
                    millis = match.groupValues[8]
                )
                i++
                val textLines = ArrayList<String>(3)
                while (i < lines.size && lines[i].isNotBlank()) {
                    val cleaned = lines[i]
                        .replace(HTML_TAG_REGEX, "")
                        .replace(ASS_TAG_REGEX, "")
                        .trim()
                    if (cleaned.isNotEmpty()) textLines.add(cleaned)
                    i++
                }
                if (textLines.isNotEmpty() && endMs > startMs) {
                    cues.add(SubtitleCue(startMs, endMs, textLines.joinToString("\n")))
                }
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Binary-searches the sorted cue list for any cue active at [positionMs] (applying [offsetMs]).
     */
    fun findActiveCueText(cues: List<SubtitleCue>, positionMs: Long, offsetMs: Long = 0L): String? {
        if (cues.isEmpty()) return null
        val adjusted = (positionMs - offsetMs).coerceAtLeast(0L)
        var low = 0
        var high = cues.lastIndex
        var candidateIdx = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= adjusted) {
                candidateIdx = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidateIdx < 0) return null
        // Check a small neighborhood backwards for overlapping cues
        val active = mutableListOf<String>()
        val startCheck = (candidateIdx - 3).coerceAtLeast(0)
        for (idx in startCheck..candidateIdx) {
            val cue = cues[idx]
            if (adjusted in cue.startMs..cue.endMs) {
                active.add(cue.text)
            }
        }
        return active.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    suspend fun loadTrack(
        context: Context,
        okHttpClient: OkHttpClient,
        uriString: String,
        label: String
    ): SecondarySubtitleTrack? = withContext(Dispatchers.IO) {
        val raw = runCatching {
            when {
                uriString.startsWith("http://", true) || uriString.startsWith("https://", true) -> {
                    val req = Request.Builder()
                        .url(uriString)
                        .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.string()
                    }
                }
                uriString.startsWith("file://", true) -> {
                    Uri.parse(uriString).path?.let { File(it) }?.takeIf { it.exists() }?.readText()
                }
                else -> {
                    context.contentResolver.openInputStream(Uri.parse(uriString))
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }
            }
        }.getOrNull() ?: return@withContext null

        val cues = parseSrtOrVtt(raw)
        if (cues.isEmpty()) null else SecondarySubtitleTrack(label = label, sourceUri = uriString, cues = cues)
    }

    private fun parseTimestampMs(hours: String, minutes: String, seconds: String, millis: String): Long {
        val h = hours.trimEnd(':').toLongOrNull() ?: 0L
        val m = minutes.toLongOrNull() ?: 0L
        val s = seconds.toLongOrNull() ?: 0L
        val ms = millis.toLongOrNull() ?: 0L
        return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
    }
}
