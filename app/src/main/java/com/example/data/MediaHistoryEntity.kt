package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_history",
    indices = [Index(value = ["sourceUri"], unique = true)]
)
data class MediaHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val sourceUri: String,
    val formatName: String,
    val isLocalFile: Boolean = false,
    val subtitleUri: String? = null,
    val subtitleName: String? = null,
    val subtitleMimeType: String? = null,
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPinned: Boolean = false,
    val lastPlayedAt: Long = System.currentTimeMillis()
)
