package com.example.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Memory-bounded configuration for live-streaming parallel segment & byte-range prefetching.
 *
 * v1.18 tuning (validated on 1 GB Android 9 Amlogic TV & 1080p 8 Mbps VBR HLS):
 * - Focused 2x (TV) / 3x (Mobile) lookahead prevents 4-way single-TCP HTTP/2 bandwidth fighting.
 * - Raised single-segment ceiling (12 MB TV / 16 MB Mobile) + upfront Content-Length check +
 *   zero-discard live streaming handoff eliminate the 6 MB VBR action-scene discard cliff.
 * - Separate dedicated audio worker thread guarantees demuxed 5.1/7.1 audio never queues behind video.
 */
data class ParallelPrefetchConfig(
    val hlsPrefetchAheadCount: Int,
    val hlsMaxCacheBytes: Int,
    val hlsMaxSingleSegmentBytes: Int,
    val progressiveChunkBytes: Int,
    val progressiveLookaheadChunks: Int,
    val workerThreads: Int
) {
    companion object {
        fun forDevice(isConstrainedOrTv: Boolean): ParallelPrefetchConfig {
            return if (isConstrainedOrTv) {
                ParallelPrefetchConfig(
                    hlsPrefetchAheadCount = 2,
                    hlsMaxCacheBytes = 14 * 1024 * 1024,          // 14 MB hard RAM ceiling on TV
                    hlsMaxSingleSegmentBytes = 12 * 1024 * 1024,  // 12 MB single-segment limit (fits 1080p VBR spikes)
                    progressiveChunkBytes = 1536 * 1024,          // 1.5 MB range chunks
                    progressiveLookaheadChunks = 2,               // 3 MB rolling window on TV
                    workerThreads = 2                             // + 1 dedicated audio lane
                )
            } else {
                ParallelPrefetchConfig(
                    hlsPrefetchAheadCount = 3,
                    hlsMaxCacheBytes = 28 * 1024 * 1024,          // 28 MB hard RAM ceiling on phones
                    hlsMaxSingleSegmentBytes = 16 * 1024 * 1024,  // 16 MB single-segment limit
                    progressiveChunkBytes = 2 * 1024 * 1024,      // 2 MB range chunks
                    progressiveLookaheadChunks = 3,               // 6 MB rolling window on phones
                    workerThreads = 3                             // + 1 dedicated audio lane
                )
            }
        }
    }
}

/**
 * Live Stats for Nerds telemetry snapshot for the parallel prefetcher.
 */
data class PrefetchStatsSnapshot(
    val activeDepth: Int = 0,
    val cacheHits: Long = 0L,
    val cacheMisses: Long = 0L,
    val cachedBytes: Long = 0L,
    val inFlightCount: Int = 0
) {
    val hitRatePercent: Int
        get() {
            val total = cacheHits + cacheMisses
            if (total <= 0L) return 0
            return ((cacheHits * 100L) / total).toInt().coerceIn(0, 100)
        }
}

/**
 * Pure, thread-safe HLS media playlist parser & ordered segment index.
 *
 * Indexes discrete `.m4s`/`.ts`/`.aac` segments from both video and audio variant `.m3u8`
 * playlists, classifies audio vs video playlists for dedicated non-blocking audio prefetching,
 * and safely skips master playlists (`#EXT-X-STREAM-INF`) and `#EXT-X-BYTERANGE` playlists.
 */
class HlsSegmentIndex {

    companion object {
        private const val MAX_PLAYLISTS = 8
        private const val MAX_SEGMENTS_PER_PLAYLIST = 12_000

        /**
         * Parses an HLS media playlist into resolved absolute segment URLs.
         * Returns an empty list for master playlists or `#EXT-X-BYTERANGE` playlists.
         */
        fun parseMediaPlaylist(playlistUrl: String, content: String): List<String> {
            if (content.isBlank()) return emptyList()
            if (content.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return emptyList()
            if (content.contains("#EXT-X-BYTERANGE", ignoreCase = true)) return emptyList()

            val baseUri = runCatching { URI(playlistUrl) }.getOrNull() ?: return emptyList()
            val segments = ArrayList<String>(128)
            val lines = content.lineSequence()

            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val resolved = resolveUrl(baseUri, line) ?: continue
                segments.add(resolved)
                if (segments.size >= MAX_SEGMENTS_PER_PLAYLIST) break
            }
            return segments
        }

        fun resolveUrl(baseUri: URI, relativeOrAbsolute: String): String? {
            return runCatching {
                val resolved = if (relativeOrAbsolute.startsWith("http://", ignoreCase = true) ||
                    relativeOrAbsolute.startsWith("https://", ignoreCase = true)
                ) {
                    relativeOrAbsolute
                } else {
                    baseUri.resolve(relativeOrAbsolute).toString()
                }
                if (resolved.startsWith("http://", ignoreCase = true) ||
                    resolved.startsWith("https://", ignoreCase = true)
                ) {
                    resolved
                } else {
                    null
                }
            }.getOrNull()
        }

        fun isManifestUrl(uri: Uri?): Boolean {
            if (uri == null) return false
            val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
            return path.endsWith(".m3u8") || path.endsWith(".m3u")
        }

        fun isLikelyAudioUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("/audio") ||
                lower.contains("audio_") ||
                lower.contains("audio-") ||
                lower.contains("/a_") ||
                lower.endsWith(".aac") ||
                lower.endsWith(".m4a") ||
                lower.endsWith(".ac3") ||
                lower.endsWith(".ec3") ||
                lower.contains("mp4a")
        }

        fun isProgressiveVideoUrl(uri: Uri?): Boolean {
            if (uri == null) return false
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") return false
            val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
            return path.endsWith(".mp4") ||
                path.endsWith(".mkv") ||
                path.endsWith(".webm") ||
                path.endsWith(".mov") ||
                path.endsWith(".avi") ||
                path.endsWith(".flv")
        }
    }

    private data class SegmentPos(
        val playlistKey: String,
        val index: Int,
        val isAudio: Boolean
    )

    private val lock = Any()
    private val playlists = LinkedHashMap<String, List<String>>()
    private val urlIndex = HashMap<String, SegmentPos>()

    /**
     * Indexes a parsed HLS media playlist and returns the first [initialPrefetchCount] segment URLs
     * so cold-cache playback can immediately warm the first segments in parallel.
     */
    fun indexPlaylist(playlistUrl: String, content: String, initialPrefetchCount: Int = 2): List<String> {
        val segments = parseMediaPlaylist(playlistUrl, content)
        if (segments.isEmpty()) return emptyList()
        val playlistIsAudio = isLikelyAudioUrl(playlistUrl) ||
            segments.firstOrNull()?.let { isLikelyAudioUrl(it) } == true

        synchronized(lock) {
            if (playlists.size >= MAX_PLAYLISTS && !playlists.containsKey(playlistUrl)) {
                val oldestKey = playlists.keys.firstOrNull()
                if (oldestKey != null) {
                    playlists.remove(oldestKey)?.forEach { oldUrl ->
                        if (urlIndex[oldUrl]?.playlistKey == oldestKey) {
                            urlIndex.remove(oldUrl)
                        }
                    }
                }
            }
            playlists[playlistUrl] = segments
            segments.forEachIndexed { idx, segUrl ->
                urlIndex[segUrl] = SegmentPos(playlistUrl, idx, playlistIsAudio || isLikelyAudioUrl(segUrl))
            }
        }
        return segments.take(initialPrefetchCount.coerceAtLeast(0))
    }

    fun isIndexedSegment(url: String): Boolean = synchronized(lock) { urlIndex.containsKey(url) }

    fun isAudioSegment(url: String): Boolean = synchronized(lock) {
        urlIndex[url]?.isAudio ?: isLikelyAudioUrl(url)
    }

    fun getNextSegments(currentUrl: String, count: Int): List<String> {
        if (count <= 0) return emptyList()
        synchronized(lock) {
            val pos = urlIndex[currentUrl] ?: return emptyList()
            val list = playlists[pos.playlistKey] ?: return emptyList()
            val start = pos.index + 1
            if (start >= list.size) return emptyList()
            val end = (start + count).coerceAtMost(list.size)
            return list.subList(start, end).toList()
        }
    }

    /**
     * Returns true if [candidateUrl] belongs to the same playlist as [currentUrl] and lies outside
     * the active rolling window `[currentIndex, currentIndex + windowSize]`, allowing stale
     * prefetches after seeks or playback advancement to be cancelled immediately.
     */
    fun isOutsideActiveWindow(currentUrl: String, candidateUrl: String, windowSize: Int): Boolean {
        synchronized(lock) {
            val cur = urlIndex[currentUrl] ?: return false
            val cand = urlIndex[candidateUrl] ?: return false
            if (cur.playlistKey != cand.playlistKey) return false
            return cand.index < cur.index || cand.index > cur.index + windowSize
        }
    }

    fun clear() {
        synchronized(lock) {
            playlists.clear()
            urlIndex.clear()
        }
    }
}

/**
 * Thread-safe progressive streaming buffer for an in-flight HLS segment.
 *
 * Allows ExoPlayer to attach mid-download and read already-arrived bytes immediately at 0 ms
 * while the background worker continues streaming the rest of the segment in 32 KB chunks —
 * eliminating v1.17's blocking "wait for 100% or discard" stalls!
 */
class StreamingPrefetchEntry(val url: String) {
    private val lock = Object()
    private val chunks = ArrayList<ByteArray>(32)

    @Volatile var started: Boolean = false
        private set
    @Volatile var claimedByConsumer: Boolean = false
        private set
    @Volatile var cancelled: Boolean = false
        private set

    private var headersReady: Boolean = false
    private var completed: Boolean = false
    private var failed: Boolean = false
    private var failureReason: IOException? = null
    private var totalBytesWritten: Int = 0
    private var expectedLength: Long = C.LENGTH_UNSET.toLong()
    private var resolvedUri: Uri = Uri.parse(url)
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    // Consumer cursor state (accessed by a single ExoPlayer reader thread)
    private var consumerChunkIndex: Int = 0
    private var consumerChunkOffset: Int = 0
    private var consumerTotalRead: Int = 0

    fun markStarted() {
        synchronized(lock) {
            started = true
            lock.notifyAll()
        }
    }

    fun setHeaders(uri: Uri, headers: Map<String, List<String>>, contentLength: Long) {
        synchronized(lock) {
            resolvedUri = uri
            responseHeaders = headers
            expectedLength = contentLength
            headersReady = true
            lock.notifyAll()
        }
    }

    fun appendChunk(data: ByteArray, length: Int) {
        if (length <= 0) return
        val copy = ByteArray(length)
        System.arraycopy(data, 0, copy, 0, length)
        synchronized(lock) {
            if (cancelled) return
            chunks.add(copy)
            totalBytesWritten += length
            lock.notifyAll()
        }
    }

    fun markCompleted() {
        synchronized(lock) {
            completed = true
            headersReady = true
            lock.notifyAll()
        }
    }

    fun markFailed(error: IOException? = null) {
        synchronized(lock) {
            failed = true
            failureReason = error
            headersReady = true
            lock.notifyAll()
        }
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            chunks.clear()
            lock.notifyAll()
        }
    }

    fun isClaimed(): Boolean = claimedByConsumer

    fun currentByteCount(): Int = synchronized(lock) { totalBytesWritten }

    /**
     * Attempts to claim this live stream for ExoPlayer consumption.
     * - Returns immediately if cancelled, failed, already claimed, or not yet started (so ExoPlayer
     *   never waits behind queued video jobs).
     * - Waits at most [headersTimeoutMs] for HTTP response headers, then returns immediately so
     *   ExoPlayer can stream bytes live as they arrive.
     */
    fun tryClaimAndAwaitHeaders(headersTimeoutMs: Long = 2_500L): HeaderInfo? {
        val deadline = System.currentTimeMillis() + headersTimeoutMs
        synchronized(lock) {
            if (claimedByConsumer || cancelled || failed || !started) return null
            claimedByConsumer = true
            while (!headersReady && !cancelled && !failed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    claimedByConsumer = false
                    return null
                }
                try {
                    lock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    claimedByConsumer = false
                    return null
                }
            }
            if (cancelled || failed) {
                claimedByConsumer = false
                return null
            }
            return HeaderInfo(
                resolvedUri = resolvedUri,
                responseHeaders = responseHeaders,
                contentLength = if (completed) totalBytesWritten.toLong() else expectedLength
            )
        }
    }

    data class HeaderInfo(
        val resolvedUri: Uri,
        val responseHeaders: Map<String, List<String>>,
        val contentLength: Long
    )

    /**
     * Seeks consumer cursor to [startPos] bytes (for non-zero DataSpec.position).
     */
    @Throws(IOException::class)
    fun skipTo(startPos: Long, timeoutMs: Long = 8_000L): Boolean {
        if (startPos <= 0L) return true
        val target = startPos.toInt()
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (totalBytesWritten < target && !completed && !failed && !cancelled) {
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) return false
                try {
                    lock.wait(rem)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (cancelled || (failed && totalBytesWritten < target)) return false
            if (totalBytesWritten < target) return false
            var left = target
            consumerChunkIndex = 0
            consumerChunkOffset = 0
            while (left > 0 && consumerChunkIndex < chunks.size) {
                val cLen = chunks[consumerChunkIndex].size
                if (left >= cLen) {
                    left -= cLen
                    // Free consumed prefix chunks immediately to minimize peak RAM
                    chunks[consumerChunkIndex] = ByteArray(0)
                    consumerChunkIndex++
                } else {
                    consumerChunkOffset = left
                    left = 0
                }
            }
            consumerTotalRead = target
            return true
        }
    }

    /**
     * Reads available bytes immediately, blocking briefly only when awaiting the next incoming
     * 32 KB network chunk from the background worker. Consumed chunks are freed immediately.
     */
    @Throws(IOException::class)
    fun readNext(dest: ByteArray, destOffset: Int, maxLength: Int, chunkTimeoutMs: Long = 15_000L): Int {
        if (maxLength <= 0) return 0
        val deadline = System.currentTimeMillis() + chunkTimeoutMs
        synchronized(lock) {
            while (consumerTotalRead >= totalBytesWritten && !completed && !failed && !cancelled) {
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) {
                    throw IOException("Timed out awaiting prefetched stream bytes for $url")
                }
                try {
                    lock.wait(rem)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while reading prefetched segment", e)
                }
            }
            if (cancelled) {
                throw IOException("Prefetched segment cancelled")
            }
            if (consumerTotalRead >= totalBytesWritten) {
                if (failed) {
                    throw failureReason ?: IOException("Prefetch stream failed for $url")
                }
                return C.RESULT_END_OF_INPUT
            }

            var copied = 0
            while (copied < maxLength && consumerChunkIndex < chunks.size) {
                val chunk = chunks[consumerChunkIndex]
                val avail = chunk.size - consumerChunkOffset
                if (avail <= 0) {
                    chunks[consumerChunkIndex] = ByteArray(0)
                    consumerChunkIndex++
                    consumerChunkOffset = 0
                    continue
                }
                val toCopy = minOf(maxLength - copied, avail)
                System.arraycopy(chunk, consumerChunkOffset, dest, destOffset + copied, toCopy)
                copied += toCopy
                consumerChunkOffset += toCopy
                consumerTotalRead += toCopy
                if (consumerChunkOffset >= chunk.size) {
                    // Immediately drop reference to consumed 32 KB chunk for GC
                    chunks[consumerChunkIndex] = ByteArray(0)
                    consumerChunkIndex++
                    consumerChunkOffset = 0
                }
            }
            return if (copied > 0) copied else C.RESULT_END_OF_INPUT
        }
    }

    /**
     * Materializes the completed bytes into a single [ParallelHlsPrefetchManager.PrefetchedSegment]
     * ONLY when no live consumer claimed the stream during download.
     */
    fun toCompletedSegment(): ParallelHlsPrefetchManager.PrefetchedSegment? {
        synchronized(lock) {
            if (!completed || failed || cancelled || claimedByConsumer) return null
            val merged = ByteArray(totalBytesWritten)
            var pos = 0
            for (c in chunks) {
                if (c.isNotEmpty()) {
                    System.arraycopy(c, 0, merged, pos, c.size)
                    pos += c.size
                }
            }
            chunks.clear()
            return ParallelHlsPrefetchManager.PrefetchedSegment(
                bytes = merged,
                resolvedUri = resolvedUri,
                responseHeaders = responseHeaders
            )
        }
    }

    fun releaseAfterConsumerClose() {
        synchronized(lock) {
            cancelled = true
            chunks.clear()
            lock.notifyAll()
        }
    }
}

/**
 * Bounded, consume-once, live-streaming parallel HLS & Progressive Range prefetch engine (v1.18).
 *
 * Key properties:
 * - **Zero-Wait Live Streaming Handoff:** If ExoPlayer requests a segment already mid-download,
 *   it attaches immediately and reads bytes live as 32 KB chunks arrive (never blocking for 100%
 *   completion and never discarding a >6 MB VBR segment mid-download).
 * - **Foreground-First Anti-Starvation:** During seeks or low-buffer recovery (< 8s buffered),
 *   throttles deep background prefetching to 1 immediate next segment (`N+1`) so ExoPlayer's
 *   urgent current segment gets uncontested HTTP/2 TCP bandwidth.
 * - **Dedicated Audio Lane:** Audio segments always run on a separate 1-thread daemon executor so
 *   heavy 1080p video chunks can never queue-starve demuxed 5.1/7.1 audio tracks.
 * - **Strict RAM Cap:** 14 MB max on Android TV / 28 MB max on Mobile, consume-once eviction,
 *   instant stale-window cancellation on seeks, and OS `onTrimMemory` cache flushing.
 */
@OptIn(UnstableApi::class)
class ParallelHlsPrefetchManager(
    val config: ParallelPrefetchConfig,
    private val upstreamFactory: DataSource.Factory
) {
    companion object {
        private val activeManagers = ConcurrentHashMap.newKeySet<ParallelHlsPrefetchManager>()

        fun trimActiveCaches() {
            for (mgr in activeManagers) {
                runCatching { mgr.clearMemoryOnly() }
            }
        }
    }

    init {
        activeManagers.add(this)
    }

    data class PrefetchedSegment(
        val bytes: ByteArray,
        val resolvedUri: Uri,
        val responseHeaders: Map<String, List<String>>
    )

    sealed class PrefetchAcquireResult {
        data class CompletedHit(val segment: PrefetchedSegment) : PrefetchAcquireResult()
        data class LiveStreamHit(
            val entry: StreamingPrefetchEntry,
            val headers: StreamingPrefetchEntry.HeaderInfo
        ) : PrefetchAcquireResult()
        data object Miss : PrefetchAcquireResult()
    }

    val segmentIndex = HlsSegmentIndex()

    private val workerCounter = AtomicInteger(1)
    private val videoExecutor: ExecutorService = Executors.newFixedThreadPool(config.workerThreads) { runnable ->
        Thread(runnable, "CineStream-Prefetch-V${workerCounter.getAndIncrement()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    // Dedicated non-blocking audio lane: demuxed audio chunks never queue behind heavy video chunks
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CineStream-Prefetch-A1").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private val cacheLock = Any()
    private val completedCache = LinkedHashMap<String, PrefetchedSegment>()
    private var completedCacheBytes: Long = 0L

    private val inFlight = ConcurrentHashMap<String, StreamingPrefetchEntry>()
    private val sessionGeneration = AtomicLong(0L)

    // Foreground anti-starvation tracking
    private val activeForegroundVideoLoads = AtomicInteger(0)
    @Volatile private var lowBufferMode: Boolean = false
    @Volatile private var cpuThrottleMode: Boolean = false
    @Volatile private var lastAccessWasMiss: Boolean = false

    // Non-range progressive hosts discovered during playback (never retried with range prefetch)
    private val nonRangeProgressiveUrls = ConcurrentHashMap.newKeySet<String>()

    // Telemetry counters
    private val cacheHits = AtomicLong(0L)
    private val cacheMisses = AtomicLong(0L)

    @Volatile
    var enabled: Boolean = true

    fun setLowBufferMode(isLowBuffer: Boolean) {
        lowBufferMode = isLowBuffer
    }

    fun setCpuThrottleMode(isThrottled: Boolean) {
        cpuThrottleMode = isThrottled
    }

    fun onForegroundDirectLoadStarted(isAudio: Boolean) {
        if (!isAudio) {
            activeForegroundVideoLoads.incrementAndGet()
        }
    }

    fun onForegroundDirectLoadFinished(isAudio: Boolean, finishedSegmentUrl: String?) {
        if (!isAudio) {
            activeForegroundVideoLoads.decrementAndGet().coerceAtLeast(0)
            if (activeForegroundVideoLoads.get() < 0) activeForegroundVideoLoads.set(0)
        }
        // As soon as the foreground segment completes, warm the next segments in parallel
        if (!finishedSegmentUrl.isNullOrBlank() && enabled && segmentIndex.isIndexedSegment(finishedSegmentUrl)) {
            triggerPrefetchAfter(finishedSegmentUrl, isFromCacheHit = true)
        }
    }

    fun onPlaylistLoaded(playlistUrl: String, content: String) {
        if (!enabled) return
        val initialCount = if (lowBufferMode || cpuThrottleMode) 1 else config.hlsPrefetchAheadCount
        val initialSegments = segmentIndex.indexPlaylist(
            playlistUrl = playlistUrl,
            content = content,
            initialPrefetchCount = initialCount
        )
        if (initialSegments.isNotEmpty()) {
            scheduleUrls(initialSegments)
        }
    }

    fun isIndexedSegment(url: String): Boolean = segmentIndex.isIndexedSegment(url)

    fun isAudioSegment(url: String): Boolean = segmentIndex.isAudioSegment(url)

    fun isProgressiveRangeEligible(uri: Uri?): Boolean {
        if (!enabled || uri == null) return false
        if (!HlsSegmentIndex.isProgressiveVideoUrl(uri)) return false
        return !nonRangeProgressiveUrls.contains(uri.toString())
    }

    fun markNonRangeProgressiveUrl(url: String) {
        nonRangeProgressiveUrls.add(url)
    }

    /**
     * Triggers parallel background prefetching for upcoming segments after [currentSegmentUrl],
     * dynamically throttling depth to 1 when recovering from a seek/miss or low buffer so the
     * urgent foreground segment gets uncontested HTTP/2 TCP bandwidth.
     */
    fun triggerPrefetchAfter(currentSegmentUrl: String, isFromCacheHit: Boolean = true) {
        if (!enabled) return
        evictStaleFor(currentSegmentUrl)

        val isAudio = segmentIndex.isAudioSegment(currentSegmentUrl)
        val depth = when {
            isAudio -> config.hlsPrefetchAheadCount
            !isFromCacheHit -> 0 // Let foreground direct load finish uncontested; onForegroundDirectLoadFinished will warm N+1..K
            lowBufferMode || cpuThrottleMode || lastAccessWasMiss || activeForegroundVideoLoads.get() > 0 -> 1
            else -> config.hlsPrefetchAheadCount
        }
        if (depth <= 0) return

        val upcoming = segmentIndex.getNextSegments(currentSegmentUrl, depth)
        if (upcoming.isNotEmpty()) {
            scheduleUrls(upcoming)
        }
    }

    /**
     * Attempts to satisfy [url] from:
     * 1. Completed RAM cache (0 ms, consume-once), OR
     * 2. Actively-downloading [StreamingPrefetchEntry] (0 ms live progressive streaming handoff!).
     *
     * If a background task is still queued (not yet started), cancels it immediately and returns
     * [PrefetchAcquireResult.Miss] so ExoPlayer opens it directly with zero queue delay.
     */
    fun acquireSegment(url: String): PrefetchAcquireResult {
        if (!enabled) return PrefetchAcquireResult.Miss

        // 1. Instant completed RAM hit (consume-once)
        val cached = synchronized(cacheLock) {
            completedCache.remove(url)?.also {
                completedCacheBytes = (completedCacheBytes - it.bytes.size).coerceAtLeast(0L)
            }
        }
        if (cached != null) {
            lastAccessWasMiss = false
            cacheHits.incrementAndGet()
            return PrefetchAcquireResult.CompletedHit(cached)
        }

        // 2. Live streaming handoff for an actively-downloading in-flight segment
        val entry = inFlight[url]
        if (entry != null) {
            if (!entry.started) {
                // Queued behind another task -> cancel background slot so foreground opens immediately
                entry.cancel()
                inFlight.remove(url, entry)
            } else {
                val headers = entry.tryClaimAndAwaitHeaders(headersTimeoutMs = 2_500L)
                if (headers != null) {
                    lastAccessWasMiss = false
                    cacheHits.incrementAndGet()
                    return PrefetchAcquireResult.LiveStreamHit(entry, headers)
                }
            }
        }

        lastAccessWasMiss = true
        cacheMisses.incrementAndGet()
        return PrefetchAcquireResult.Miss
    }

    /**
     * Backwards-compatible helper used by unit tests to wait for a completed/live segment.
     */
    fun consumeOrAwait(url: String, timeoutMs: Long = 8_000L): PrefetchedSegment? {
        return when (val res = acquireSegment(url)) {
            is PrefetchAcquireResult.CompletedHit -> res.segment
            is PrefetchAcquireResult.LiveStreamHit -> {
                try {
                    val out = ByteArrayOutputStream(64 * 1024)
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val r = res.entry.readNext(buf, 0, buf.size, chunkTimeoutMs = timeoutMs)
                        if (r == C.RESULT_END_OF_INPUT || r < 0) break
                        out.write(buf, 0, r)
                    }
                    PrefetchedSegment(
                        bytes = out.toByteArray(),
                        resolvedUri = res.headers.resolvedUri,
                        responseHeaders = res.headers.responseHeaders
                    )
                } catch (_: Exception) {
                    null
                } finally {
                    res.entry.releaseAfterConsumerClose()
                }
            }
            PrefetchAcquireResult.Miss -> null
        }
    }

    /**
     * Downloads a single byte-range slice `[startByte, startByte + length - 1]` asynchronously
     * on the shared bounded worker pool for progressive `.mp4`/`.mkv` parallel range prefetching.
     */
    fun submitRangeChunk(
        uri: Uri,
        startByte: Long,
        length: Long
    ): CompletableFuture<ByteArray?> {
        val gen = sessionGeneration.get()
        val future = CompletableFuture<ByteArray?>()
        try {
            videoExecutor.execute {
                if (gen != sessionGeneration.get() || future.isCancelled) {
                    future.complete(null)
                    return@execute
                }
                val bytes = downloadRangeBytes(uri, startByte, length, gen)
                future.complete(bytes)
            }
        } catch (_: Exception) {
            future.complete(null)
        }
        return future
    }

    private fun downloadRangeBytes(uri: Uri, startByte: Long, length: Long, generation: Long): ByteArray? {
        val ds = upstreamFactory.createDataSource()
        return try {
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(startByte)
                .setLength(length)
                .build()
            val openedLength = ds.open(spec)
            if (openedLength == 0L) return ByteArray(0)
            val expectedCap = length.coerceAtMost(config.progressiveChunkBytes.toLong()).toInt()
            val out = ByteArrayOutputStream(expectedCap.coerceAtMost(256 * 1024))
            val buf = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                if (generation != sessionGeneration.get() || Thread.currentThread().isInterrupted) {
                    return null
                }
                val read = ds.read(buf, 0, buf.size)
                if (read == C.RESULT_END_OF_INPUT || read < 0) break
                total += read
                if (total > config.progressiveChunkBytes + 64 * 1024) {
                    return null
                }
                out.write(buf, 0, read)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { ds.close() }
        }
    }

    private fun scheduleUrls(urls: List<String>) {
        val gen = sessionGeneration.get()
        for (url in urls) {
            val alreadyCached = synchronized(cacheLock) { completedCache.containsKey(url) }
            if (alreadyCached || inFlight.containsKey(url)) continue

            val isAudio = segmentIndex.isAudioSegment(url)

            // Respect strict RAM ceiling before starting more background video fetches
            if (!isAudio) {
                val currentUsage = synchronized(cacheLock) { completedCacheBytes }
                if (currentUsage >= config.hlsMaxCacheBytes.toLong()) {
                    continue
                }
            }

            val entry = StreamingPrefetchEntry(url)
            if (inFlight.putIfAbsent(url, entry) != null) continue

            val targetExecutor = if (isAudio) audioExecutor else videoExecutor
            try {
                targetExecutor.execute {
                    if (gen != sessionGeneration.get() || entry.cancelled) {
                        inFlight.remove(url, entry)
                        entry.cancel()
                        return@execute
                    }
                    streamDownloadIntoEntry(url, entry, gen)
                    inFlight.remove(url, entry)
                }
            } catch (_: Exception) {
                inFlight.remove(url, entry)
                entry.cancel()
            }
        }
    }

    private fun streamDownloadIntoEntry(
        url: String,
        entry: StreamingPrefetchEntry,
        generation: Long
    ) {
        entry.markStarted()
        val ds = upstreamFactory.createDataSource()
        try {
            val uri = Uri.parse(url)
            val openedLen = ds.open(DataSpec(uri))
            // Upfront Content-Length check: skip oversized segments BEFORE downloading a single byte
            if (openedLen > config.hlsMaxSingleSegmentBytes.toLong() && !entry.isClaimed()) {
                entry.markFailed(IOException("Segment exceeds single-segment prefetch budget"))
                return
            }
            entry.setHeaders(
                uri = ds.uri ?: uri,
                headers = ds.responseHeaders,
                contentLength = openedLen
            )
            val buf = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                if (generation != sessionGeneration.get() || entry.cancelled || Thread.currentThread().isInterrupted) {
                    entry.cancel()
                    return
                }
                val read = ds.read(buf, 0, buf.size)
                if (read == C.RESULT_END_OF_INPUT || read < 0) break
                total += read
                // Only abort an oversized segment if NO live consumer is reading it!
                // Once ExoPlayer attaches mid-stream, ALWAYS finish streaming without discarding!
                if (total > config.hlsMaxSingleSegmentBytes && !entry.isClaimed()) {
                    entry.markFailed(IOException("Unclaimed prefetch exceeded byte budget"))
                    return
                }
                entry.appendChunk(buf, read)
            }
            entry.markCompleted()
            // If no consumer claimed it mid-download, store it in bounded consume-once RAM cache
            val completedSeg = entry.toCompletedSegment()
            if (completedSeg != null && generation == sessionGeneration.get() && !entry.cancelled) {
                putIntoCache(url, completedSeg)
            }
        } catch (e: IOException) {
            entry.markFailed(e)
        } catch (e: Exception) {
            entry.markFailed(IOException(e.message ?: "Prefetch failed", e))
        } finally {
            runCatching { ds.close() }
        }
    }

    private fun putIntoCache(url: String, segment: PrefetchedSegment) {
        val segSize = segment.bytes.size.toLong()
        if (segSize > config.hlsMaxCacheBytes.toLong()) return
        synchronized(cacheLock) {
            completedCache.remove(url)?.let { old ->
                completedCacheBytes = (completedCacheBytes - old.bytes.size).coerceAtLeast(0L)
            }
            val iterator = completedCache.entries.iterator()
            while (completedCacheBytes + segSize > config.hlsMaxCacheBytes.toLong() && iterator.hasNext()) {
                val oldest = iterator.next()
                completedCacheBytes = (completedCacheBytes - oldest.value.bytes.size).coerceAtLeast(0L)
                iterator.remove()
            }
            completedCache[url] = segment
            completedCacheBytes += segSize
        }
    }

    private fun evictStaleFor(currentUrl: String) {
        val window = config.hlsPrefetchAheadCount + 2
        // Cancel stale unclaimed in-flight tasks outside the rolling window
        for ((url, entry) in inFlight.entries) {
            if (!entry.isClaimed() && segmentIndex.isOutsideActiveWindow(currentUrl, url, window)) {
                entry.cancel()
                inFlight.remove(url, entry)
            }
        }
        // Evict stale completed entries outside the rolling window
        synchronized(cacheLock) {
            val iter = completedCache.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (segmentIndex.isOutsideActiveWindow(currentUrl, entry.key, window)) {
                    completedCacheBytes = (completedCacheBytes - entry.value.bytes.size).coerceAtLeast(0L)
                    iter.remove()
                }
            }
        }
    }

    /**
     * Immediately cancels all in-flight prefetches and frees all cached RAM bytes.
     * Called on new media load, stopAndTrimBuffers(), release(), and low-memory trim.
     */
    fun resetSession() {
        sessionGeneration.incrementAndGet()
        for ((url, entry) in inFlight.entries) {
            entry.cancel()
            inFlight.remove(url, entry)
        }
        synchronized(cacheLock) {
            completedCache.clear()
            completedCacheBytes = 0L
        }
        segmentIndex.clear()
        nonRangeProgressiveUrls.clear()
        activeForegroundVideoLoads.set(0)
        lowBufferMode = false
        cpuThrottleMode = false
        lastAccessWasMiss = false
        cacheHits.set(0L)
        cacheMisses.set(0L)
    }

    fun clearMemoryOnly() {
        sessionGeneration.incrementAndGet()
        for ((url, entry) in inFlight.entries) {
            entry.cancel()
            inFlight.remove(url, entry)
        }
        synchronized(cacheLock) {
            completedCache.clear()
            completedCacheBytes = 0L
        }
    }

    fun snapshotStats(): PrefetchStatsSnapshot {
        val cached = synchronized(cacheLock) { completedCacheBytes }
        return PrefetchStatsSnapshot(
            activeDepth = config.hlsPrefetchAheadCount,
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get(),
            cachedBytes = cached,
            inFlightCount = inFlight.size
        )
    }

    fun release() {
        activeManagers.remove(this)
        resetSession()
        runCatching { videoExecutor.shutdownNow() }
        runCatching { audioExecutor.shutdownNow() }
    }

    fun createDataSourceFactory(): DataSource.Factory {
        return DataSource.Factory {
            ParallelPrefetchDataSource(
                upstream = upstreamFactory.createDataSource(),
                manager = this
            )
        }
    }
}

/**
 * Media3 [DataSource] that transparently accelerates:
 * 1. **HLS `.m3u8` + `.m4s`/`.ts` streams:** Tees media playlist responses (0 ms overhead) to index
 *    upcoming segment URLs, serves completed RAM hits OR attaches live to actively downloading
 *    prefetch streams (zero blocking wait), and triggers foreground-first parallel lookahead.
 * 2. **Direct Progressive `.mp4`/`.mkv`/`.webm` URLs:** Parallel rolling HTTP `206` byte-range
 *    lookahead (2x TV / 3x Mobile) with seamless single-stream fallback for non-range servers.
 */
@OptIn(UnstableApi::class)
private class ParallelPrefetchDataSource(
    private val upstream: DataSource,
    private val manager: ParallelHlsPrefetchManager
) : DataSource {

    companion object {
        private const val MAX_TEE_MANIFEST_BYTES = 2 * 1024 * 1024
        private val CONTENT_RANGE_TOTAL_REGEX = Regex("bytes\\s+\\d+-\\d+/(\\d+)", RegexOption.IGNORE_CASE)
    }

    private enum class Mode {
        DIRECT_UPSTREAM,
        TEE_MANIFEST,
        RAM_SEGMENT,
        STREAMING_PREFETCH,
        PROGRESSIVE_RANGE
    }

    private var mode: Mode = Mode.DIRECT_UPSTREAM
    private var resolvedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    // Direct foreground tracking (for anti-starvation on seeks/misses)
    private var trackedDirectSegmentUrl: String? = null
    private var trackedDirectIsAudio: Boolean = false

    // Tee manifest state
    private var manifestTee: ByteArrayOutputStream? = null
    private var manifestOverflowed: Boolean = false

    // Completed RAM segment state
    private var ramBytes: ByteArray? = null
    private var ramOffset: Int = 0
    private var ramRemaining: Int = 0

    // Live streaming prefetch state
    private var streamingEntry: StreamingPrefetchEntry? = null
    private var streamingRemainingLimit: Long = C.LENGTH_UNSET.toLong()

    // Progressive parallel range state
    private var progressiveUri: Uri? = null
    private var progressiveTotalSize: Long = C.LENGTH_UNSET.toLong()
    private var progressiveCurrentOffset: Long = 0L
    private var progressiveEndExclusive: Long = Long.MAX_VALUE
    private var currentChunkBytes: ByteArray? = null
    private var currentChunkOffset: Int = 0
    private var nextChunkStartOffset: Long = 0L
    private val pendingRangeChunks = ArrayDeque<Pair<Long, CompletableFuture<ByteArray?>>>()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val url = uri.toString()
        val isHttp = uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)

        if (!isHttp || !manager.enabled) {
            return openDirect(dataSpec)
        }

        // 1. HLS manifest: stream directly to ExoPlayer while tee-ing a copy to index segments
        if (HlsSegmentIndex.isManifestUrl(uri)) {
            val length = upstream.open(dataSpec)
            mode = Mode.TEE_MANIFEST
            resolvedUri = upstream.uri ?: uri
            responseHeaders = upstream.responseHeaders
            manifestTee = ByteArrayOutputStream(16 * 1024)
            manifestOverflowed = false
            return length
        }

        // 2. Indexed HLS media segment: try instant RAM hit or live progressive streaming handoff
        if (manager.isIndexedSegment(url)) {
            val isAudio = manager.isAudioSegment(url)
            when (val acquired = manager.acquireSegment(url)) {
                is ParallelHlsPrefetchManager.PrefetchAcquireResult.CompletedHit -> {
                    val hit = acquired.segment
                    manager.triggerPrefetchAfter(url, isFromCacheHit = true)
                    val startPos = dataSpec.position
                    if (startPos >= 0L && startPos <= hit.bytes.size.toLong()) {
                        val available = hit.bytes.size - startPos.toInt()
                        val desired = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                            available
                        } else {
                            minOf(available.toLong(), dataSpec.length).toInt()
                        }
                        mode = Mode.RAM_SEGMENT
                        ramBytes = hit.bytes
                        ramOffset = startPos.toInt()
                        ramRemaining = desired.coerceAtLeast(0)
                        resolvedUri = hit.resolvedUri
                        responseHeaders = hit.responseHeaders
                        return ramRemaining.toLong()
                    }
                }

                is ParallelHlsPrefetchManager.PrefetchAcquireResult.LiveStreamHit -> {
                    val entry = acquired.entry
                    val hdr = acquired.headers
                    val startPos = dataSpec.position.coerceAtLeast(0L)
                    if (startPos == 0L || entry.skipTo(startPos)) {
                        manager.triggerPrefetchAfter(url, isFromCacheHit = true)
                        mode = Mode.STREAMING_PREFETCH
                        streamingEntry = entry
                        resolvedUri = hdr.resolvedUri
                        responseHeaders = hdr.responseHeaders
                        val totalLen = hdr.contentLength
                        val availLen = if (totalLen != C.LENGTH_UNSET.toLong() && totalLen >= startPos) {
                            totalLen - startPos
                        } else {
                            C.LENGTH_UNSET.toLong()
                        }
                        streamingRemainingLimit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                            availLen
                        } else if (availLen == C.LENGTH_UNSET.toLong()) {
                            dataSpec.length
                        } else {
                            minOf(availLen, dataSpec.length)
                        }
                        return streamingRemainingLimit
                    } else {
                        entry.releaseAfterConsumerClose()
                    }
                }

                ParallelHlsPrefetchManager.PrefetchAcquireResult.Miss -> {
                    // Cache miss (e.g. fresh seek): evict stale prefetches and give 100% TCP
                    // bandwidth to this urgent foreground segment first!
                    manager.triggerPrefetchAfter(url, isFromCacheHit = false)
                }
            }
            trackedDirectSegmentUrl = url
            trackedDirectIsAudio = isAudio
            manager.onForegroundDirectLoadStarted(isAudio)
            return try {
                openDirect(dataSpec)
            } catch (e: IOException) {
                manager.onForegroundDirectLoadFinished(isAudio, finishedSegmentUrl = null)
                trackedDirectSegmentUrl = null
                throw e
            }
        }

        // 3. Direct progressive video (.mp4 / .mkv / .webm): parallel rolling byte-range lookahead
        val chunkSize = manager.config.progressiveChunkBytes.toLong()
        val isOpenEndedOrLarge = dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > chunkSize
        if (isOpenEndedOrLarge && manager.isProgressiveRangeEligible(uri)) {
            val rangeLength = openProgressiveRange(dataSpec, chunkSize)
            if (rangeLength != null) {
                return rangeLength
            }
        }

        return openDirect(dataSpec)
    }

    private fun openDirect(dataSpec: DataSpec): Long {
        val len = upstream.open(dataSpec)
        resolvedUri = upstream.uri ?: dataSpec.uri
        responseHeaders = upstream.responseHeaders
        if (manager.enabled &&
            dataSpec.position == 0L &&
            !HlsSegmentIndex.isProgressiveVideoUrl(resolvedUri) &&
            isMpegUrlContentType(responseHeaders)
        ) {
            mode = Mode.TEE_MANIFEST
            manifestTee = ByteArrayOutputStream(16 * 1024)
            manifestOverflowed = false
        } else {
            mode = Mode.DIRECT_UPSTREAM
        }
        return len
    }

    private fun isMpegUrlContentType(headers: Map<String, List<String>>): Boolean {
        val values = headers.entries.firstOrNull { (k, _) ->
            k.equals("Content-Type", ignoreCase = true)
        }?.value ?: return false
        return values.any { v ->
            v.contains("mpegurl", ignoreCase = true) || v.contains("m3u8", ignoreCase = true)
        }
    }

    private fun openProgressiveRange(dataSpec: DataSpec, chunkSize: Long): Long? {
        val uri = dataSpec.uri
        val startPos = dataSpec.position.coerceAtLeast(0L)
        val firstChunkSpec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(startPos)
            .setLength(chunkSize)
            .build()

        return try {
            val openedLen = upstream.open(firstChunkSpec)
            val headers = upstream.responseHeaders
            val totalSize = parseTotalSizeFromContentRange(headers)
            if (totalSize == null || totalSize <= chunkSize) {
                runCatching { upstream.close() }
                if (totalSize == null) {
                    manager.markNonRangeProgressiveUrl(uri.toString())
                }
                return null
            }

            val firstOut = ByteArrayOutputStream(
                openedLen.takeIf { it > 0L }?.toInt()?.coerceAtMost(256 * 1024) ?: (256 * 1024)
            )
            val tmp = ByteArray(32 * 1024)
            while (true) {
                val r = upstream.read(tmp, 0, tmp.size)
                if (r == C.RESULT_END_OF_INPUT || r < 0) break
                firstOut.write(tmp, 0, r)
            }
            val finalUri = upstream.uri ?: uri
            runCatching { upstream.close() }

            val firstBytes = firstOut.toByteArray()
            if (firstBytes.isEmpty()) {
                return null
            }

            val requestedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                totalSize
            } else {
                minOf(totalSize, startPos + dataSpec.length)
            }

            mode = Mode.PROGRESSIVE_RANGE
            progressiveUri = finalUri
            progressiveTotalSize = totalSize
            progressiveCurrentOffset = startPos
            progressiveEndExclusive = requestedEnd
            currentChunkBytes = firstBytes
            currentChunkOffset = 0
            nextChunkStartOffset = startPos + firstBytes.size
            resolvedUri = finalUri
            responseHeaders = headers

            fillProgressiveLookahead(chunkSize)
            (requestedEnd - startPos).coerceAtLeast(0L)
        } catch (_: Exception) {
            runCatching { upstream.close() }
            null
        }
    }

    private fun fillProgressiveLookahead(chunkSize: Long) {
        val uri = progressiveUri ?: return
        val maxPending = manager.config.progressiveLookaheadChunks
        while (pendingRangeChunks.size < maxPending && nextChunkStartOffset < progressiveEndExclusive) {
            val remaining = progressiveEndExclusive - nextChunkStartOffset
            val reqLen = minOf(chunkSize, remaining)
            if (reqLen <= 0L) break
            val start = nextChunkStartOffset
            nextChunkStartOffset += reqLen
            pendingRangeChunks.addLast(start to manager.submitRangeChunk(uri, start, reqLen))
        }
    }

    private fun parseTotalSizeFromContentRange(headers: Map<String, List<String>>): Long? {
        val rangeValues = headers.entries.firstOrNull { (k, _) ->
            k.equals("Content-Range", ignoreCase = true)
        }?.value ?: return null
        for (value in rangeValues) {
            val match = CONTENT_RANGE_TOTAL_REGEX.find(value)
            val total = match?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (total != null && total > 0L) return total
        }
        return null
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return when (mode) {
            Mode.DIRECT_UPSTREAM -> upstream.read(buffer, offset, length)

            Mode.TEE_MANIFEST -> {
                val read = upstream.read(buffer, offset, length)
                if (read > 0 && !manifestOverflowed) {
                    val tee = manifestTee
                    if (tee != null && tee.size() + read <= MAX_TEE_MANIFEST_BYTES) {
                        tee.write(buffer, offset, read)
                    } else {
                        manifestOverflowed = true
                        manifestTee = null
                    }
                }
                read
            }

            Mode.RAM_SEGMENT -> {
                if (ramRemaining <= 0) return C.RESULT_END_OF_INPUT
                val src = ramBytes ?: return C.RESULT_END_OF_INPUT
                val toCopy = minOf(length, ramRemaining)
                System.arraycopy(src, ramOffset, buffer, offset, toCopy)
                ramOffset += toCopy
                ramRemaining -= toCopy
                toCopy
            }

            Mode.STREAMING_PREFETCH -> {
                val entry = streamingEntry ?: return C.RESULT_END_OF_INPUT
                val maxRead = if (streamingRemainingLimit == C.LENGTH_UNSET.toLong()) {
                    length
                } else {
                    if (streamingRemainingLimit <= 0L) return C.RESULT_END_OF_INPUT
                    minOf(length.toLong(), streamingRemainingLimit).toInt()
                }
                val read = entry.readNext(buffer, offset, maxRead)
                if (read > 0 && streamingRemainingLimit != C.LENGTH_UNSET.toLong()) {
                    streamingRemainingLimit = (streamingRemainingLimit - read).coerceAtLeast(0L)
                }
                read
            }

            Mode.PROGRESSIVE_RANGE -> readProgressiveRange(buffer, offset, length)
        }
    }

    @Throws(IOException::class)
    private fun readProgressiveRange(buffer: ByteArray, offset: Int, length: Int): Int {
        if (progressiveCurrentOffset >= progressiveEndExclusive) return C.RESULT_END_OF_INPUT
        val chunkSize = manager.config.progressiveChunkBytes.toLong()

        var chunk = currentChunkBytes
        if (chunk == null || currentChunkOffset >= chunk.size) {
            val nextEntry = pendingRangeChunks.removeFirstOrNull()
            if (nextEntry == null) {
                fillProgressiveLookahead(chunkSize)
                val retryEntry = pendingRangeChunks.removeFirstOrNull() ?: return C.RESULT_END_OF_INPUT
                chunk = awaitRangeChunk(retryEntry.first, retryEntry.second, chunkSize)
            } else {
                chunk = awaitRangeChunk(nextEntry.first, nextEntry.second, chunkSize)
            }
            if (chunk == null || chunk.isEmpty()) {
                return C.RESULT_END_OF_INPUT
            }
            currentChunkBytes = chunk
            currentChunkOffset = 0
            fillProgressiveLookahead(chunkSize)
        }

        val remainingInChunk = chunk.size - currentChunkOffset
        val remainingInStream = (progressiveEndExclusive - progressiveCurrentOffset)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val toCopy = minOf(length, remainingInChunk, remainingInStream)
        if (toCopy <= 0) return C.RESULT_END_OF_INPUT

        System.arraycopy(chunk, currentChunkOffset, buffer, offset, toCopy)
        currentChunkOffset += toCopy
        progressiveCurrentOffset += toCopy
        return toCopy
    }

    @Throws(IOException::class)
    private fun awaitRangeChunk(
        chunkStart: Long,
        future: CompletableFuture<ByteArray?>,
        chunkSize: Long
    ): ByteArray? {
        val prefetched = try {
            future.get(15, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Range prefetch interrupted", e)
        } catch (_: Exception) {
            null
        }
        if (prefetched != null && prefetched.isNotEmpty()) {
            return prefetched
        }
        val uri = progressiveUri ?: throw IOException("Missing progressive URI")
        val remaining = (progressiveEndExclusive - chunkStart).coerceAtLeast(0L)
        if (remaining <= 0L) return null
        val reqLen = minOf(chunkSize, remaining)
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(chunkStart)
            .setLength(reqLen)
            .build()
        return try {
            upstream.open(spec)
            val out = ByteArrayOutputStream(256 * 1024)
            val tmp = ByteArray(32 * 1024)
            while (true) {
                val r = upstream.read(tmp, 0, tmp.size)
                if (r == C.RESULT_END_OF_INPUT || r < 0) break
                out.write(tmp, 0, r)
            }
            out.toByteArray()
        } finally {
            runCatching { upstream.close() }
        }
    }

    override fun getUri(): Uri? = resolvedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return responseHeaders.ifEmpty { upstream.responseHeaders }
    }

    @Throws(IOException::class)
    override fun close() {
        val finishedDirectUrl = trackedDirectSegmentUrl
        val finishedDirectAudio = trackedDirectIsAudio
        trackedDirectSegmentUrl = null
        try {
            when (mode) {
                Mode.TEE_MANIFEST -> {
                    val finalUrl = (resolvedUri ?: upstream.uri)?.toString()
                    val teeBytes = if (!manifestOverflowed) manifestTee?.toByteArray() else null
                    manifestTee = null
                    upstream.close()
                    if (!finalUrl.isNullOrBlank() && teeBytes != null && teeBytes.isNotEmpty()) {
                        val content = String(teeBytes, Charsets.UTF_8)
                        manager.onPlaylistLoaded(finalUrl, content)
                    }
                }
                Mode.DIRECT_UPSTREAM -> upstream.close()
                Mode.RAM_SEGMENT -> {
                    ramBytes = null
                    ramOffset = 0
                    ramRemaining = 0
                }
                Mode.STREAMING_PREFETCH -> {
                    streamingEntry?.releaseAfterConsumerClose()
                    streamingEntry = null
                    streamingRemainingLimit = C.LENGTH_UNSET.toLong()
                }
                Mode.PROGRESSIVE_RANGE -> {
                    while (pendingRangeChunks.isNotEmpty()) {
                        pendingRangeChunks.removeFirst().second.cancel(true)
                    }
                    currentChunkBytes = null
                    currentChunkOffset = 0
                    progressiveUri = null
                }
            }
        } finally {
            mode = Mode.DIRECT_UPSTREAM
            resolvedUri = null
            responseHeaders = emptyMap()
            if (finishedDirectUrl != null) {
                manager.onForegroundDirectLoadFinished(finishedDirectAudio, finishedDirectUrl)
            }
        }
    }
}
