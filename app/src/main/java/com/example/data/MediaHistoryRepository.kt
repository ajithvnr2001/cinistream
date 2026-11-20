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
        subtitle: ExternalSubtitle?
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
                lastPlayedAt = now
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
                lastPlayedAt = now
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

    suspend fun togglePin(item: MediaHistoryEntity) {
        dao.setPinned(item.id, !item.isPinned)
    }

    suspend fun deleteItem(id: Long) {
        dao.deleteById(id)
    }
}
