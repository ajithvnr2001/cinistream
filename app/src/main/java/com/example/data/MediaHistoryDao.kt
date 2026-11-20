package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaHistoryDao {

    @Query("SELECT * FROM media_history ORDER BY isPinned DESC, lastPlayedAt DESC LIMIT 50")
    fun observeHistory(): Flow<List<MediaHistoryEntity>>

    @Query("SELECT * FROM media_history WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getBySourceUri(sourceUri: String): MediaHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaHistoryEntity): Long

    @Query(
        "UPDATE media_history SET lastPositionMs = :positionMs, durationMs = :durationMs, lastPlayedAt = :playedAt WHERE sourceUri = :sourceUri"
    )
    suspend fun updatePlaybackProgress(
        sourceUri: String,
        positionMs: Long,
        durationMs: Long,
        playedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE media_history SET subtitleUri = :subtitleUri, subtitleName = :subtitleName, subtitleMimeType = :subtitleMimeType WHERE sourceUri = :sourceUri"
    )
    suspend fun updateSubtitle(
        sourceUri: String,
        subtitleUri: String?,
        subtitleName: String?,
        subtitleMimeType: String?
    )

    @Query("UPDATE media_history SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM media_history WHERE id = :id")
    suspend fun deleteById(id: Long)
}
