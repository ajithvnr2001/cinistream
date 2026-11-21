package com.example.player

import android.content.Context
import android.net.Uri
import com.example.model.JsonPlaylistItem
import com.example.model.MediaContentType
import com.example.model.SeriesEpisode
import com.example.model.SeriesSeason
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
        val sourceTitle: String?,
        val playlistCdnMirrors: List<String> = emptyList()
    ) : JsonPlaylistLoadResult()

    data class Error(val message: String) : JsonPlaylistLoadResult()
}

/**
 * Parses JSON playlists from HTTP/HTTPS URLs or local content:// / file:// files.
 *
 * Supports unified Movie + Series catalogs and CDN failover fields:
 *  - Top-level array: `[ { "title": "...", "url": "..." }, { "type": "series", "seasons": [...] } ]`
 *  - Wrapped object: `{ "title": "...", "cdnMirrors": [...], "headers": {...}, "items": [...], "movies": [...], "series": [...] }`
 *  - Categorized object: `{ "categories": [ { "name": "Action", "videos": [...] } ] }`
 *  - Series shapes: nested `"seasons": [ { "season": 1, "episodes": [...] } ]` OR flat `"episodes": [...]`
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
        var playlistCdnMirrors: List<String> = emptyList()

        when (root) {
            is JSONArray -> {
                collectArrayItems(
                    array = root,
                    defaultGroup = "Movies",
                    defaultTypeHint = null,
                    playlistMirrors = emptyList(),
                    playlistHeaders = emptyMap(),
                    target = collected
                )
            }
            is JSONObject -> {
                playlistTitle = root.optFirstString("name", "title", "playlistName", "playlist_title")
                    ?: fallbackSourceTitle
                playlistCdnMirrors = extractStringList(
                    root,
                    "cdnMirrors", "cdn_mirrors", "mirrorHosts", "fallbackHosts", "cdnBases"
                )
                val playlistHeaders = extractHeaders(root)

                val categories = root.optJSONArray("categories")
                if (categories != null) {
                    for (i in 0 until categories.length()) {
                        val cat = categories.optJSONObject(i) ?: continue
                        val groupName = cat.optFirstString("name", "title", "category", "genre") ?: "Movies"
                        val catArrays = findCatalogArrays(cat)
                        for ((arr, hint) in catArrays) {
                            collectArrayItems(
                                array = arr,
                                defaultGroup = groupName,
                                defaultTypeHint = hint,
                                playlistMirrors = playlistCdnMirrors,
                                playlistHeaders = playlistHeaders,
                                target = collected
                            )
                        }
                    }
                }

                // Collect ALL catalog arrays (supports combined "movies" + "series" + "items" in one JSON!)
                val rootArrays = findCatalogArrays(root)
                for ((arr, hint) in rootArrays) {
                    val defaultGroup = if (hint == MediaContentType.SERIES) "Series" else "Movies"
                    collectArrayItems(
                        array = arr,
                        defaultGroup = defaultGroup,
                        defaultTypeHint = hint,
                        playlistMirrors = playlistCdnMirrors,
                        playlistHeaders = playlistHeaders,
                        target = collected
                    )
                }

                // Fallback: single-item JSON object directly describing a playable movie or series
                if (collected.isEmpty()) {
                    parseItemObject(
                        obj = root,
                        defaultGroup = "Movies",
                        defaultTypeHint = null,
                        playlistMirrors = playlistCdnMirrors,
                        playlistHeaders = playlistHeaders
                    )?.let { collected.add(it) }
                }
            }
            else -> {
                return JsonPlaylistLoadResult.Error("JSON root must be an array or playlist object.")
            }
        }

        if (collected.isEmpty()) {
            return JsonPlaylistLoadResult.Error("No playable movies or series found in JSON playlist.")
        }

        val deduped = collected.distinctBy { item ->
            if (item.isSeries) "series::${item.id}" else "movie::${item.url}"
        }
        return JsonPlaylistLoadResult.Success(
            items = deduped,
            sourceTitle = playlistTitle,
            playlistCdnMirrors = playlistCdnMirrors
        )
    }

    private fun findCatalogArrays(obj: JSONObject): List<Pair<JSONArray, MediaContentType?>> {
        val result = mutableListOf<Pair<JSONArray, MediaContentType?>>()
        val consumedKeys = mutableSetOf("categories", "cdnMirrors", "cdn_mirrors", "mirrorHosts", "fallbackHosts", "cdnBases")

        val preferredKeys = listOf(
            "items" to null,
            "movies" to MediaContentType.MOVIE,
            "series" to MediaContentType.SERIES,
            "shows" to MediaContentType.SERIES,
            "tvShows" to MediaContentType.SERIES,
            "tv_shows" to MediaContentType.SERIES,
            "videos" to null,
            "playlist" to null,
            "playlists" to null,
            "channels" to null,
            "streams" to null,
            "media" to null,
            "entries" to null,
            "results" to null,
            "data" to null,
            "catalog" to null,
            "tracks" to null
        )

        for ((key, hint) in preferredKeys) {
            obj.optJSONArray(key)?.let { arr ->
                result.add(arr to hint)
                consumedKeys.add(key)
            }
        }

        if (result.isEmpty()) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in consumedKeys) continue
                obj.optJSONArray(k)?.let { arr ->
                    if (arr.length() > 0 && arr.optJSONObject(0) != null) {
                        val hint = if (k.contains("series", ignoreCase = true) || k.contains("show", ignoreCase = true)) {
                            MediaContentType.SERIES
                        } else {
                            null
                        }
                        result.add(arr to hint)
                    }
                }
            }
        }
        return result
    }

    private fun collectArrayItems(
        array: JSONArray,
        defaultGroup: String,
        defaultTypeHint: MediaContentType?,
        playlistMirrors: List<String>,
        playlistHeaders: Map<String, String>,
        target: MutableList<JsonPlaylistItem>
    ) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseItemObject(
                obj = obj,
                defaultGroup = defaultGroup,
                defaultTypeHint = defaultTypeHint,
                playlistMirrors = playlistMirrors,
                playlistHeaders = playlistHeaders
            )?.let { target.add(it) }
        }
    }

    private fun parseItemObject(
        obj: JSONObject,
        defaultGroup: String,
        defaultTypeHint: MediaContentType?,
        playlistMirrors: List<String>,
        playlistHeaders: Map<String, String>
    ): JsonPlaylistItem? {
        val rawContentType = obj.optFirstString("contentType", "content_type", "mediaType", "media_type", "kind", "type")
            ?.lowercase(Locale.ROOT)
        val hasSeasonsOrEpisodes = obj.optJSONArray("seasons") != null || obj.optJSONArray("episodes") != null
        val isSeriesObject = hasSeasonsOrEpisodes ||
            rawContentType == "series" ||
            rawContentType == "show" ||
            rawContentType == "tv" ||
            rawContentType == "tvshow" ||
            (defaultTypeHint == MediaContentType.SERIES && hasSeasonsOrEpisodes)

        val itemId = obj.optFirstString("id") ?: UUID.randomUUID().toString()
        val itemHeaders = mergeHeaders(playlistHeaders, extractHeaders(obj))

        if (isSeriesObject) {
            val seasons = parseSeasons(
                seriesObj = obj,
                seriesId = itemId,
                playlistMirrors = playlistMirrors,
                inheritedHeaders = itemHeaders
            )
            if (seasons.isEmpty()) return null

            val firstEp = seasons.first().episodes.first()
            val title = obj.optFirstString("title", "name", "seriesTitle", "series_title", "showName")
                ?.takeIf { it.isNotBlank() } ?: "Untitled Series"
            val group = extractGroup(obj) ?: if (defaultGroup == "Movies") "Series" else defaultGroup
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

            return JsonPlaylistItem(
                id = itemId,
                title = title,
                url = firstEp.url,
                groupTitle = group,
                format = firstEp.format,
                imageUrl = imageUrl,
                imdbId = imdbId,
                imdbRating = rating,
                releaseYear = year,
                description = description,
                subtitleUrl = firstEp.subtitleUrl,
                subtitleLabel = firstEp.subtitleLabel,
                contentType = MediaContentType.SERIES,
                mirrors = firstEp.mirrors,
                headers = itemHeaders,
                seasons = seasons
            )
        }

        // Standard Movie / Single Stream item
        val streamUrl = extractStreamUrl(obj) ?: return null
        if (!MediaFormatDetector.isValidMediaLink(streamUrl)) return null

        val rawTitle = obj.optFirstString("title", "name", "label", "movieName", "movie_name", "channel")
        val title = rawTitle?.takeIf { it.isNotBlank() } ?: MediaFormatDetector.inferTitle(streamUrl)

        val formatHint = obj.optFirstString("format", "mimeType", "mime_type", "type")
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

        val explicitMirrors = extractItemMirrors(obj, primaryUrl = streamUrl)
        val resolvedCandidates = CdnMirrorResolver.buildCandidateUrls(
            primaryUrl = streamUrl,
            itemMirrors = explicitMirrors,
            playlistCdnMirrors = playlistMirrors
        )
        val backupMirrors = resolvedCandidates.drop(1)

        return JsonPlaylistItem(
            id = itemId,
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
            subtitleLabel = subtitlePair?.second,
            contentType = MediaContentType.MOVIE,
            mirrors = backupMirrors,
            headers = itemHeaders,
            seasons = emptyList()
        )
    }

    private fun parseSeasons(
        seriesObj: JSONObject,
        seriesId: String,
        playlistMirrors: List<String>,
        inheritedHeaders: Map<String, String>
    ): List<SeriesSeason> {
        val seasonsArray = seriesObj.optJSONArray("seasons")
        if (seasonsArray != null && seasonsArray.length() > 0) {
            val parsedSeasons = mutableListOf<SeriesSeason>()
            for (sIdx in 0 until seasonsArray.length()) {
                val seasonObj = seasonsArray.optJSONObject(sIdx) ?: continue
                val seasonNum = seasonObj.optIntOrNull("season", "seasonNumber", "season_number", "number")
                    ?: (sIdx + 1)
                val seasonTitle = seasonObj.optFirstString("title", "name") ?: "Season $seasonNum"
                val seasonHeaders = mergeHeaders(inheritedHeaders, extractHeaders(seasonObj))
                val episodesArr = seasonObj.optJSONArray("episodes")
                    ?: seasonObj.optJSONArray("items")
                    ?: seasonObj.optJSONArray("videos")
                    ?: continue

                val episodes = mutableListOf<SeriesEpisode>()
                for (eIdx in 0 until episodesArr.length()) {
                    val epObj = episodesArr.optJSONObject(eIdx) ?: continue
                    parseEpisodeObject(
                        epObj = epObj,
                        seriesId = seriesId,
                        defaultSeasonNum = seasonNum,
                        defaultEpisodeNum = eIdx + 1,
                        playlistMirrors = playlistMirrors,
                        inheritedHeaders = seasonHeaders
                    )?.let { episodes.add(it) }
                }

                if (episodes.isNotEmpty()) {
                    parsedSeasons.add(
                        SeriesSeason(
                            seasonNumber = seasonNum,
                            title = seasonTitle,
                            episodes = episodes.sortedBy { it.episodeNumber }
                        )
                    )
                }
            }
            if (parsedSeasons.isNotEmpty()) {
                return parsedSeasons.sortedBy { it.seasonNumber }
            }
        }

        // Flat "episodes": [...] fallback (grouped automatically by seasonNumber, defaulting to Season 1)
        val flatEpisodesArr = seriesObj.optJSONArray("episodes") ?: return emptyList()
        val flatEpisodes = mutableListOf<SeriesEpisode>()
        for (eIdx in 0 until flatEpisodesArr.length()) {
            val epObj = flatEpisodesArr.optJSONObject(eIdx) ?: continue
            val seasonNum = epObj.optIntOrNull("season", "seasonNumber", "season_number") ?: 1
            parseEpisodeObject(
                epObj = epObj,
                seriesId = seriesId,
                defaultSeasonNum = seasonNum,
                defaultEpisodeNum = eIdx + 1,
                playlistMirrors = playlistMirrors,
                inheritedHeaders = inheritedHeaders
            )?.let { flatEpisodes.add(it) }
        }
        if (flatEpisodes.isEmpty()) return emptyList()

        return flatEpisodes
            .groupBy { it.seasonNumber }
            .toSortedMap()
            .map { (seasonNum, eps) ->
                SeriesSeason(
                    seasonNumber = seasonNum,
                    title = "Season $seasonNum",
                    episodes = eps.sortedBy { it.episodeNumber }
                )
            }
    }

    private fun parseEpisodeObject(
        epObj: JSONObject,
        seriesId: String,
        defaultSeasonNum: Int,
        defaultEpisodeNum: Int,
        playlistMirrors: List<String>,
        inheritedHeaders: Map<String, String>
    ): SeriesEpisode? {
        val streamUrl = extractStreamUrl(epObj) ?: return null
        if (!MediaFormatDetector.isValidMediaLink(streamUrl)) return null

        val seasonNum = epObj.optIntOrNull("season", "seasonNumber", "season_number") ?: defaultSeasonNum
        val episodeNum = epObj.optIntOrNull("episode", "episodeNumber", "episode_number", "number") ?: defaultEpisodeNum
        val epId = epObj.optFirstString("id") ?: "${seriesId}_s${seasonNum}_e${episodeNum}"
        val title = epObj.optFirstString("title", "name", "episodeTitle", "episode_title")
            ?.takeIf { it.isNotBlank() } ?: "Episode $episodeNum"

        val formatHint = epObj.optFirstString("format", "mimeType", "mime_type", "type")
        val format = resolveFormat(formatHint, streamUrl)
        val subtitlePair = extractSubtitle(epObj)
        val explicitMirrors = extractItemMirrors(epObj, primaryUrl = streamUrl)
        val backupMirrors = CdnMirrorResolver.buildCandidateUrls(
            primaryUrl = streamUrl,
            itemMirrors = explicitMirrors,
            playlistCdnMirrors = playlistMirrors
        ).drop(1)
        val epHeaders = mergeHeaders(inheritedHeaders, extractHeaders(epObj))

        return SeriesEpisode(
            id = epId,
            seasonNumber = seasonNum,
            episodeNumber = episodeNum,
            title = title,
            url = streamUrl,
            mirrors = backupMirrors,
            headers = epHeaders,
            format = format,
            description = epObj.optFirstString("description", "plot", "overview", "synopsis", "summary"),
            durationLabel = epObj.optFirstString("duration", "runtime", "length"),
            imageUrl = extractImageUrl(epObj),
            subtitleUrl = subtitlePair?.first,
            subtitleLabel = subtitlePair?.second
        )
    }

    private fun extractItemMirrors(obj: JSONObject, primaryUrl: String): List<String> {
        val mirrors = LinkedHashSet<String>()
        mirrors.addAll(extractStringList(obj, "mirrors", "cdnUrls", "cdn_urls", "backupUrls", "fallbackUrls"))

        // Secondary entries inside "sources" or "streams" arrays also act as automatic CDN mirrors
        val sourcesArray = obj.optJSONArray("sources") ?: obj.optJSONArray("streams")
        if (sourcesArray != null) {
            for (i in 0 until sourcesArray.length()) {
                val candidate = when (val entry = sourcesArray.opt(i)) {
                    is String -> entry.trim()
                    is JSONObject -> entry.optFirstString("url", "src", "file", "streamUrl", "source")?.trim()
                    else -> null
                }
                if (!candidate.isNullOrBlank() &&
                    candidate != primaryUrl &&
                    MediaFormatDetector.isValidMediaLink(candidate)
                ) {
                    mirrors.add(candidate)
                }
            }
        }
        return mirrors.toList()
    }

    private fun extractStringList(obj: JSONObject, vararg keys: String): List<String> {
        val list = mutableListOf<String>()
        for (key in keys) {
            val arr = obj.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is String -> if (item.isNotBlank()) list.add(item.trim())
                    is JSONObject -> item.optFirstString("url", "host", "baseUrl", "src")?.let { list.add(it) }
                }
            }
        }
        return list.distinct()
    }

    private fun extractHeaders(obj: JSONObject): Map<String, String> {
        val headersObj = obj.optJSONObject("headers")
            ?: obj.optJSONObject("httpHeaders")
            ?: obj.optJSONObject("http_headers")
            ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = headersObj.keys()
        while (keys.hasNext()) {
            val k = keys.next().trim()
            val v = headersObj.optString(k, "").trim()
            if (k.isNotBlank() && v.isNotBlank()) {
                result[k] = v
            }
        }
        return result
    }

    private fun mergeHeaders(parent: Map<String, String>, child: Map<String, String>): Map<String, String> {
        if (parent.isEmpty()) return child
        if (child.isEmpty()) return parent
        return LinkedHashMap<String, String>(parent.size + child.size).apply {
            putAll(parent)
            putAll(child)
        }
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

    private fun JSONObject.optIntOrNull(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }
}
