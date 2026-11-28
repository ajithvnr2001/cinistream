package com.example.data

import com.example.model.GlobalSearchCategory
import com.example.model.GlobalSearchResult
import com.example.model.JsonPlaylistItem
import com.example.model.M3uEntry
import com.example.model.StreamFormat
import kotlinx.coroutines.flow.Flow

class FavoriteRepository(private val dao: FavoriteDao) {
    val favorites: Flow<List<FavoriteItemEntity>> = dao.observeFavorites()

    suspend fun getAll(): List<FavoriteItemEntity> = dao.getAllFavorites()

    suspend fun isFavorite(mediaKey: String): Boolean = dao.getByMediaKey(mediaKey) != null

    suspend fun toggleFavorite(item: FavoriteItemEntity): Boolean {
        val existing = dao.getByMediaKey(item.mediaKey)
        return if (existing != null) {
            dao.deleteByMediaKey(item.mediaKey)
            false
        } else {
            dao.upsert(item.copy(id = 0L, addedAt = System.currentTimeMillis()))
            true
        }
    }

    suspend fun remove(mediaKey: String) {
        dao.deleteByMediaKey(mediaKey)
    }

    suspend fun mergeSyncedFavorites(items: List<FavoriteItemEntity>): Int {
        var merged = 0
        for (item in items) {
            if (dao.getByMediaKey(item.mediaKey) == null) {
                dao.upsert(item.copy(id = 0L))
                merged++
            }
        }
        return merged
    }
}

object GlobalSearchEngine {
    fun search(
        query: String,
        favorites: List<FavoriteItemEntity>,
        jsonItems: List<JsonPlaylistItem>,
        m3uEntries: List<M3uEntry>,
        downloads: List<OfflineDownloadEntity>,
        history: List<MediaHistoryEntity>,
        maxResults: Int = 30
    ): List<GlobalSearchResult> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val seen = HashSet<String>()
        val results = mutableListOf<GlobalSearchResult>()

        for (fav in favorites) {
            if (fav.title.lowercase().contains(q) || (fav.groupTitle?.lowercase()?.contains(q) == true)) {
                val dedupeKey = "fav:${fav.sourceUri.lowercase()}"
                if (seen.add(dedupeKey)) {
                    results.add(
                        GlobalSearchResult(
                            id = "fav_${fav.id}",
                            title = fav.title,
                            subtitle = fav.groupTitle ?: fav.itemType,
                            category = GlobalSearchCategory.FAVORITE,
                            format = runCatching { StreamFormat.valueOf(fav.formatName) }.getOrDefault(StreamFormat.AUTO),
                            posterUrl = fav.posterUrl,
                            sourceUri = fav.sourceUri
                        )
                    )
                }
            }
        }

        for (item in jsonItems) {
            val matches = item.title.lowercase().contains(q) ||
                item.groupTitle.lowercase().contains(q) ||
                (item.imdbId?.lowercase()?.contains(q) == true) ||
                (item.releaseYear?.contains(q) == true)
            if (matches) {
                val dedupeKey = "json:${item.id}"
                if (seen.add(dedupeKey)) {
                    val badge = if (item.isCollection) "${item.totalCollectionMovies} Films" else null
                    results.add(
                        GlobalSearchResult(
                            id = "json_${item.id}",
                            title = item.title,
                            subtitle = listOfNotNull(badge, item.releaseYear, item.imdbRating?.let { "IMDb $it" }, item.groupTitle)
                                .joinToString(" · "),
                            category = GlobalSearchCategory.CATALOG,
                            format = item.format,
                            posterUrl = item.imageUrl,
                            sourceUri = item.url,
                            jsonItem = item
                        )
                    )
                }
            }
            if (item.isCollection) {
                for (movie in item.orderedCollectionMovies()) {
                    val movieMatches = movie.title.lowercase().contains(q) ||
                        (movie.imdbId?.lowercase()?.contains(q) == true) ||
                        (movie.releaseYear?.contains(q) == true)
                    if (movieMatches) {
                        val movieKey = "col_movie:${item.id}:${movie.id}"
                        if (seen.add(movieKey)) {
                            results.add(
                                GlobalSearchResult(
                                    id = "col_${item.id}_${movie.id}",
                                    title = movie.title,
                                    subtitle = listOfNotNull(
                                        item.title,
                                        movie.releaseYear,
                                        movie.imdbRating?.let { "IMDb $it" }
                                    ).joinToString(" · "),
                                    category = GlobalSearchCategory.CATALOG,
                                    format = movie.format,
                                    posterUrl = movie.imageUrl ?: item.imageUrl,
                                    sourceUri = movie.url,
                                    jsonItem = JsonPlaylistItem(
                                        id = movie.id,
                                        title = movie.title,
                                        url = movie.url,
                                        groupTitle = item.groupTitle,
                                        format = movie.format,
                                        imageUrl = movie.imageUrl ?: item.imageUrl,
                                        imdbId = movie.imdbId,
                                        imdbRating = movie.imdbRating,
                                        releaseYear = movie.releaseYear,
                                        description = movie.description,
                                        subtitleUrl = movie.subtitleUrl,
                                        subtitleLabel = movie.subtitleLabel,
                                        mirrors = movie.mirrors,
                                        headers = movie.headers,
                                        externalAudios = movie.externalAudios
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }

        for (entry in m3uEntries) {
            if (entry.title.lowercase().contains(q) || entry.groupTitle.lowercase().contains(q)) {
                val dedupeKey = "m3u:${entry.url.lowercase()}"
                if (seen.add(dedupeKey)) {
                    results.add(
                        GlobalSearchResult(
                            id = "m3u_${entry.id}",
                            title = entry.title,
                            subtitle = entry.groupTitle,
                            category = GlobalSearchCategory.M3U,
                            format = entry.format,
                            posterUrl = entry.tvgLogo,
                            sourceUri = entry.url,
                            m3uEntry = entry
                        )
                    )
                }
            }
        }

        for (dl in downloads) {
            if (dl.status == "COMPLETED" && dl.title.lowercase().contains(q)) {
                val dedupeKey = "dl:${dl.id}"
                if (seen.add(dedupeKey)) {
                    results.add(
                        GlobalSearchResult(
                            id = "dl_${dl.id}",
                            title = dl.title,
                            subtitle = "Offline · Ready to play",
                            category = GlobalSearchCategory.DOWNLOAD,
                            format = runCatching { StreamFormat.valueOf(dl.formatName) }.getOrDefault(StreamFormat.MP4),
                            posterUrl = dl.posterUrl,
                            sourceUri = "file://${dl.localFilePath}"
                        )
                    )
                }
            }
        }

        for (h in history) {
            if (h.title.lowercase().contains(q) || (h.seriesTitle?.lowercase()?.contains(q) == true)) {
                val dedupeKey = "hist:${h.sourceUri.lowercase()}"
                if (seen.add(dedupeKey)) {
                    results.add(
                        GlobalSearchResult(
                            id = "hist_${h.id}",
                            title = h.title,
                            subtitle = h.seriesTitle ?: h.formatName,
                            category = GlobalSearchCategory.HISTORY,
                            format = runCatching { StreamFormat.valueOf(h.formatName) }.getOrDefault(StreamFormat.AUTO),
                            posterUrl = h.posterUrl,
                            sourceUri = h.sourceUri
                        )
                    )
                }
            }
        }

        return results.take(maxResults)
    }
}
