package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.player.CinePlayerManager
import com.example.player.DynamicSocksProxyManager
import com.example.player.FastDnsResolver
import com.example.player.ParallelHlsPrefetchManager
import com.example.player.StreamingNetworkEngine
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Application class providing:
 * - Asynchronous Play Services Cronet HTTP/3 (QUIC + TLS 1.3 + BBR) initialization on Android 9+ TVs & phones.
 * - A single shared HTTP/2-capable OkHttpClient with 5-minute IPv4-first DNS caching (plus optional
 *   Cloudflare 1.1.1.1 / Google 8.8.8.8 DoH and NordVPN SOCKS5 proxy routing) and a tuned
 *   high-concurrency Dispatcher (`maxRequestsPerHost = 12`) so parallel segment prefetches never queue.
 * - A memory-bounded RGB_565 Coil ImageLoader so low-RAM Android TVs stream fast without OOMs.
 */
class CineStreamApplication : Application(), ImageLoaderFactory {

    val fastDnsResolver: FastDnsResolver by lazy { FastDnsResolver() }
    val socksProxyManager: DynamicSocksProxyManager by lazy {
        DynamicSocksProxyManager(proxyDnsResolver = fastDnsResolver)
    }

    val sharedOkHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 12
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // Cache DNS for 5 min with IPv4-first ordering & optional Cloudflare 1.1.1.1 DoH
            .dns(fastDnsResolver)
            // Live-switchable NordVPN SOCKS5 proxy selector (Proxy.NO_PROXY when disabled)
            .proxySelector(socksProxyManager.proxySelector)
            // Force HTTP/2 multiplexing + HTTP/1.1 fallback so Android 9 TVs stream demuxed
            // HLS video + audio .m4s chunks + parallel prefetches smoothly over warm TLS sockets.
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Warm up Google Play Services Chromium Cronet (HTTP/3 QUIC + TLS 1.3) off the main thread
        StreamingNetworkEngine.initializeCronetAsync(
            context = this,
            userAgent = CinePlayerManager.DEFAULT_USER_AGENT
        )
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
            runCatching { ParallelHlsPrefetchManager.trimActiveCaches() }
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
            runCatching { sharedOkHttpClient.connectionPool.evictAll() }
            runCatching { fastDnsResolver.clear() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { ParallelHlsPrefetchManager.trimActiveCaches() }
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        runCatching { sharedOkHttpClient.connectionPool.evictAll() }
        runCatching { fastDnsResolver.clear() }
    }
}
