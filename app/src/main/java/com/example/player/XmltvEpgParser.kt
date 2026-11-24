package com.example.player

import android.util.Xml
import com.example.model.ChannelEpgNowNext
import com.example.model.EpgProgram
import com.example.model.M3uEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV (.xml and .xml.gz) EPG parser and Now/Next schedule resolver for Live TV M3U playlists.
 */
class XmltvEpgParser(private val okHttpClient: OkHttpClient) {

    companion object {
        private const val MAX_PROGRAMS_CACHED = 12_000

        /**
         * Parses XMLTV timestamp formats like `20261123210000 +0530` or `20261123210000`.
         */
        fun parseXmltvTimeMs(raw: String?): Long {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.length < 12) return 0L
            val patterns = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmssZ", "yyyyMMddHHmmss", "yyyyMMddHHmm")
            for (pattern in patterns) {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = runCatching { sdf.parse(cleaned)?.time }.getOrNull()
                if (parsed != null && parsed > 0L) return parsed
            }
            return 0L
        }

        fun normalizeKey(raw: String): String =
            raw.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

        fun parseXml(inputStream: InputStream, nowMs: Long = System.currentTimeMillis()): Map<String, List<EpgProgram>> {
            val windowStart = nowMs - 6 * 3600_000L
            val windowEnd = nowMs + 24 * 3600_000L
            val channelDisplayNames = HashMap<String, MutableList<String>>()
            val programsByKey = HashMap<String, MutableList<EpgProgram>>()

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var currentChannelId: String? = null
            var inProgramme = false
            var progChannel = ""
            var progStart = 0L
            var progEnd = 0L
            var progTitle = ""
            var progDesc = ""
            var totalParsed = 0

            while (eventType != XmlPullParser.END_DOCUMENT && totalParsed < MAX_PROGRAMS_CACHED) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name?.lowercase(Locale.US)) {
                            "channel" -> {
                                currentChannelId = parser.getAttributeValue(null, "id")?.trim()
                            }
                            "display-name" -> {
                                val cid = currentChannelId
                                if (!cid.isNullOrBlank()) {
                                    val display = runCatching { parser.nextText().trim() }.getOrDefault("")
                                    if (display.isNotBlank()) {
                                        channelDisplayNames.getOrPut(cid) { mutableListOf() }.add(display)
                                    }
                                }
                            }
                            "programme" -> {
                                inProgramme = true
                                progChannel = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
                                progStart = parseXmltvTimeMs(parser.getAttributeValue(null, "start"))
                                progEnd = parseXmltvTimeMs(parser.getAttributeValue(null, "stop"))
                                progTitle = ""
                                progDesc = ""
                            }
                            "title" -> if (inProgramme && progTitle.isBlank()) {
                                progTitle = runCatching { parser.nextText().trim() }.getOrDefault("")
                            }
                            "desc" -> if (inProgramme && progDesc.isBlank()) {
                                progDesc = runCatching { parser.nextText().trim() }.getOrDefault("")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name?.lowercase(Locale.US)) {
                            "channel" -> currentChannelId = null
                            "programme" -> {
                                if (inProgramme && progChannel.isNotBlank() && progTitle.isNotBlank() &&
                                    progEnd > progStart && progEnd >= windowStart && progStart <= windowEnd
                                ) {
                                    val program = EpgProgram(
                                        channelId = progChannel,
                                        title = progTitle,
                                        description = progDesc,
                                        startMs = progStart,
                                        endMs = progEnd
                                    )
                                    val key = normalizeKey(progChannel)
                                    programsByKey.getOrPut(key) { mutableListOf() }.add(program)
                                    channelDisplayNames[progChannel]?.forEach { name ->
                                        val nameKey = normalizeKey(name)
                                        if (nameKey.isNotBlank() && nameKey != key) {
                                            programsByKey.getOrPut(nameKey) { mutableListOf() }.add(program)
                                        }
                                    }
                                    totalParsed++
                                }
                                inProgramme = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            return programsByKey.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        }

        fun parseXmlString(xml: String, nowMs: Long = System.currentTimeMillis()): Map<String, List<EpgProgram>> =
            parseXml(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), nowMs)

        fun resolveNowNext(
            epgIndex: Map<String, List<EpgProgram>>,
            entry: M3uEntry,
            nowMs: Long = System.currentTimeMillis()
        ): ChannelEpgNowNext? {
            if (epgIndex.isEmpty()) return null
            val candidates = listOfNotNull(entry.tvgId, entry.title)
                .map { normalizeKey(it) }
                .filter { it.isNotBlank() }
            val programs = candidates.firstNotNullOfOrNull { epgIndex[it] } ?: return null
            val now = programs.firstOrNull { nowMs in it.startMs until it.endMs }
            val next = if (now != null) {
                programs.firstOrNull { it.startMs >= now.endMs }
            } else {
                programs.firstOrNull { it.startMs > nowMs }
            }
            return if (now != null || next != null) ChannelEpgNowNext(now = now, next = next) else null
        }
    }

    suspend fun fetchAndParse(epgUrl: String): Result<Map<String, List<EpgProgram>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = epgUrl.trim()
                require(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                    "EPG URL must start with http:// or https://"
                }
                val request = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("EPG HTTP ${response.code}")
                    val body = response.body ?: error("Empty EPG response")
                    val rawStream = body.byteStream()
                    val isGzip = trimmed.endsWith(".gz", ignoreCase = true) ||
                        response.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true ||
                        response.header("Content-Type")?.contains("gzip", ignoreCase = true) == true
                    val stream = if (isGzip) GZIPInputStream(rawStream) else rawStream
                    stream.use { parseXml(it) }
                }
            }
        }
}
