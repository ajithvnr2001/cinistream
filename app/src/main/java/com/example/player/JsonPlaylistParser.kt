package com.example.player

import android.content.Context
import android.net.Uri
import com.example.model.JsonPlaylistItem
import com.example.model.StreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID

sealed class JsonPlaylistLoadResult {
    data class Success(
        val items: List<JsonPlaylistItem>,
        val sourceTitle: String?
    ) : JsonPlaylistLoadResult()

    data class Error(val message: String) : JsonPlaylistLoadResult()
}

/**
 * Parses JSON playlists from HTTP/HTTPS URLs or local content:// / file:// files.
 *
 * Supports all common JSON playlist shapes:
 *  - Top-level array: `[ { "title": "...", "url": "...", "imdbId": "tt...", "image": "...", "subtitle": "..." } ]`
 *  - Wrapped object: `{ "title": "...", "items" / "movies" / "videos" / "playlist" / "channels" / "streams" / "data" / "results": [...] }`
 *  - Categorized object: `{ "categories": [ { "name": "Action", "videos": [...] } ] }`
 */
class JsonPlaylistParser(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    suspend fun loadFromUrl(url: String): JsonPlaylistLoadResult = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext JsonPlaylistLoadResult.Error("JSON playlist URL must start with http:// or https://")
        }

        val raw = try {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext JsonPlaylistLoadResult.Error(
                        "Failed to load JSON playlist (HTTP ${response.code})"
                    )
                }
                val body = response.body ?: return@withContext JsonPlaylistLoadResult.Error(
                    "Empty response from JSON playlist URL"
                )
                val source = body.source()
                // Cap at 4 MB and read directly without cloning the okio buffer
                source.request(4_194_304L)
                val toRead = minOf(source.buffer.size, 4_194_304L)
                source.readByteArray(toRead).toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return@withContext JsonPlaylistLoadResult.Error(
                e.localizedMessage ?: "Network error while loading JSON playlist"
            )
        }

        parseJson(raw, fallbackSourceTitle = MediaFormatDetector.inferTitle(trimmed))
    }

    suspend fun loadFromLocalUri(uri: Uri): JsonPlaylistLoadResult = withContext(Dispatchers.IO) {
        val raw = runCatching {
            val stream = when (uri.scheme) {
                "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
                else -> context.contentResolver.openInputStream(uri)
            }
            stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val maxChars = 4_194_304
                    val sb = StringBuilder()
                    val buf = CharArray(8_192)
                    var total = 0
                    while (total < maxChars) {
                        val n = reader.read(buf, 0, minOf(buf.size, maxChars - total))
                        if (n <= 0) break
                        sb.append(buf, 0, n)
                        total += n
                    }
                    sb.toString()
                }
            }
        }.getOrNull()

        if (raw.isNullOrBlank()) {
            return@withContext JsonPlaylistLoadResult.Error("Could not read JSON playlist file.")
        }

        parseJson(raw, fallbackSourceTitle = "Local JSON Playlist")
    }

    fun parseJson(raw: String, fallbackSourceTitle: String? = null): JsonPlaylistLoadResult {
        val clean = raw.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (clean.isBlank()) {
            return JsonPlaylistLoadResult.Error("JSON playlist is empty.")
        }

        val root = runCatching { JSONTokener(clean).nextValue() }.getOrElse { err ->
            return JsonPlaylistLoadResult.Error("Invalid JSON format: ${err.localizedMessage ?: "parse error"}")
        }

        val collected = mutableListOf<JsonPlaylistItem>()
        var playlistTitle: String? = fallbackSourceTitle

        when (root) {
            is JSONArray -> {
                collectArrayItems(root, defaultGroup = "Movies", target = collected)
            }
            is JSONObject -> {
                playlistTitle = root.optFirstString("name", "title", "playlistName", "playlist_title")
                    ?: fallbackSourceTitle

                val categories = root.optJSONArray("categories")
                if (categories != null) {
                    for (i in 0 until categories.length()) {
                        val cat = categories.optJSONObject(i) ?: continue
                        val groupName = cat.optFirstString("name", "title", "category", "genre") ?: "Movies"
                        val catArray = findFirstArray(cat)
                        if (catArray != null) {
                            collectArrayItems(catArray, defaultGroup = groupName, target = collected)
                        }
                    }
                }

                val mainArray = findFirstArray(root)
                if (mainArray != null) {
                    collectArrayItems(mainArray, defaultGroup = "Movies", target = collected)
                }

                // Fallback: single-item JSON object directly describing a playable stream
                if (collected.isEmpty()) {
                    parseItemObject(root, defaultGroup = "Movies")?.let { collected.add(it) }
                }
            }
            else -> {
                return JsonPlaylistLoadResult.Error("JSON root must be an array or playlist object.")
            }
        }

        if (collected.isEmpty()) {
            return JsonPlaylistLoadResult.Error("No playable video entries (url/streamUrl/sources) found in JSON.")
        }

        val deduped = collected.distinctBy { it.url }
        return JsonPlaylistLoadResult.Success(
            items = deduped,
            sourceTitle = playlistTitle
        )
    }

    private fun findFirstArray(obj: JSONObject): JSONArray? {
        val preferredKeys = listOf(
            "items",
            "movies",
            "videos",
            "playlist",
            "playlists",
            "channels",
            "streams",
            "media",
            "entries",
            "results",
            "data",
            "catalog",
            "tracks"
        )
        for (key in preferredKeys) {
            obj.optJSONArray(key)?.let { return it }
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "categories") continue
            obj.optJSONArray(k)?.let { arr ->
                if (arr.length() > 0 && arr.optJSONObject(0) != null) return arr
            }
        }
        return null
    }

    private fun collectArrayItems(
        array: JSONArray,
        defaultGroup: String,
        target: MutableList<JsonPlaylistItem>
    ) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseItemObject(obj, defaultGroup)?.let { target.add(it) }
        }
    }

    private fun parseItemObject(obj: JSONObject, defaultGroup: String): JsonPlaylistItem? {
        val streamUrl = extractStreamUrl(obj) ?: return null
        if (!MediaFormatDetector.isValidMediaLink(streamUrl)) return null

        val rawTitle = obj.optFirstString("title", "name", "label", "movieName", "movie_name", "channel")
        val title = rawTitle?.takeIf { it.isNotBlank() } ?: MediaFormatDetector.inferTitle(streamUrl)

        val formatHint = obj.optFirstString("format", "type", "mimeType", "mime_type")
        val format = resolveFormat(formatHint, streamUrl)

        val group = extractGroup(obj) ?: defaultGroup
        val imageUrl = extractImageUrl(obj)
        val imdbRaw = obj.optFirstString(
            "imdbId", "imdb_id", "imdb", "imdbUrl", "imdb_url", "imdbLink", "imdb_link", "tconst", "tt"
        )
        val imdbId = ImdbMetadataClient.extractImdbId(imdbRaw)
            ?: ImdbMetadataClient.extractImdbId(obj.optFirstString("id"))

        val rating = extractRating(obj)
        val year = obj.optFirstString("year", "releaseYear", "release_year", "datePublished")
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value ?: it }
        val description = obj.optFirstString("description", "plot", "overview", "synopsis", "summary")
        val subtitlePair = extractSubtitle(obj)

        return JsonPlaylistItem(
            id = obj.optFirstString("id") ?: UUID.randomUUID().toString(),
            title = title,
            url = streamUrl,
            groupTitle = group,
            format = format,
            imageUrl = imageUrl,
            imdbId = imdbId,
            imdbRating = rating,
            releaseYear = year,
            description = description,
            subtitleUrl = subtitlePair?.first,
            subtitleLabel = subtitlePair?.second
        )
    }

    private fun extractStreamUrl(obj: JSONObject): String? {
        val direct = obj.optFirstString(
            "url", "streamUrl", "stream_url", "videoUrl", "video_url",
            "src", "source", "link", "hls", "stream", "file", "playUrl", "play_url"
        )
        if (!direct.isNullOrBlank()) return direct.trim()

        val sourcesArray = obj.optJSONArray("sources") ?: obj.optJSONArray("streams")
        if (sourcesArray != null) {
            for (i in 0 until sourcesArray.length()) {
                when (val entry = sourcesArray.opt(i)) {
                    is String -> if (entry.isNotBlank()) return entry.trim()
                    is JSONObject -> {
                        val candidate = entry.optFirstString("url", "src", "file", "streamUrl", "source")
                        if (!candidate.isNullOrBlank()) return candidate.trim()
                    }
                }
            }
        }
        return null
    }

    private fun extractImageUrl(obj: JSONObject): String? {
        val direct = obj.optFirstString(
            "image", "poster", "posterUrl", "poster_url", "imageUrl", "image_url",
            "thumbnail", "thumb", "cover", "artwork", "banner", "logo",
            "tvgLogo", "tvg_logo", "tvg-logo", "backdrop", "img"
        )
        val nestedImdbImage = obj.optJSONObject("i")?.optFirstString("imageUrl")
            ?: obj.optJSONObject("poster")?.optFirstString("url", "imageUrl")
            ?: obj.optJSONObject("image")?.optFirstString("url")

        val chosen = direct ?: nestedImdbImage ?: return null
        val trimmed = chosen.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> null
        }
    }

    private fun extractSubtitle(obj: JSONObject): Pair<String, String?>? {
        val directUrl = obj.optFirstString(
            "subtitle", "subtitleUrl", "subtitle_url", "captions", "captionUrl", "srt", "vtt"
        )
        if (!directUrl.isNullOrBlank() && MediaFormatDetector.isValidMediaLink(directUrl)) {
            return directUrl.trim() to obj.optFirstString("subtitleLabel", "subtitleLang", "lang")
        }

        val subsArray = obj.optJSONArray("subtitles") ?: obj.optJSONArray("captions") ?: obj.optJSONArray("tracks")
        if (subsArray != null) {
            for (i in 0 until subsArray.length()) {
                when (val entry = subsArray.opt(i)) {
                    is String -> if (MediaFormatDetector.isValidMediaLink(entry)) return entry.trim() to null
                    is JSONObject -> {
                        val subUrl = entry.optFirstString("url", "src", "file", "subtitleUrl")
                        if (!subUrl.isNullOrBlank() && MediaFormatDetector.isValidMediaLink(subUrl)) {
                            val label = entry.optFirstString("label", "name", "lang", "language")
                            return subUrl.trim() to label
                        }
                    }
                }
            }
        }

        val subObj = obj.optJSONObject("subtitle") ?: obj.optJSONObject("subtitles")
        if (subObj != null) {
            val subUrl = subObj.optFirstString("url", "src", "file", "en", "english")
            if (!subUrl.isNullOrBlank() && MediaFormatDetector.isValidMediaLink(subUrl)) {
                return subUrl.trim() to subObj.optFirstString("label", "lang")
            }
        }
        return null
    }

    private fun extractGroup(obj: JSONObject): String? {
        obj.optFirstString("group", "groupTitle", "group_title", "group-title", "category", "genre")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val genres = obj.optJSONArray("genres") ?: obj.optJSONArray("genre")
        if (genres != null && genres.length() > 0) {
            val list = (0 until minOf(2, genres.length()))
                .mapNotNull { genres.optString(it).takeIf { g -> g.isNotBlank() } }
            if (list.isNotEmpty()) return list.joinToString(" · ")
        }
        return null
    }

    private fun extractRating(obj: JSONObject): String? {
        val rawNum = obj.optFirstString("imdbRating", "imdb_rating", "rating", "score")
        if (!rawNum.isNullOrBlank()) {
            val cleaned = rawNum.trim()
            if (cleaned.equals("N/A", ignoreCase = true) || cleaned == "0" || cleaned == "0.0") {
                return null
            }
            return cleaned
        }
        val nested = obj.optJSONObject("imdb")?.optFirstString("rating", "ratingValue")
            ?: obj.optJSONObject("aggregateRating")?.optFirstString("ratingValue")
        return nested?.takeIf { it.isNotBlank() }
    }

    private fun resolveFormat(hint: String?, url: String): StreamFormat {
        val normalized = hint?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            normalized.contains("m3u8") -> StreamFormat.M3U8
            normalized.contains("hls") || normalized.contains("mpegurl") -> StreamFormat.HLS
            normalized.contains("mkv") || normalized.contains("matroska") -> StreamFormat.MKV
            normalized.contains("mp4") -> StreamFormat.MP4
            normalized.contains("dash") || normalized.contains("mpd") -> StreamFormat.DASH
            normalized.contains("webm") -> StreamFormat.WEBM
            normalized == "m3u" -> StreamFormat.M3U
            else -> MediaFormatDetector.detectFormat(url)
        }
    }

    private fun JSONObject.optFirstString(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is Number) return value.toString()
        }
        return null
    }
}
