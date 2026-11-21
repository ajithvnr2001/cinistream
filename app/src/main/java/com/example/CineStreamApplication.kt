package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Application class providing a single shared OkHttpClient (zero body logging) and a
 * memory-bounded RGB_565 Coil ImageLoader so low-RAM Android TVs never exhaust Java heap.
 */
class CineStreamApplication : Application(), ImageLoaderFactory {

    val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 3, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(sharedOkHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Strict 10% cap (down from Coil's 25% default) for 32-bit Android TVs
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "imdb_poster_cache"))
                    .maxSizeBytes(24L * 1024L * 1024L)
                    .build()
            }
            // Cuts decoded opaque poster/backdrop bitmap RAM in half and avoids dual-bitmap crossfades
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
            runCatching { sharedOkHttpClient.connectionPool.evictAll() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        runCatching { sharedOkHttpClient.connectionPool.evictAll() }
    }
}
