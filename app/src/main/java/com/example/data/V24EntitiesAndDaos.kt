package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "favorites",
    indices = [Index(value = ["mediaKey"], unique = true)]
)
data class FavoriteItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mediaKey: String,
    val title: String,
    val sourceUri: String,
    val itemType: String, // MOVIE, SERIES, M3U_CHANNEL, STREAM_URL
    val formatName: String = "AUTO",
    val posterUrl: String? = null,
    val imdbId: String? = null,
    val imdbRating: String? = null,
    val releaseYear: String? = null,
    val groupTitle: String? = null,
    val subtitleUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteItemEntity>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getAllFavorites(): List<FavoriteItemEntity>

    @Query("SELECT * FROM favorites WHERE mediaKey = :mediaKey LIMIT 1")
    suspend fun getByMediaKey(mediaKey: String): FavoriteItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteItemEntity): Long

    @Query("DELETE FROM favorites WHERE mediaKey = :mediaKey")
    suspend fun deleteByMediaKey(mediaKey: String)
}

@Entity(
    tableName = "offline_downloads",
    indices = [Index(value = ["sourceUrl"], unique = true)]
)
data class OfflineDownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceUrl: String,
    val title: String,
    val formatName: String = "MP4",
    val posterUrl: String? = null,
    val localFilePath: String = "",
    val status: String = "QUEUED", // QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface OfflineDownloadDao {
    @Query("SELECT * FROM offline_downloads ORDER BY createdAt DESC")
    fun observeDownloads(): Flow<List<OfflineDownloadEntity>>

    @Query("SELECT * FROM offline_downloads WHERE sourceUrl = :sourceUrl LIMIT 1")
    suspend fun getBySourceUrl(sourceUrl: String): OfflineDownloadEntity?

    @Query("SELECT * FROM offline_downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): OfflineDownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfflineDownloadEntity): Long

    @Query(
        "UPDATE offline_downloads SET status = :status, progressPercent = :progressPercent, bytesDownloaded = :bytesDownloaded, totalBytes = :totalBytes, localFilePath = :localFilePath, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        progressPercent: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        localFilePath: String,
        errorMessage: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM offline_downloads WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Entity(
    tableName = "network_servers",
    indices = [Index(value = ["baseUrl", "serverType"], unique = true)]
)
data class NetworkServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val serverType: String, // WEBDAV or JELLYFIN
    val baseUrl: String,
    val usernameEnc: String = "",
    val secretEnc: String = "", // Encrypted password or Jellyfin API key via CredentialVault
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface NetworkServerDao {
    @Query("SELECT * FROM network_servers ORDER BY createdAt DESC")
    fun observeServers(): Flow<List<NetworkServerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NetworkServerEntity): Long

    @Query("DELETE FROM network_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
