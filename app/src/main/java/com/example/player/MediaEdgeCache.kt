package com.example.player

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide singleton managing a TV-safe Media3 [SimpleCache] in `cacheDir/media_edge_cache`.
 *
 * - Zero extra Java heap RAM (stores media chunks on disk only).
 * - Strictly LRU-capped (64 MB on Android TV / low-RAM devices, 128 MB on phones/tablets).
 * - Automatically bypasses `.m3u8`/`.mpd` manifests, subtitles, and local `content://` files via
 *   [SmartEdgeCacheDataSource] so live playlists never go stale and local storage isn't duplicated.
 * - Uses [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] so full disk storage silently falls back
 *   to direct network playback instead of failing playback.
 */
@OptIn(UnstableApi::class)
object MediaEdgeCache {

    private const val CACHE_DIR_NAME = "media_edge_cache"
    private const val MAX_CACHE_FILE_SIZE_BYTES = 4L * 1024L * 1024L
    const val WRITE_BUFFER_SIZE_BYTES = 128 * 1024
    const val CONSTRAINED_HEAP_THRESHOLD_BYTES = 400L * 1024L * 1024L

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var maxBytes: Long = CdnMirrorResolver.TV_DISK_CACHE_MAX_BYTES

    private val lock = Any()

    /**
     * Low-RAM Android TVs share slow internal eMMC flash with OS RAM swap. Writing every 2-6 MB
     * HLS `.m4s` chunk synchronously on ExoPlayer's loader thread + SQLite index updates can stall
     * download throughput. Because ExoPlayer already holds a 20-45s RAM buffer + 5s back-buffer,
     * constrained TVs stream significantly faster directly from RAM with zero eMMC write stalls.
     */
    fun shouldBypassDiskCacheForDevice(
        isTv: Boolean,
        isLowRam: Boolean,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    ): Boolean {
        return isTv || isLowRam || maxHeapBytes <= CONSTRAINED_HEAP_THRESHOLD_BYTES
    }

    fun isRamDirectMode(context: Context): Boolean {
        val appCtx = context.applicationContext
        val isTv = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val activityManager = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = activityManager?.isLowRamDevice == true
        return shouldBypassDiskCacheForDevice(
            isTv = isTv,
            isLowRam = isLowRam,
            maxHeapBytes = Runtime.getRuntime().maxMemory()
        )
    }

    fun getOrCreate(context: Context): SimpleCache? {
        simpleCache?.let { return it }
        return synchronized(lock) {
            simpleCache?.let { return it }
            val appCtx = context.applicationContext
            val isTv = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val activityManager = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isLowRam = activityManager?.isLowRamDevice == true ||
                Runtime.getRuntime().maxMemory() <= CONSTRAINED_HEAP_THRESHOLD_BYTES
            val capBytes = CdnMirrorResolver.computeSafeDiskCacheBytes(isTv || isLowRam)
            maxBytes = capBytes

            runCatching {
                val dir = File(appCtx.cacheDir, CACHE_DIR_NAME)
                if (!dir.exists()) dir.mkdirs()
                val evictor = LeastRecentlyUsedCacheEvictor(capBytes)
                val dbProvider = StandaloneDatabaseProvider(appCtx)
                SimpleCache(dir, evictor, dbProvider).also {
                    simpleCache = it
                }
            }.getOrNull()
        }
    }

    fun usedBytes(): Long = runCatching { simpleCache?.cacheSpace ?: 0L }.getOrDefault(0L)

    fun maxBytes(): Long = maxBytes

    fun clearAll() {
        val cache = simpleCache ?: return
        runCatching {
            val keys = cache.keys.toList()
            for (key in keys) {
                cache.removeResource(key)
            }
        }
    }

    /**
     * Wraps [directDataSourceFactory] with fast 128 KB-buffered disk caching on modern phones and
     * tablets, while routing constrained Android TVs directly through RAM-only streaming so slow
     * TV eMMC flash writes never throttle HLS/DASH/MKV loader threads.
     */
    fun createSmartDataSourceFactory(
        context: Context,
        directDataSourceFactory: DataSource.Factory,
        bypassCacheProvider: () -> Boolean = { false }
    ): DataSource.Factory {
        if (isRamDirectMode(context)) {
            maxBytes = 0L
            return directDataSourceFactory
        }
        val cache = getOrCreate(context) ?: return directDataSourceFactory
        val cacheSinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(MAX_CACHE_FILE_SIZE_BYTES)
            .setBufferSize(WRITE_BUFFER_SIZE_BYTES)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(directDataSourceFactory)
            .setCacheWriteDataSinkFactory(cacheSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DataSource.Factory {
            SmartEdgeCacheDataSource(
                cachedDataSource = cacheDataSourceFactory.createDataSource(),
                directDataSource = directDataSourceFactory.createDataSource(),
                bypassCacheProvider = bypassCacheProvider
            )
        }
    }
}

/**
 * Per-request router that sends cache-eligible HTTP(S) video/audio chunks through Media3's
 * [CacheDataSource] and routes manifests/local files directly to [directDataSource].
 */
@OptIn(UnstableApi::class)
private class SmartEdgeCacheDataSource(
    private val cachedDataSource: DataSource,
    private val directDataSource: DataSource,
    private val bypassCacheProvider: () -> Boolean
) : DataSource {

    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cachedDataSource.addTransferListener(transferListener)
        directDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chosen = if (!bypassCacheProvider() && CdnMirrorResolver.shouldCacheUri(dataSpec.uri)) {
            cachedDataSource
        } else {
            directDataSource
        }
        activeDataSource = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeDataSource?.read(buffer, offset, length) ?: -1
    }

    override fun getUri(): Uri? = activeDataSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeDataSource?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        try {
            activeDataSource?.close()
        } finally {
            activeDataSource = null
        }
    }
}
