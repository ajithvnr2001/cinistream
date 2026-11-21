package com.example.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.android.gms.net.CronetProviderInstaller
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.io.FileNotFoundException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe in-memory DNS cache with IPv4-first dual-stack ordering.
 *
 * Eliminates repeated Android 9 `getaddrinfo` stalls and JioFiber/dual-stack IPv6 fallback
 * delays to Cloudflare Workers / CDNs while preserving full IPv6 fallback on IPv6-only networks.
 */
class FastDnsResolver(
    private val delegate: Dns = Dns.SYSTEM,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = MAX_CACHE_ENTRIES,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) : Dns {

    companion object {
        const val DEFAULT_TTL_MS: Long = 5L * 60L * 1000L
        const val MAX_CACHE_ENTRIES: Int = 128

        fun sortIpv4First(addresses: List<InetAddress>): List<InetAddress> {
            if (addresses.size <= 1) return addresses
            val ipv4 = ArrayList<InetAddress>(addresses.size)
            val other = ArrayList<InetAddress>(addresses.size)
            for (addr in addresses) {
                if (addr is Inet4Address) {
                    ipv4.add(addr)
                } else {
                    other.add(addr)
                }
            }
            if (ipv4.isEmpty() || other.isEmpty()) return addresses
            ipv4.addAll(other)
            return ipv4
        }
    }

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.trim().lowercase(Locale.ROOT)
        val now = clockMs()

        cache[key]?.let { cached ->
            if (now < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
            cache.remove(key, cached)
        }

        val resolved = delegate.lookup(hostname)
        if (resolved.isEmpty()) {
            throw UnknownHostException("Empty DNS lookup for $hostname")
        }

        val ordered = sortIpv4First(resolved)
        if (cache.size >= maxEntries) {
            cache.clear()
        }
        cache[key] = CacheEntry(
            addresses = ordered,
            expiresAtMs = now + ttlMs
        )
        return ordered
    }

    fun clear() {
        cache.clear()
    }

    fun cachedHostCount(): Int = cache.size
}

/**
 * Tuned Media3 load error handling policy for HLS/DASH/progressive Wi-Fi streaming.
 *
 * - Keeps Media3's fail-fast behavior on unrecoverable HTTP 403/404/410, parser errors, and
 *   missing files so Multi-CDN mirror failover triggers immediately instead of stalling.
 * - Cuts initial transient Wi-Fi retry backoff from 1000ms -> 500ms so single-packet drops
 *   recover well before the playback buffer drains.
 */
@OptIn(UnstableApi::class)
class FastStreamingLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(MIN_RETRY_COUNT) {

    companion object {
        const val MIN_RETRY_COUNT: Int = 4
        const val FIRST_RETRY_DELAY_MS: Long = 500L
        const val SECOND_RETRY_DELAY_MS: Long = 1_200L
        const val THIRD_RETRY_DELAY_MS: Long = 2_500L
        const val MAX_RETRY_DELAY_MS: Long = 4_000L
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        if (exception is ParserException ||
            exception is FileNotFoundException ||
            exception is HttpDataSource.CleartextNotPermittedException
        ) {
            return C.TIME_UNSET
        }
        if (exception is DataSourceException && exception.reason == DataSourceException.POSITION_OUT_OF_RANGE) {
            return C.TIME_UNSET
        }
        if (exception is HttpDataSource.InvalidResponseCodeException) {
            val code = exception.responseCode
            // Fail fast on permanent client/auth/not-found errors so CDN mirror failover activates immediately
            if (code in 400..499 && code != 408 && code != 429) {
                return C.TIME_UNSET
            }
        }
        return when (loadErrorInfo.errorCount) {
            1 -> FIRST_RETRY_DELAY_MS
            2 -> SECOND_RETRY_DELAY_MS
            3 -> THIRD_RETRY_DELAY_MS
            else -> MAX_RETRY_DELAY_MS
        }
    }
}

/**
 * Adaptive HTTP DataSource factory that dynamically routes new requests through Google Play
 * Services Cronet (HTTP/3 QUIC + TLS 1.3 + BoringSSL + BBR) once initialized, and falls back
 * seamlessly to CineStream's HTTP/2 OkHttp engine on non-GMS devices or before Cronet loads.
 */
@OptIn(UnstableApi::class)
class AdaptiveHttpDataSourceFactory(
    private val okHttpFactory: OkHttpDataSource.Factory,
    private val transferListener: TransferListener?,
    private val cronetEngineProvider: () -> CronetEngine? = { StreamingNetworkEngine.cronetEngine },
    private val cronetExecutor: ExecutorService = StreamingNetworkEngine.cronetExecutor
) : HttpDataSource.Factory {

    @Volatile
    private var defaultHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var cachedCronetEngine: CronetEngine? = null

    @Volatile
    private var cachedCronetFactory: CronetDataSource.Factory? = null

    private val lock = Any()

    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        val copy = defaultRequestProperties.toMap()
        defaultHeaders = copy
        okHttpFactory.setDefaultRequestProperties(copy)
        synchronized(lock) {
            cachedCronetFactory?.setDefaultRequestProperties(copy)
        }
        return this
    }

    override fun createDataSource(): HttpDataSource {
        val engine = cronetEngineProvider()
        if (engine != null) {
            val cronetSource = runCatching {
                getOrCreateCronetFactory(engine).createDataSource()
            }.getOrNull()
            if (cronetSource != null) {
                return cronetSource
            }
        }
        return okHttpFactory.createDataSource()
    }

    fun isUsingCronet(): Boolean = cronetEngineProvider() != null

    fun activeTransportLabel(): String {
        return if (isUsingCronet()) {
            StreamingNetworkEngine.TRANSPORT_HTTP3_CRONET
        } else {
            StreamingNetworkEngine.TRANSPORT_HTTP2_OKHTTP
        }
    }

    private fun getOrCreateCronetFactory(engine: CronetEngine): CronetDataSource.Factory {
        val existing = cachedCronetFactory
        if (existing != null && cachedCronetEngine === engine) {
            return existing
        }
        return synchronized(lock) {
            val rechecked = cachedCronetFactory
            if (rechecked != null && cachedCronetEngine === engine) {
                return@synchronized rechecked
            }
            CronetDataSource.Factory(engine, cronetExecutor)
                .setConnectionTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setResetTimeoutOnRedirects(true)
                .setTransferListener(transferListener)
                .setFallbackFactory(okHttpFactory)
                .apply {
                    if (defaultHeaders.isNotEmpty()) {
                        setDefaultRequestProperties(defaultHeaders)
                    }
                }
                .also {
                    cachedCronetEngine = engine
                    cachedCronetFactory = it
                }
        }
    }
}

/**
 * Process-wide streaming transport engine managing:
 * 1. Asynchronous Google Play Services Cronet setup (HTTP/3 QUIC + TLS 1.3 + BoringSSL + BBR)
 *    with strict rejection of the slow Java HTTP/1.1 Cronet fallback provider.
 * 2. Bounded daemon network executor for Cronet callbacks (memory-safe on 1 GB Android 9 TVs).
 * 3. Debounced background origin pre-warming (DNS + TLS/QUIC socket warm-up) before Play is tapped.
 */
@OptIn(UnstableApi::class)
object StreamingNetworkEngine {

    const val TRANSPORT_HTTP3_CRONET = "HTTP/3 · QUIC (Cronet)"
    const val TRANSPORT_HTTP2_OKHTTP = "HTTP/2 (OkHttp)"

    private const val PREWARM_COOLDOWN_MS = 60_000L
    private const val MAX_PREWARM_HOSTS = 32

    private val cronetInitStarted = AtomicBoolean(false)

    @Volatile
    var cronetEngine: CronetEngine? = null
        private set

    private val prewarmTimestamps = ConcurrentHashMap<String, Long>()

    val cronetExecutor: ExecutorService by lazy {
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "CineStream-Net").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    private val prewarmExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CineStream-Prewarm").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /**
     * Asynchronously installs Google Play Services Cronet on a background thread and builds a
     * native Chromium [CronetEngine] with HTTP/3 (QUIC) + HTTP/2 + TLS 1.3 enabled.
     *
     * Never blocks the UI thread, never throws on non-GMS/Robolectric environments, and strictly
     * rejects `Fallback-Cronet-Provider` (Java HttpURLConnection HTTP/1.1) so non-GMS devices
     * always stay on CineStream's faster HTTP/2 OkHttp engine.
     */
    fun initializeCronetAsync(context: Context, userAgent: String) {
        if (!cronetInitStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        prewarmExecutor.execute {
            runCatching {
                CronetProviderInstaller.installProvider(appContext)
                    .addOnCompleteListener(prewarmExecutor) { task ->
                        if (task.isSuccessful) {
                            runCatching {
                                cronetEngine = buildNativeCronetEngine(appContext, userAgent)
                            }
                        }
                    }
            }
        }
    }

    private fun buildNativeCronetEngine(context: Context, userAgent: String): CronetEngine? {
        val providers = runCatching { CronetProvider.getAllProviders(context) }.getOrNull() ?: return null
        val nativeProvider = providers
            .filter { provider ->
                provider.isEnabled && provider.name != CronetProvider.PROVIDER_NAME_FALLBACK
            }
            .sortedBy { provider ->
                if (provider.name.contains("Play-Services", ignoreCase = true)) 0 else 1
            }
            .firstOrNull() ?: return null

        return runCatching {
            nativeProvider.createBuilder()
                .setUserAgent(userAgent)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0L)
                .build()
        }.getOrNull()
    }

    fun createAdaptiveHttpDataSourceFactory(
        okHttpClient: OkHttpClient,
        userAgent: String,
        transferListener: TransferListener?
    ): AdaptiveHttpDataSourceFactory {
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            .setTransferListener(transferListener)
        return AdaptiveHttpDataSourceFactory(
            okHttpFactory = okHttpFactory,
            transferListener = transferListener
        )
    }

    /**
     * Extracts the safe `scheme://host[:port]/` root URL from any HTTP(S) media URL so origin
     * pre-warming establishes DNS + TLS/QUIC without touching single-use tokenized media paths.
     */
    fun extractOriginRootUrl(mediaUrl: String?): String? {
        if (mediaUrl.isNullOrBlank()) return null
        val trimmed = mediaUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$portPart/"
    }

    /**
     * Silently warms DNS + TLS/HTTP2/QUIC connection state to the media origin in the background
     * (debounced to once per 60s per origin, zero media bytes downloaded).
     */
    fun prewarmOrigin(
        mediaUrl: String?,
        okHttpClient: OkHttpClient,
        clockMs: () -> Long = SystemClock::elapsedRealtime
    ) {
        val originRoot = extractOriginRootUrl(mediaUrl) ?: return
        val now = clockMs()
        val last = prewarmTimestamps[originRoot]
        if (last != null && now - last < PREWARM_COOLDOWN_MS) return

        if (prewarmTimestamps.size >= MAX_PREWARM_HOSTS) {
            prewarmTimestamps.clear()
        }
        prewarmTimestamps[originRoot] = now

        prewarmExecutor.execute {
            runCatching {
                val quickClient = okHttpClient.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .callTimeout(4, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(originRoot)
                    .head()
                    .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                    .build()
                quickClient.newCall(req).execute().use { /* discard headers; socket & DNS are now warm */ }
            }
        }
    }
}
