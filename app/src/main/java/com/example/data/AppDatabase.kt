package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaHistoryEntity::class,
        FavoriteItemEntity::class,
        OfflineDownloadEntity::class,
        NetworkServerEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaHistoryDao(): MediaHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun offlineDownloadDao(): OfflineDownloadDao
    abstract fun networkServerDao(): NetworkServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_history ADD COLUMN seriesId TEXT")
                db.execSQL("ALTER TABLE media_history ADD COLUMN seriesTitle TEXT")
                db.execSQL("ALTER TABLE media_history ADD COLUMN seasonNumber INTEGER")
                db.execSQL("ALTER TABLE media_history ADD COLUMN episodeNumber INTEGER")
                db.execSQL("ALTER TABLE media_history ADD COLUMN episodeId TEXT")
                db.execSQL("ALTER TABLE media_history ADD COLUMN posterUrl TEXT")
                db.execSQL("ALTER TABLE media_history ADD COLUMN playlistId TEXT")
                db.execSQL("ALTER TABLE media_history ADD COLUMN audioDelayMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_history ADD COLUMN subtitleOffsetMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorites` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mediaKey` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceUri` TEXT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `formatName` TEXT NOT NULL,
                        `posterUrl` TEXT,
                        `imdbId` TEXT,
                        `imdbRating` TEXT,
                        `releaseYear` TEXT,
                        `groupTitle` TEXT,
                        `subtitleUrl` TEXT,
                        `addedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_mediaKey` ON `favorites` (`mediaKey`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `offline_downloads` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `formatName` TEXT NOT NULL,
                        `posterUrl` TEXT,
                        `localFilePath` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `progressPercent` INTEGER NOT NULL,
                        `bytesDownloaded` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_downloads_sourceUrl` ON `offline_downloads` (`sourceUrl`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `network_servers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `serverType` TEXT NOT NULL,
                        `baseUrl` TEXT NOT NULL,
                        `usernameEnc` TEXT NOT NULL,
                        `secretEnc` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_network_servers_baseUrl_serverType` ON `network_servers` (`baseUrl`, `serverType`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cinestream_local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
