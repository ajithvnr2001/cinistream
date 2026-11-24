package com.example.data

import org.json.JSONArray
import org.json.JSONObject

data class DeviceSyncSnapshot(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val jsonPlaylistUrls: List<String> = emptyList(),
    val epgUrl: String = "",
    val preferredAudioLanguage: String = "",
    val preferredSubtitleLanguage: String = "",
    val seekIntervalSeconds: Int = 10,
    val nightModeAudioEnabled: Boolean = false,
    val autoPlayUpNextEnabled: Boolean = true,
    val favorites: List<FavoriteItemEntity> = emptyList(),
    val history: List<MediaHistoryEntity> = emptyList()
)

/**
 * Pure JSON encoder/decoder for Phone <-> Android TV Wi-Fi Sync & Local Backup snapshots.
 * Explicitly excludes private credentials (VPN keys / NAS passwords) so backups stay safe to share over LAN.
 */
object DeviceSyncCodec {
    private const val MAX_HISTORY_SYNC = 100
    private const val MAX_FAVORITES_SYNC = 200

    fun encode(snapshot: DeviceSyncSnapshot): String {
        val root = JSONObject().apply {
            put("schemaVersion", snapshot.schemaVersion)
            put("exportedAt", snapshot.exportedAt)
            put("epgUrl", snapshot.epgUrl)
            put("preferredAudioLanguage", snapshot.preferredAudioLanguage)
            put("preferredSubtitleLanguage", snapshot.preferredSubtitleLanguage)
            put("seekIntervalSeconds", snapshot.seekIntervalSeconds)
            put("nightModeAudioEnabled", snapshot.nightModeAudioEnabled)
            put("autoPlayUpNextEnabled", snapshot.autoPlayUpNextEnabled)
            put("jsonPlaylistUrls", JSONArray().apply {
                snapshot.jsonPlaylistUrls.forEach { put(it) }
            })
            put("favorites", JSONArray().apply {
                snapshot.favorites.take(MAX_FAVORITES_SYNC).forEach { fav ->
                    put(JSONObject().apply {
                        put("mediaKey", fav.mediaKey)
                        put("title", fav.title)
                        put("sourceUri", fav.sourceUri)
                        put("itemType", fav.itemType)
                        put("formatName", fav.formatName)
                        fav.posterUrl?.let { put("posterUrl", it) }
                        fav.imdbId?.let { put("imdbId", it) }
                        fav.imdbRating?.let { put("imdbRating", it) }
                        fav.releaseYear?.let { put("releaseYear", it) }
                        fav.groupTitle?.let { put("groupTitle", it) }
                        fav.subtitleUrl?.let { put("subtitleUrl", it) }
                        put("addedAt", fav.addedAt)
                    })
                }
            })
            put("history", JSONArray().apply {
                snapshot.history.filter { !it.isLocalFile }.take(MAX_HISTORY_SYNC).forEach { h ->
                    put(JSONObject().apply {
                        put("title", h.title)
                        put("sourceUri", h.sourceUri)
                        put("formatName", h.formatName)
                        h.subtitleUri?.let { put("subtitleUri", it) }
                        h.subtitleName?.let { put("subtitleName", it) }
                        h.subtitleMimeType?.let { put("subtitleMimeType", it) }
                        put("lastPositionMs", h.lastPositionMs)
                        put("durationMs", h.durationMs)
                        put("isPinned", h.isPinned)
                        put("lastPlayedAt", h.lastPlayedAt)
                        h.seriesId?.let { put("seriesId", it) }
                        h.seriesTitle?.let { put("seriesTitle", it) }
                        h.seasonNumber?.let { put("seasonNumber", it) }
                        h.episodeNumber?.let { put("episodeNumber", it) }
                        h.episodeId?.let { put("episodeId", it) }
                        h.posterUrl?.let { put("posterUrl", it) }
                        h.playlistId?.let { put("playlistId", it) }
                        put("audioDelayMs", h.audioDelayMs)
                        put("subtitleOffsetMs", h.subtitleOffsetMs)
                    })
                }
            })
        }
        return root.toString(2)
    }

    fun decode(rawJson: String): DeviceSyncSnapshot? {
        if (rawJson.isBlank() || rawJson.length > 2_000_000) return null
        return runCatching {
            val root = JSONObject(rawJson)
            val urls = buildList {
                val arr = root.optJSONArray("jsonPlaylistUrls")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i, "").trim()
                        if (u.startsWith("http://", true) || u.startsWith("https://", true)) add(u)
                    }
                }
            }
            val favorites = buildList {
                val arr = root.optJSONArray("favorites")
                if (arr != null) {
                    for (i in 0 until minOf(arr.length(), MAX_FAVORITES_SYNC)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val key = obj.optString("mediaKey", "").trim()
                        val title = obj.optString("title", "").trim()
                        val uri = obj.optString("sourceUri", "").trim()
                        if (key.isNotBlank() && title.isNotBlank() && uri.isNotBlank()) {
                            add(
                                FavoriteItemEntity(
                                    mediaKey = key,
                                    title = title,
                                    sourceUri = uri,
                                    itemType = obj.optString("itemType", "STREAM_URL"),
                                    formatName = obj.optString("formatName", "AUTO"),
                                    posterUrl = obj.optString("posterUrl", "").takeIf { it.isNotBlank() },
                                    imdbId = obj.optString("imdbId", "").takeIf { it.isNotBlank() },
                                    imdbRating = obj.optString("imdbRating", "").takeIf { it.isNotBlank() },
                                    releaseYear = obj.optString("releaseYear", "").takeIf { it.isNotBlank() },
                                    groupTitle = obj.optString("groupTitle", "").takeIf { it.isNotBlank() },
                                    subtitleUrl = obj.optString("subtitleUrl", "").takeIf { it.isNotBlank() },
                                    addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                                )
                            )
                        }
                    }
                }
            }
            val history = buildList {
                val arr = root.optJSONArray("history")
                if (arr != null) {
                    for (i in 0 until minOf(arr.length(), MAX_HISTORY_SYNC)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val title = obj.optString("title", "").trim()
                        val uri = obj.optString("sourceUri", "").trim()
                        if (title.isNotBlank() && (uri.startsWith("http://", true) || uri.startsWith("https://", true))) {
                            add(
                                MediaHistoryEntity(
                                    title = title,
                                    sourceUri = uri,
                                    formatName = obj.optString("formatName", "AUTO"),
                                    isLocalFile = false,
                                    subtitleUri = obj.optString("subtitleUri", "").takeIf { it.isNotBlank() },
                                    subtitleName = obj.optString("subtitleName", "").takeIf { it.isNotBlank() },
                                    subtitleMimeType = obj.optString("subtitleMimeType", "").takeIf { it.isNotBlank() },
                                    lastPositionMs = obj.optLong("lastPositionMs", 0L).coerceAtLeast(0L),
                                    durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
                                    isPinned = obj.optBoolean("isPinned", false),
                                    lastPlayedAt = obj.optLong("lastPlayedAt", System.currentTimeMillis()),
                                    seriesId = obj.optString("seriesId", "").takeIf { it.isNotBlank() },
                                    seriesTitle = obj.optString("seriesTitle", "").takeIf { it.isNotBlank() },
                                    seasonNumber = if (obj.has("seasonNumber") && !obj.isNull("seasonNumber")) obj.optInt("seasonNumber") else null,
                                    episodeNumber = if (obj.has("episodeNumber") && !obj.isNull("episodeNumber")) obj.optInt("episodeNumber") else null,
                                    episodeId = obj.optString("episodeId", "").takeIf { it.isNotBlank() },
                                    posterUrl = obj.optString("posterUrl", "").takeIf { it.isNotBlank() },
                                    playlistId = obj.optString("playlistId", "").takeIf { it.isNotBlank() },
                                    audioDelayMs = obj.optLong("audioDelayMs", 0L),
                                    subtitleOffsetMs = obj.optLong("subtitleOffsetMs", 0L)
                                )
                            )
                        }
                    }
                }
            }
            val seekSec = root.optInt("seekIntervalSeconds", 10).let { if (it in listOf(5, 10, 15, 30)) it else 10 }
            DeviceSyncSnapshot(
                schemaVersion = root.optInt("schemaVersion", 1),
                exportedAt = root.optLong("exportedAt", System.currentTimeMillis()),
                jsonPlaylistUrls = urls,
                epgUrl = root.optString("epgUrl", "").trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty(),
                preferredAudioLanguage = root.optString("preferredAudioLanguage", "").trim(),
                preferredSubtitleLanguage = root.optString("preferredSubtitleLanguage", "").trim(),
                seekIntervalSeconds = seekSec,
                nightModeAudioEnabled = root.optBoolean("nightModeAudioEnabled", false),
                autoPlayUpNextEnabled = root.optBoolean("autoPlayUpNextEnabled", true),
                favorites = favorites,
                history = history
            )
        }.getOrNull()
    }
}
