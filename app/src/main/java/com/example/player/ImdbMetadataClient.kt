package com.example.player

import com.example.model.CollectionMovie
import com.example.model.JsonPlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ImdbMovieInfo(
    val imdbId: String,
    val title: String,
    val year: String? = null,
    val posterUrl: String? = null,
    val rating: String? = null,
    val genres: String? = null,
    val plot: String? = null,
    val cast: String? = null
)

/**
 * Resolves real IMDb posters, ratings, years, and descriptions via IMDb's public CDN suggestion
 * endpoint and IMDb title JSON-LD metadata without requiring an external API key.
 */
class ImdbMetadataClient(
    private val okHttpClient: OkHttpClient
) {
    private val cache = ConcurrentHashMap<String, ImdbMovieInfo>()

    suspend fun lookup(
        imdbId: String? = null,
        titleQuery: String? = null,
        releaseYear: String? = null
    ): ImdbMovieInfo? = withContext(Dispatchers.IO) {
        val cleanId = extractImdbId(imdbId)
        val cleanQuery = cleanTitleForSearch(titleQuery)

        val cacheKey = cleanId ?: cleanQuery.lowercase(Locale.ROOT)
        if (cacheKey.isBlank()) return@withContext null
        cache[cacheKey]?.let { return@withContext it }

        // 1. Fast CDN suggestion lookup (works by exact tt-ID or movie title)
        val suggestionQuery = cleanId ?: cleanQuery
        var info = fetchFromImdbSuggestion(suggestionQuery, expectedImdbId = cleanId, preferredYear = releaseYear)

        // 2. If we resolved an IMDb ID, enrich with rating/plot from IMDb title page JSON-LD (best-effort)
        val targetId = cleanId ?: info?.imdbId
        if (targetId != null && (info?.rating.isNullOrBlank() || info?.posterUrl.isNullOrBlank())) {
            val ldInfo = fetchTitleJsonLd(targetId)
            if (ldInfo != null) {
                info = if (info == null) {
                    ldInfo
                } else {
                    info.copy(
                        posterUrl = info.posterUrl ?: ldInfo.posterUrl,
                        rating = ldInfo.rating ?: info.rating,
                        plot = ldInfo.plot ?: info.plot,
                        genres = ldInfo.genres ?: info.genres,
                        year = info.year ?: ldInfo.year
                    )
                }
            }
        }

        if (info != null) {
            if (cache.size >= 120) {
                cache.clear()
            }
            cache[info.imdbId] = info
            cache[cacheKey] = info
        }
        info
    }

    private fun fetchFromImdbSuggestion(
        query: String,
        expectedImdbId: String?,
        preferredYear: String?
    ): ImdbMovieInfo? {
        if (query.isBlank()) return null
        val slug = query.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
            .ifBlank { return null }

        val firstChar = slug.firstOrNull { it.isLetterOrDigit() } ?: 'x'
        val encoded = URLEncoder.encode(slug, "UTF-8")
        val endpoint = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"

        return runCatching {
            val req = Request.Builder()
                .url(endpoint)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val source = body.source()
                source.request(262_144L)
                val toRead = minOf(source.buffer.size, 262_144L)
                val bodyText = source.readByteArray(toRead).toString(Charsets.UTF_8)
                parseSuggestionJson(bodyText, expectedImdbId, preferredYear)
            }
        }.getOrNull()
    }

    internal fun parseSuggestionJson(
        jsonString: String,
        expectedImdbId: String? = null,
        preferredYear: String? = null
    ): ImdbMovieInfo? {
        val root = runCatching { JSONObject(jsonString) }.getOrNull() ?: return null
        val items = root.optJSONArray("d") ?: return null
        if (items.length() == 0) return null

        val candidates = mutableListOf<ImdbMovieInfo>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (!id.startsWith("tt")) continue
            val title = obj.optString("l").trim()
            if (title.isBlank()) continue

            val year = obj.optInt("y", 0).takeIf { it > 1880 }?.toString()
            val cast = obj.optString("s").trim().takeIf { it.isNotBlank() }
            val poster = obj.optJSONObject("i")?.optString("imageUrl")?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

            candidates.add(
                ImdbMovieInfo(
                    imdbId = id,
                    title = title,
                    year = year,
                    posterUrl = poster,
                    cast = cast
                )
            )
        }
        if (candidates.isEmpty()) return null

        if (expectedImdbId != null) {
            candidates.firstOrNull { it.imdbId.equals(expectedImdbId, ignoreCase = true) }?.let { return it }
        }
        if (!preferredYear.isNullOrBlank()) {
            candidates.firstOrNull { it.year == preferredYear && !it.posterUrl.isNullOrBlank() }?.let { return it }
        }
        return candidates.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: candidates.first()
    }

    private fun fetchTitleJsonLd(imdbId: String): ImdbMovieInfo? {
        val url = "https://www.imdb.com/title/$imdbId/"
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                // Stream-read at most 512 KB and stop early once the JSON-LD script block completes
                val sb = StringBuilder(32_768)
                var totalRead = 0
                val maxChars = 524_288
                val buf = CharArray(8_192)
                body.charStream().use { reader ->
                    while (totalRead < maxChars) {
                        val n = reader.read(buf, 0, minOf(buf.size, maxChars - totalRead))
                        if (n <= 0) break
                        sb.append(buf, 0, n)
                        totalRead += n
                        val ldIdx = sb.indexOf("application/ld+json")
                        if (ldIdx >= 0 && sb.indexOf("</script>", ldIdx) >= 0) {
                            break
                        }
                    }
                }
                parseImdbHtmlJsonLd(sb.toString(), imdbId)
            }
        }.getOrNull()
    }

    internal fun parseImdbHtmlJsonLd(html: String, fallbackImdbId: String): ImdbMovieInfo? {
        val match = JSON_LD_REGEX.find(html) ?: return null
        val jsonText = match.groupValues.getOrNull(1)?.trim() ?: return null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null

        val name = obj.optString("name").trim()
        if (name.isBlank()) return null

        val poster = obj.optString("image").trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val rating = obj.optJSONObject("aggregateRating")?.opt("ratingValue")?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val plot = obj.optString("description").trim().takeIf { it.isNotBlank() }
        val year = obj.optString("datePublished").let { date ->
            Regex("""\b(19|20)\d{2}\b""").find(date)?.value
        }
        val genreArr = obj.optJSONArray("genre")
        val genres = if (genreArr != null && genreArr.length() > 0) {
            (0 until minOf(3, genreArr.length())).mapNotNull {
                genreArr.optString(it).takeIf { g -> g.isNotBlank() }
            }.joinToString(" · ").takeIf { it.isNotBlank() }
        } else {
            obj.optString("genre").takeIf { it.isNotBlank() }
        }

        return ImdbMovieInfo(
            imdbId = fallbackImdbId,
            title = name,
            year = year,
            posterUrl = poster,
            rating = rating,
            genres = genres,
            plot = plot
        )
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val IMDB_ID_REGEX = Regex("""\b(tt\d{6,10})\b""", RegexOption.IGNORE_CASE)
        private val JSON_LD_REGEX = Regex(
            """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        fun extractImdbId(input: String?): String? {
            if (input.isNullOrBlank()) return null
            return IMDB_ID_REGEX.find(input)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        }

        fun cleanTitleForSearch(rawTitle: String?): String {
            if (rawTitle.isNullOrBlank()) return ""
            return rawTitle
                .replace(Regex("""\[[^\]]*]"""), " ")
                .replace(Regex("""\((19|20)\d{2}\)"""), " ")
                .replace(Regex("""\b(4k|2160p|1080p|720p|480p|hdr|webrip|bluray|hls|m3u8|mkv|mp4|hd)\b""", RegexOption.IGNORE_CASE), " ")
                .replace('.', ' ')
                .replace('_', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        /**
         * Enriches every individual [CollectionMovie] inside a Movie Collection with its own
         * IMDb poster, rating, release year, and plot synopsis, and backfills the collection's
         * cover poster, year span, and rating from its films when not explicitly specified.
         */
        suspend fun enrichCollectionItem(
            collection: JsonPlaylistItem,
            concurrency: Int = 2,
            lookupFn: suspend (imdbId: String?, titleQuery: String?, releaseYear: String?) -> ImdbMovieInfo?
        ): JsonPlaylistItem {
            if (!collection.isCollection) return collection
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            var discoveredGenres: String? = null
            val enrichedMovies = coroutineScope {
                collection.collectionMovies.map { movie ->
                    async {
                        val needsLookup = movie.imageUrl.isNullOrBlank() ||
                            movie.imdbRating.isNullOrBlank() ||
                            movie.imdbId.isNullOrBlank() ||
                            movie.releaseYear.isNullOrBlank()
                        if (!needsLookup) return@async movie
                        semaphore.withPermit {
                            val info = lookupFn(movie.imdbId, movie.title, movie.releaseYear)
                            if (info != null) {
                                if (discoveredGenres == null && !info.genres.isNullOrBlank()) {
                                    discoveredGenres = info.genres
                                }
                                movie.copy(
                                    imageUrl = movie.imageUrl ?: info.posterUrl,
                                    imdbId = movie.imdbId ?: info.imdbId,
                                    imdbRating = movie.imdbRating ?: info.rating,
                                    releaseYear = movie.releaseYear ?: info.year,
                                    description = movie.description ?: info.plot ?: info.cast
                                )
                            } else {
                                movie
                            }
                        }
                    }
                }.awaitAll()
            }

            val years = enrichedMovies.mapNotNull { it.releaseYear?.toIntOrNull() }.sorted()
            val computedSpan = when {
                years.isEmpty() -> null
                years.first() == years.last() -> years.first().toString()
                else -> "${years.first()}–${years.last()}"
            }
            val numericRatings = enrichedMovies.mapNotNull { it.imdbRating?.toDoubleOrNull() }
            val avgRating = if (numericRatings.isNotEmpty()) {
                String.format(Locale.US, "%.1f", numericRatings.average())
            } else {
                enrichedMovies.firstNotNullOfOrNull { it.imdbRating }
            }
            val updatedGroup = if (
                (collection.groupTitle == "Collections" || collection.groupTitle == "Movies") &&
                !discoveredGenres.isNullOrBlank()
            ) {
                discoveredGenres!!
            } else {
                collection.groupTitle
            }

            return collection.copy(
                imageUrl = collection.imageUrl ?: enrichedMovies.firstNotNullOfOrNull { it.imageUrl },
                imdbRating = collection.imdbRating ?: avgRating,
                releaseYear = collection.releaseYear ?: computedSpan,
                groupTitle = updatedGroup,
                collectionMovies = enrichedMovies
            )
        }
    }
}
