package com.example.data

import com.example.model.ExternalSubtitle
import com.example.model.StreamFormat
import kotlinx.coroutines.flow.Flow

class MediaHistoryRepository(private val dao: MediaHistoryDao) {

    val history: Flow<List<MediaHistoryEntity>> = dao.observeHistory()

    suspend fun recordPlayback(
        title: String,
        sourceUri: String,
        format: StreamFormat,
        isLocalFile: Boolean,
        subtitle: ExternalSubtitle?,
        seriesId: String? = null,
        seriesTitle: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeId: String? = null,
        posterUrl: String? = null,
        playlistId: String? = null
    ): MediaHistoryEntity {
        val existing = dao.getBySourceUri(sourceUri)
        val now = System.currentTimeMillis()
        val updated = if (existing != null) {
            existing.copy(
                title = title.ifBlank { existing.title },
                formatName = format.name,
                isLocalFile = isLocalFile,
                subtitleUri = subtitle?.uri ?: existing.subtitleUri,
                subtitleName = subtitle?.label ?: existing.subtitleName,
                subtitleMimeType = subtitle?.mimeType ?: existing.subtitleMimeType,
                lastPlayedAt = now,
                seriesId = seriesId ?: existing.seriesId,
                seriesTitle = seriesTitle ?: existing.seriesTitle,
                seasonNumber = seasonNumber ?: existing.seasonNumber,
                episodeNumber = episodeNumber ?: existing.episodeNumber,
                episodeId = episodeId ?: existing.episodeId,
                posterUrl = posterUrl ?: existing.posterUrl,
                playlistId = playlistId ?: existing.playlistId
            )
        } else {
            MediaHistoryEntity(
                title = title,
                sourceUri = sourceUri,
                formatName = format.name,
                isLocalFile = isLocalFile,
                subtitleUri = subtitle?.uri,
                subtitleName = subtitle?.label,
                subtitleMimeType = subtitle?.mimeType,
                lastPlayedAt = now,
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeId = episodeId,
                posterUrl = posterUrl,
                playlistId = playlistId
            )
        }
        dao.upsert(updated)
        return updated
    }

    suspend fun getBySourceUri(sourceUri: String): MediaHistoryEntity? =
        dao.getBySourceUri(sourceUri)

    suspend fun saveProgress(sourceUri: String, positionMs: Long, durationMs: Long) {
        if (sourceUri.isBlank()) return
        dao.updatePlaybackProgress(sourceUri, positionMs.coerceAtLeast(0L), durationMs.coerceAtLeast(0L))
    }

    suspend fun saveAttachedSubtitle(sourceUri: String, subtitle: ExternalSubtitle?) {
        if (sourceUri.isBlank()) return
        dao.updateSubtitle(
            sourceUri = sourceUri,
            subtitleUri = subtitle?.uri,
            subtitleName = subtitle?.label,
            subtitleMimeType = subtitle?.mimeType
        )
    }

    suspend fun saveSyncOffsets(sourceUri: String, subtitleOffsetMs: Long, audioDelayMs: Long) {
        if (sourceUri.isBlank()) return
        dao.updateSyncOffsets(sourceUri, subtitleOffsetMs, audioDelayMs)
    }

    suspend fun clearResumeProgress(id: Long) {
        dao.clearResumeProgress(id)
    }

    suspend fun togglePin(item: MediaHistoryEntity) {
        dao.setPinned(item.id, !item.isPinned)
    }

    suspend fun deleteItem(id: Long) {
        dao.deleteById(id)
    }

    suspend fun getAllHistory(): List<MediaHistoryEntity> = dao.getAllHistory()

    suspend fun mergeSyncedHistory(items: List<MediaHistoryEntity>): Int {
        var merged = 0
        for (incoming in items) {
            val existing = dao.getBySourceUri(incoming.sourceUri)
            if (existing == null) {
                dao.upsert(incoming.copy(id = 0L))
                merged++
            } else if (incoming.lastPlayedAt > existing.lastPlayedAt) {
                dao.upsert(
                    existing.copy(
                        title = incoming.title.ifBlank { existing.title },
                        lastPositionMs = incoming.lastPositionMs,
                        durationMs = maxOf(incoming.durationMs, existing.durationMs),
                        isPinned = existing.isPinned || incoming.isPinned,
                        lastPlayedAt = incoming.lastPlayedAt,
                        posterUrl = incoming.posterUrl ?: existing.posterUrl,
                        audioDelayMs = incoming.audioDelayMs,
                        subtitleOffsetMs = incoming.subtitleOffsetMs
                    )
                )
                merged++
            }
        }
        return merged
    }
}
