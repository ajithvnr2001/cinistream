package com.example.player

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.example.CineStreamApplication
import com.example.model.ExternalSubtitle
import com.example.model.StreamFormat
import com.example.model.TrackOption
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Rapidly-updating timeline position state kept isolated from [PlayerPlaybackState] so 500ms
 * progress ticks only recompose the seekbar & timestamp instead of the full PlayerScreen.
 */
data class PlaybackProgressState(
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLiveStream: Boolean = false
)

data class PlayerPlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLiveStream: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val volumePercent: Int = AudioBoostMath.NORMAL_MAX_PERCENT,
    val errorMessage: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoTracks: List<TrackOption> = emptyList(),
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesDisabled: Boolean = false,
    val activeCdnIndex: Int = 1,
    val totalCdnNodes: Int = 1,
    val activeCdnHost: String = "",
    val activeVideoDecoderName: String = "",
    val preferredDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    val effectiveDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    val preferredTransportMode: NetworkTransportMode = NetworkTransportMode.AUTO,
    val isDecoderError: Boolean = false,
    val failedDecoderName: String? = null,
    val surfaceResetToken: Int = 0,
    val ffmpegAvailable: Boolean = false,
    val audioDelayMs: Long = 0L,
    val nightModeAudioEnabled: Boolean = false,
    val temporarySpeedActive: Boolean = false
)

@OptIn(UnstableApi::class)
class CinePlayerManager(context: Context) {

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) CineStream/1.0 ExoPlayer"

        const val MIN_TARGET_BUFFER_BYTES = 12 * 1024 * 1024
        const val MAX_TARGET_BUFFER_BYTES = 40 * 1024 * 1024
        const val MIN_BUFFER_MS = 20_000
        const val MAX_BUFFER_MS = 45_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 6_000
        const val SAFE_LARGE_HEAP_BACK_BUFFER_MS = 5_000
        const val TV_SAFE_MAX_VIDEO_WIDTH = 1920
        const val TV_SAFE_MAX_VIDEO_HEIGHT = 1080
        const val TV_SAFE_MAX_VIDEO_BITRATE = 9_500_000
        const val TV_INITIAL_WIFI_BITRATE_BPS = 6_000_000L
        const val MOBILE_INITIAL_WIFI_BITRATE_BPS = 10_000_000L
        const val ETHERNET_INITIAL_BITRATE_BPS = 12_000_000L
        private const val SPARKLINE_HISTORY_SIZE = 24

        /**
         * Caps ExoPlayer's Java-heap media buffer to 20% of JVM maxMemory, clamped to 12–40 MB.
         * - 80 MB constrained heap -> 16 MB cap
         * - 384 MB largeHeap Android 9 TV -> 40 MB cap (~37s of 8.6 Mbps 1080p, ~8.5s of 38 Mbps 4K)
         * Always enforced over time thresholds so neither 1080p nor 4K streams can ever OOM a 1 GB TV.
         */
        fun computeSafeTargetBufferBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
            return (maxHeapBytes * 0.20)
                .toLong()
                .coerceIn(MIN_TARGET_BUFFER_BYTES.toLong(), MAX_TARGET_BUFFER_BYTES.toLong())
                .toInt()
        }

        /**
         * Retains a modest 5s back-buffer on >=256 MB heaps for instant micro-rewinds, and 0 ms on
         * tighter heaps so played frames are immediately freed for GC.
         */
        fun computeSafeBackBufferMs(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
            return if (maxHeapBytes >= 256L * 1024L * 1024L) SAFE_LARGE_HEAP_BACK_BUFFER_MS else 0
        }

        /**
         * Returns a sensible Wi-Fi cold-start bitrate estimate so HLS/DASH streams start sharp in
         * HD on frame 1 instead of ExoPlayer's conservative ~1 Mbps 480p default.
         */
        fun computeInitialWifiBitrateEstimate(isConstrainedOrTv: Boolean): Long {
            return if (isConstrainedOrTv) TV_INITIAL_WIFI_BITRATE_BPS else MOBILE_INITIAL_WIFI_BITRATE_BPS
        }
    }

    private val appContext: Context = context.applicationContext

    private val isTelevisionDevice: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private val isConstrainedDevice: Boolean = run {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        (activityManager?.isLowRamDevice == true) ||
            Runtime.getRuntime().maxMemory() <= MediaEdgeCache.CONSTRAINED_HEAP_THRESHOLD_BYTES
    }

    private val isRamDirectCacheMode: Boolean = MediaEdgeCache.isRamDirectMode(context)

    val fastDnsResolver: FastDnsResolver =
        (context.applicationContext as? CineStreamApplication)?.fastDnsResolver
            ?: FastDnsResolver()

    val socksProxyManager: DynamicSocksProxyManager =
        (context.applicationContext as? CineStreamApplication)?.socksProxyManager
            ?: DynamicSocksProxyManager(proxyDnsResolver = fastDnsResolver)

    val okHttpClient: OkHttpClient = (context.applicationContext as? CineStreamApplication)?.sharedOkHttpClient
        ?: OkHttpClient.Builder()
            .dns(fastDnsResolver)
            .proxySelector(socksProxyManager.proxySelector)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    // Explicit BandwidthMeter shared between HTTP/3 Cronet / HTTP/2 OkHttp and ExoPlayer.
    // Tuned initial Wi-Fi/Ethernet estimates start frame 1 cleanly in HD instead of 480p.
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
        .setInitialBitrateEstimate(
            C.NETWORK_TYPE_WIFI,
            computeInitialWifiBitrateEstimate(isTelevisionDevice || isConstrainedDevice)
        )
        .setInitialBitrateEstimate(C.NETWORK_TYPE_ETHERNET, ETHERNET_INITIAL_BITRATE_BPS)
        .setInitialBitrateEstimate(C.NETWORK_TYPE_4G, 5_000_000L)
        .setInitialBitrateEstimate(C.NETWORK_TYPE_5G_SA, 12_000_000L)
        .setInitialBitrateEstimate(C.NETWORK_TYPE_5G_NSA, 12_000_000L)
        .setResetOnNetworkTypeChange(true)
        .build()

    // Reuse the shared ConnectionPool, FastDnsResolver, DynamicSocksProxyManager & Dispatcher for HTTP/2
    private val streamingOkHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var preferredTransportMode: NetworkTransportMode = NetworkTransportMode.AUTO
    private val quicWatchdog = QuicThroughputWatchdog()

    // Dynamically uses Play Services Cronet (HTTP/3 QUIC + TLS 1.3 + BBR) when available,
    // with automatic QUIC-throttle watchdog & zero-crash fallback to HTTP/2 OkHttp.
    // When per-app Cloudflare 1.1.1.1 DoH or NordVPN SOCKS5 is active, routes via OkHttp HTTP/2.
    private val httpDataSourceFactory: AdaptiveHttpDataSourceFactory =
        StreamingNetworkEngine.createAdaptiveHttpDataSourceFactory(
            okHttpClient = streamingOkHttpClient,
            userAgent = DEFAULT_USER_AGENT,
            transferListener = bandwidthMeter,
            transportModeProvider = { preferredTransportMode },
            cronetDemotedProvider = { quicWatchdog.isDemoted },
            forceOkHttpProvider = {
                fastDnsResolver.privateDnsMode != PrivateDnsMode.SYSTEM ||
                    socksProxyManager.isProxyActive ||
                    hasAuthorizationHeader()
            }
        )

    // DefaultDataSource wraps AdaptiveHttpDataSourceFactory so remote HTTP/3 / HTTP/2 streams
    // AND local content:// videos/subtitles seamlessly combine inside MergingMediaSource.
    private val directDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    // Bounded parallel HLS (3x TV / 4x Phone) + Progressive Range (2x TV / 3x Phone) prefetcher
    // that warms cold Cloudflare/S3 edges ahead of playback while staying strictly RAM-capped.
    val parallelPrefetchManager = ParallelHlsPrefetchManager(
        config = ParallelPrefetchConfig.forDevice(isTelevisionDevice || isConstrainedDevice),
        upstreamFactory = directDataSourceFactory
    )
    private val prefetchedDataSourceFactory = parallelPrefetchManager.createDataSourceFactory()

    // Automatically bypasses on-device disk edge cache after any decode error so neither HW nor SW
    // decoders can ever be re-fed a truncated or corrupted cached segment.
    @Volatile private var bypassEdgeCacheForSession: Boolean = false

    // RAM-Direct (zero eMMC flash stall) on constrained Android TVs; 128 KB buffered 128 MB edge
    // disk cache on modern phones/tablets (always bypassing manifests, subtitles, and local files).
    private val smartEdgeDataSourceFactory = MediaEdgeCache.createSmartDataSourceFactory(
        context = context,
        directDataSourceFactory = prefetchedDataSourceFactory,
        bypassCacheProvider = { bypassEdgeCacheForSession || hasAuthorizationHeader() }
    )

    private val loadErrorHandlingPolicy = FastStreamingLoadErrorHandlingPolicy()

    // Tolerant TS flags fix FFmpeg-muxed HEVC/H.264 HLS streams with CRA/BLA non-IDR keyframes
    private val tolerantTsFlags: Int =
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

    private val extractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setTsExtractorFlags(tolerantTsFlags)

    private val hlsMediaSourceFactory = HlsMediaSource.Factory(smartEdgeDataSourceFactory)
        .setExtractorFactory(
            DefaultHlsExtractorFactory(tolerantTsFlags, true)
                .experimentalParseSubtitlesDuringExtraction(true)
        )
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        .setAllowChunklessPreparation(true)

    private val mediaSourceFactory = DefaultMediaSourceFactory(smartEdgeDataSourceFactory, extractorsFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

    val targetBufferBytes: Int = computeSafeTargetBufferBytes()
    val backBufferDurationMs: Int = computeSafeBackBufferMs()

    // trimOnReset = true immediately returns 64 KB buffer segments to GC when stopping or switching media
    private val allocator = DefaultAllocator(
        /* trimOnReset = */ true,
        /* individualAllocationSize = */ C.DEFAULT_BUFFER_SEGMENT_SIZE
    )

    // Strict heap-capped LoadControl: provides a generous 20–45s jitter cushion and 6s post-rebuffer
    // recovery while ALWAYS enforcing targetBufferBytes over time thresholds to prevent OOMs on 1 GB TVs.
    private val loadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            /* minBufferMs = */ MIN_BUFFER_MS,
            /* maxBufferMs = */ MAX_BUFFER_MS,
            /* bufferForPlaybackMs = */ BUFFER_FOR_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        .setTargetBufferBytes(targetBufferBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(
            /* backBufferDurationMs = */ backBufferDurationMs,
            /* retainBackBufferFromKeyframe = */ false
        )
        .build()

    // TV-aware ABR & Stereo audio track selector:
    // - On Android TV / constrained RAM devices: prefers 2ch Stereo (hardware DSP, eliminating 7.1 SW AAC
    //   underruns on 32-bit Cortex-A53 CPUs), caps auto-selected video to 1080p / 9.5 Mbps (preventing
    //   18 Mbps orig streams from starving), and uses faster ABR downshifts (maxDecrease = 10s, 0.70 fraction).
    // - Always sets exceed constraints = true so 5.1/7.1-only or single-track streams still play seamlessly.

    private val adaptiveTrackSelectionFactory = if (isTelevisionDevice || isConstrainedDevice) {
        AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 10_000,
            /* maxDurationForQualityDecreaseMs = */ 10_000,
            /* minDurationToRetainAfterDiscardMs = */ 25_000,
            /* bandwidthFraction = */ 0.70f
        )
    } else {
        AdaptiveTrackSelection.Factory()
    }

    private val trackSelector = DefaultTrackSelector(context, adaptiveTrackSelectionFactory).apply {
        val preferredLang = Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"
        val builder = buildUponParameters()
            .setPreferredAudioLanguage(preferredLang)
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowVideoNonSeamlessAdaptiveness(true)
            .setExceedVideoConstraintsIfNecessary(true)
            .setExceedAudioConstraintsIfNecessary(true)

        if (isTelevisionDevice || isConstrainedDevice) {
            builder
                .setMaxAudioChannelCount(2)
                .setMaxVideoSize(TV_SAFE_MAX_VIDEO_WIDTH, TV_SAFE_MAX_VIDEO_HEIGHT)
                .setMaxVideoBitrate(TV_SAFE_MAX_VIDEO_BITRATE)
        }
        parameters = builder.build()
    }

    private val volumeBooster = AudioVolumeBooster()
    private val audioDelayUs = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAudioDelayRealign: Runnable? = null
    private var pendingAudioRealignAnchorMs: Long? = null
    private var pendingAudioRealignSetAtMs: Long = 0L
    private val frameDropAnalyzer = FrameDropAnalyzer()
    private val asyncQueueingEnabled: Boolean = MediaCodecQueueingPolicy.shouldUseAsyncQueueing(
        isConstrainedOrTv = isTelevisionDevice || isConstrainedDevice
    )

    // Dynamic HW/SW video decoder selection & failed-codec blocklist (auto-recovers from c2.qti.hevc.decoder & c2.android.hevc.decoder)
    @Volatile private var preferredDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO
    @Volatile private var effectiveDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO
    @Volatile private var activeVideoDecoderName: String = ""
    private val sessionBlockedCodecs = CopyOnWriteArraySet<String>()
    private val triedFallbackVideoTrackIds = CopyOnWriteArraySet<String>()
    private var autoSwFallbackAttempted: Boolean = false
    private var autoHwFallbackAttempted: Boolean = false

    // Native FFmpeg 10-bit HEVC/H.264/VP9/AV1 + DTS/AC3/TrueHD availability check (safe on Robolectric JVM)
    val isFfmpegExtensionAvailable: Boolean = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)

    private val smartMediaCodecSelector = SmartMediaCodecSelector(
        modeProvider = { effectiveDecoderMode },
        blockedCodecNamesProvider = { sessionBlockedCodecs },
        preferExternalFfmpegVideoProvider = {
            isFfmpegExtensionAvailable && effectiveDecoderMode == VideoDecoderMode.SOFTWARE
        },
        hasFfmpegFallbackProvider = { isFfmpegExtensionAvailable }
    )

    // NextRenderersFactory injects native FFmpeg software video (10-bit HEVC Main10, AV1, H.264, VP9)
    // and audio (DTS, AC-3, E-AC-3, TrueHD, FLAC) renderers right behind platform HW MediaCodec.
    // Audio sync is handled upstream at the SampleStream layer via AudioSyncMediaSource so it works
    // identically on HLS muxed/demuxed segments, PCM, FFmpeg, and HDMI AC-3/E-AC-3 passthrough.
    private val renderersFactory = NextRenderersFactory(context).apply {
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
        if (asyncQueueingEnabled) {
            forceEnableMediaCodecAsynchronousQueueing()
        } else {
            forceDisableMediaCodecAsynchronousQueueing()
        }
        setMediaCodecSelector(smartMediaCodecSelector)
        setAllowedVideoJoiningTimeMs(5_000L)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .setBandwidthMeter(bandwidthMeter)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            // Snap rapidly to nearest sync keyframe for instant multi-minute scrubbing on MKV/MP4/HLS
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // Seamless display refresh-rate matching eliminates 24fps->60Hz pulldown judder without black flashes
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        }

    private val _state = MutableStateFlow(PlayerPlaybackState())
    val state: StateFlow<PlayerPlaybackState> = _state.asStateFlow()

    private val _progressState = MutableStateFlow(PlaybackProgressState())
    val progressState: StateFlow<PlaybackProgressState> = _progressState.asStateFlow()

    private val _networkStats = MutableStateFlow(PlayerNetworkStats(targetBufferBytes = targetBufferBytes))
    val networkStats: StateFlow<PlayerNetworkStats> = _networkStats.asStateFlow()

    private var currentTracks: Tracks = Tracks.EMPTY
    private var pendingAutoSelectSubtitleId: String? = null

    // Live telemetry & Multi-CDN failover state
    private var activeMediaUri: String? = null
    private var activeMediaFormat: StreamFormat = StreamFormat.AUTO
    private var activeMediaIsLocal: Boolean = false
    private var candidateUrls: List<String> = emptyList()
    private var activeMirrorIndex: Int = 0
    private var cdnFailoverCount: Int = 0
    @Volatile private var activeHeaders: Map<String, String> = emptyMap()

    private fun hasAuthorizationHeader(): Boolean =
        activeHeaders.keys.any { it.equals("Authorization", ignoreCase = true) }
    private var lastLoadedSubtitles: List<Pair<ExternalSubtitle, Uri>> = emptyList()
    private var lastAutoSelectSubtitleId: String? = null
    private var lastRequestedStartPosMs: Long = 0L
    private var lastAutoPlay: Boolean = true

    var onCdnFailover: ((host: String, nodeIndex: Int, totalNodes: Int) -> Unit)? = null
    var onDecoderFallback: ((failedCodec: String?, switchedMode: VideoDecoderMode) -> Unit)? = null
    var onTrackFallback: ((trackLabel: String) -> Unit)? = null
    var onTransportAutoFallback: ((reason: String) -> Unit)? = null
    var onMediaEnded: (() -> Unit)? = null
    var onSmartVpnStallCandidate: ((estimatedBps: Long) -> Unit)? = null
    var onSmartVpnFatalNetworkError: ((reason: String) -> Boolean)? = null

    private val windowBytesLoaded = AtomicLong(0L)
    private val sessionBytesLoaded = AtomicLong(0L)
    private val droppedFramesCount = AtomicLong(0L)
    @Volatile private var latestBandwidthEstimateBps: Long = 0L
    private var hasReachedReadyOnce: Boolean = false
    private var rebufferCount: Int = 0
    private var rebufferStartedAtElapsedMs: Long = 0L
    private var totalRebufferMs: Long = 0L
    private var lastStatsSampleElapsedMs: Long = 0L
    private val networkHistory = ArrayDeque<Float>(SPARKLINE_HISTORY_SIZE + 1)
    private val bufferHistory = ArrayDeque<Float>(SPARKLINE_HISTORY_SIZE + 1)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val nowElapsed = SystemClock.elapsedRealtime()
            when (playbackState) {
                Player.STATE_READY -> {
                    if (rebufferStartedAtElapsedMs > 0L) {
                        totalRebufferMs += (nowElapsed - rebufferStartedAtElapsedMs).coerceAtLeast(0L)
                        rebufferStartedAtElapsedMs = 0L
                    }
                    if (pendingAudioDelayRealign == null) {
                        pendingAudioRealignAnchorMs = null
                    }
                    hasReachedReadyOnce = true
                }
                Player.STATE_BUFFERING -> {
                    if (hasReachedReadyOnce && rebufferStartedAtElapsedMs == 0L) {
                        rebufferCount++
                        rebufferStartedAtElapsedMs = nowElapsed
                        val currentBps = latestBandwidthEstimateBps.takeIf { it > 0L }
                            ?: bandwidthMeter.bitrateEstimate.takeIf { it != C.TIME_UNSET && it > 0L }
                            ?: 0L
                        // Fast self-healing: if Cronet HTTP/3 is stalling repeatedly at < 2.5 Mbps,
                        // immediately tear down the throttled UDP socket and switch to HTTP/2 TCP.
                        if (!activeMediaIsLocal &&
                            preferredTransportMode == NetworkTransportMode.AUTO &&
                            httpDataSourceFactory.isUsingCronet() &&
                            quicWatchdog.recordRebufferStall(currentBps)
                        ) {
                            triggerAutoHttp2Recovery("Slow HTTP/3 QUIC stalled → Switched to HTTP/2 TCP")
                            return
                        }
                        if (!activeMediaIsLocal) {
                            onSmartVpnStallCandidate?.invoke(currentBps)
                        }
                    }
                }
                Player.STATE_IDLE, Player.STATE_ENDED -> {
                    if (rebufferStartedAtElapsedMs > 0L) {
                        totalRebufferMs += (nowElapsed - rebufferStartedAtElapsedMs).coerceAtLeast(0L)
                        rebufferStartedAtElapsedMs = 0L
                    }
                    if (playbackState == Player.STATE_ENDED && hasReachedReadyOnce) {
                        onMediaEnded?.invoke()
                    }
                }
            }

            val dur = player.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: _progressState.value.durationMs
            val isLive = player.isCurrentMediaItemLive
            _progressState.update {
                it.copy(durationMs = dur, isLiveStream = isLive)
            }
            _state.update {
                it.copy(
                    playbackState = playbackState,
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = dur,
                    isLiveStream = isLive,
                    errorMessage = if (playbackState == Player.STATE_READY) null else it.errorMessage
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _state.update {
                it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            currentTracks = tracks
            refreshTrackList(tracks)

            val targetSubId = pendingAutoSelectSubtitleId
            if (targetSubId != null) {
                val match = _state.value.subtitleTracks.firstOrNull { it.id == targetSubId }
                    ?: _state.value.subtitleTracks.lastOrNull { it.isExternal }
                if (match != null) {
                    pendingAutoSelectSubtitleId = null
                    selectSubtitleTrack(match)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isDecErr = DecoderFallbackHelper.isDecoderError(error)
            val failedCodec = if (isDecErr) DecoderFallbackHelper.extractFailedDecoderName(error) else null

            // 1. Multi-Tier Automatic Video Decoder Failover (fixes both c2.qti.hevc.decoder & c2.android.hevc.decoder)
            if (isDecErr) {
                // Immediately bypass local edge disk cache so potentially truncated chunks are never re-read
                bypassEdgeCacheForSession = true
                if (!failedCodec.isNullOrBlank()) {
                    sessionBlockedCodecs.add(failedCodec)
                }

                val resumeMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
                val targetUrl = candidateUrls.getOrNull(activeMirrorIndex) ?: activeMediaUri
                val failedIsSw = failedCodec != null && DecoderFallbackHelper.isSoftwareCodecName(failedCodec)

                // Tier 1A: Hardware failed in AUTO mode -> switch to Software (c2.android)
                val canAutoSwitchToSoftware = preferredDecoderMode == VideoDecoderMode.AUTO &&
                    effectiveDecoderMode != VideoDecoderMode.SOFTWARE &&
                    !autoSwFallbackAttempted &&
                    !failedIsSw

                if (canAutoSwitchToSoftware && !targetUrl.isNullOrBlank()) {
                    autoSwFallbackAttempted = true
                    effectiveDecoderMode = VideoDecoderMode.SOFTWARE
                    activeVideoDecoderName = ""
                    onDecoderFallback?.invoke(failedCodec, VideoDecoderMode.SOFTWARE)
                    prepareResolvedUrl(
                        targetUrl = targetUrl,
                        format = activeMediaFormat,
                        externalSubtitles = lastLoadedSubtitles,
                        startPositionMs = resumeMs,
                        autoPlay = lastAutoPlay,
                        autoSelectSubtitleId = lastAutoSelectSubtitleId,
                        resetSessionCounters = false,
                        forceResetRenderer = true
                    )
                    return
                }

                // Tier 1B: Software (c2.android.hevc.decoder, 8-bit only) failed first -> try Hardware (10-bit capable)
                val canAutoSwitchToHardware = preferredDecoderMode == VideoDecoderMode.AUTO &&
                    failedIsSw &&
                    !autoHwFallbackAttempted &&
                    !sessionBlockedCodecs.any { !DecoderFallbackHelper.isSoftwareCodecName(it) }

                if (canAutoSwitchToHardware && !targetUrl.isNullOrBlank()) {
                    autoHwFallbackAttempted = true
                    effectiveDecoderMode = VideoDecoderMode.HARDWARE
                    activeVideoDecoderName = ""
                    onDecoderFallback?.invoke(failedCodec, VideoDecoderMode.HARDWARE)
                    prepareResolvedUrl(
                        targetUrl = targetUrl,
                        format = activeMediaFormat,
                        externalSubtitles = lastLoadedSubtitles,
                        startPositionMs = resumeMs,
                        autoPlay = lastAutoPlay,
                        autoSelectSubtitleId = lastAutoSelectSubtitleId,
                        resetSessionCounters = false,
                        forceResetRenderer = true
                    )
                    return
                }

                // Tier 2: Both HW & SW failed on current HEVC track -> step down to compatible H.264 or lower-res HLS/DASH track
                val fallbackTrack = findCompatibleFallbackVideoTrack()
                if (fallbackTrack != null && !targetUrl.isNullOrBlank()) {
                    triedFallbackVideoTrackIds.add(fallbackTrack.id)
                    sessionBlockedCodecs.clear()
                    autoSwFallbackAttempted = false
                    autoHwFallbackAttempted = false
                    effectiveDecoderMode = preferredDecoderMode
                    activeVideoDecoderName = ""
                    val group = currentTracks.groups.getOrNull(fallbackTrack.groupIndex)
                    if (group != null) {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(fallbackTrack.trackIndex)))
                            .build()
                        onTrackFallback?.invoke(fallbackTrack.label)
                        prepareResolvedUrl(
                            targetUrl = targetUrl,
                            format = activeMediaFormat,
                            externalSubtitles = lastLoadedSubtitles,
                            startPositionMs = resumeMs,
                            autoPlay = lastAutoPlay,
                            autoSelectSubtitleId = lastAutoSelectSubtitleId,
                            resetSessionCounters = false,
                            forceResetRenderer = true
                        )
                        return
                    }
                }
            }

            // 2A. Automatic HTTP/3 QUIC error demotion -> retry immediately over HTTP/2 TCP
            if (isRecoverableCdnError(error) &&
                httpDataSourceFactory.isUsingCronet() &&
                preferredTransportMode == NetworkTransportMode.AUTO &&
                quicWatchdog.forceDemote()
            ) {
                val resumeMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
                val targetUrl = candidateUrls.getOrNull(activeMirrorIndex) ?: activeMediaUri
                if (!targetUrl.isNullOrBlank()) {
                    parallelPrefetchManager.clearMemoryOnly()
                    onTransportAutoFallback?.invoke("HTTP/3 QUIC error → Switched to HTTP/2 TCP")
                    prepareResolvedUrl(
                        targetUrl = targetUrl,
                        format = activeMediaFormat,
                        externalSubtitles = lastLoadedSubtitles,
                        startPositionMs = resumeMs,
                        autoPlay = lastAutoPlay,
                        autoSelectSubtitleId = lastAutoSelectSubtitleId,
                        resetSessionCounters = false
                    )
                    return
                }
            }

            // 2B. Automatic Multi-CDN Mirror Failover at exact playback position on upstream network/IO errors
            if (isRecoverableCdnError(error) && activeMirrorIndex + 1 < candidateUrls.size) {
                val resumeMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
                activeMirrorIndex++
                cdnFailoverCount++
                val nextUrl = candidateUrls[activeMirrorIndex]
                val nextHost = PlayerStatsFormatter.extractHostLabel(nextUrl, isLocal = false)
                onCdnFailover?.invoke(nextHost, activeMirrorIndex + 1, candidateUrls.size)
                prepareResolvedUrl(
                    targetUrl = nextUrl,
                    format = activeMediaFormat,
                    externalSubtitles = lastLoadedSubtitles,
                    startPositionMs = resumeMs,
                    autoPlay = lastAutoPlay,
                    autoSelectSubtitleId = lastAutoSelectSubtitleId,
                    resetSessionCounters = false
                )
                return
            }

            // 2C. Smart Auto-VPN Failover after QUIC & mirror fallbacks are exhausted
            if (isRecoverableCdnError(error) && !activeMediaIsLocal) {
                val reason = error.errorCodeName.replace('_', ' ').lowercase(Locale.US)
                if (onSmartVpnFatalNetworkError?.invoke(reason) == true) {
                    return
                }
            }

            val bothHwAndSwFailed = isDecErr &&
                sessionBlockedCodecs.any { !DecoderFallbackHelper.isSoftwareCodecName(it) } &&
                sessionBlockedCodecs.any { DecoderFallbackHelper.isSoftwareCodecName(it) }

            val readable = buildString {
                append(
                    error.errorCodeName.replace('_', ' ').lowercase(Locale.US)
                        .replaceFirstChar { it.titlecase(Locale.US) }
                )
                error.cause?.localizedMessage?.takeIf { it.isNotBlank() }?.let { cause ->
                    append(": ")
                    append(cause)
                }
                if (isDecErr) {
                    append("\n")
                    append(
                        DecoderFallbackHelper.buildReadableDecoderErrorHint(
                            failedCodec = failedCodec,
                            effectiveMode = effectiveDecoderMode,
                            bothHwAndSwFailed = bothHwAndSwFailed,
                            ffmpegAvailable = isFfmpegExtensionAvailable
                        )
                    )
                }
            }
            _state.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = readable,
                    isDecoderError = isDecErr,
                    failedDecoderName = failedCodec
                )
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(
            eventTime: AnalyticsListener.EventTime,
            audioSessionId: Int
        ) {
            volumeBooster.bindAudioSession(audioSessionId)
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long
        ) {
            if (bitrateEstimate > 0L) {
                latestBandwidthEstimateBps = bitrateEstimate
            }
            if (totalBytesLoaded > 0L) {
                windowBytesLoaded.addAndGet(totalBytesLoaded)
                sessionBytesLoaded.addAndGet(totalBytesLoaded)
            }
            // Pure socket bandwidth check (immune to 1 ms RAM prefetch hits!)
            if (!activeMediaIsLocal &&
                preferredTransportMode == NetworkTransportMode.AUTO &&
                httpDataSourceFactory.isUsingCronet() &&
                quicWatchdog.recordNetworkSample(bitrateEstimate, totalBytesLoaded)
            ) {
                triggerAutoHttp2Recovery("Slow HTTP/3 QUIC detected → Switched to HTTP/2 TCP")
            }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            // Fallback accumulator when chunks complete faster than bandwidth estimate windows
            if (loadEventInfo.bytesLoaded > 0L && latestBandwidthEstimateBps <= 0L) {
                windowBytesLoaded.addAndGet(loadEventInfo.bytesLoaded)
                sessionBytesLoaded.addAndGet(loadEventInfo.bytesLoaded)
            }
            // Secondary segment-level watchdog (sub-50 ms RAM hits are ignored inside watchdog)
            if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA &&
                !activeMediaIsLocal &&
                preferredTransportMode == NetworkTransportMode.AUTO &&
                httpDataSourceFactory.isUsingCronet() &&
                quicWatchdog.recordCompletedSegment(loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs)
            ) {
                triggerAutoHttp2Recovery("Slow HTTP/3 QUIC detected → Switched to HTTP/2 TCP")
            }
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            activeVideoDecoderName = decoderName
            _state.update {
                it.copy(
                    activeVideoDecoderName = decoderName,
                    isDecoderError = false,
                    failedDecoderName = null
                )
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            if (droppedFrames > 0) {
                droppedFramesCount.addAndGet(droppedFrames.toLong())
                frameDropAnalyzer.recordDroppedFrames(droppedFrames, SystemClock.elapsedRealtime())
            }
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
    }

    fun loadMedia(
        mediaUri: String,
        format: StreamFormat,
        externalSubtitles: List<Pair<ExternalSubtitle, Uri>>,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        autoSelectSubtitleId: String? = null,
        mirrors: List<String> = emptyList(),
        headers: Map<String, String> = emptyMap()
    ) {
        candidateUrls = CdnMirrorResolver.buildCandidateUrls(
            primaryUrl = mediaUri,
            itemMirrors = mirrors
        )
        activeMirrorIndex = 0
        cdnFailoverCount = 0
        activeHeaders = headers
        lastLoadedSubtitles = externalSubtitles
        lastAutoSelectSubtitleId = autoSelectSubtitleId
        lastAutoPlay = autoPlay

        // Fresh media resets per-session prefetch queue, QUIC watchdog, failed codec blocklist & cache bypass
        parallelPrefetchManager.resetSession()
        quicWatchdog.reset()
        bypassEdgeCacheForSession = false
        sessionBlockedCodecs.clear()
        triedFallbackVideoTrackIds.clear()
        autoSwFallbackAttempted = false
        autoHwFallbackAttempted = false
        effectiveDecoderMode = preferredDecoderMode
        activeVideoDecoderName = ""
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()

        // Apply per-URL CDN headers (or clear stale headers from previous session)
        httpDataSourceFactory.setDefaultRequestProperties(headers)

        val initialUrl = candidateUrls.firstOrNull() ?: mediaUri
        prepareResolvedUrl(
            targetUrl = initialUrl,
            format = format,
            externalSubtitles = externalSubtitles,
            startPositionMs = startPositionMs,
            autoPlay = autoPlay,
            autoSelectSubtitleId = autoSelectSubtitleId,
            resetSessionCounters = true
        )
    }

    /**
     * Updates the user's preferred [VideoDecoderMode]. When a stream is active and [reloadActiveStream]
     * is true, stops/clears the renderer so [SmartMediaCodecSelector] immediately binds the new decoder
     * at the exact playback millisecond.
     */
    fun setPreferredDecoderMode(mode: VideoDecoderMode, reloadActiveStream: Boolean = true) {
        val changed = preferredDecoderMode != mode || effectiveDecoderMode != mode
        preferredDecoderMode = mode
        effectiveDecoderMode = mode
        sessionBlockedCodecs.clear()
        autoSwFallbackAttempted = mode == VideoDecoderMode.SOFTWARE
        autoHwFallbackAttempted = mode == VideoDecoderMode.HARDWARE
        _state.update {
            it.copy(
                preferredDecoderMode = mode,
                effectiveDecoderMode = mode
            )
        }
        if (changed && reloadActiveStream) {
            val targetUrl = candidateUrls.getOrNull(activeMirrorIndex) ?: activeMediaUri
            if (!targetUrl.isNullOrBlank()) {
                val resumeMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
                val wasPlaying = player.playWhenReady || _state.value.errorMessage != null
                activeVideoDecoderName = ""
                prepareResolvedUrl(
                    targetUrl = targetUrl,
                    format = activeMediaFormat,
                    externalSubtitles = lastLoadedSubtitles,
                    startPositionMs = resumeMs,
                    autoPlay = wasPlaying,
                    autoSelectSubtitleId = lastAutoSelectSubtitleId,
                    resetSessionCounters = false,
                    forceResetRenderer = true
                )
            }
        }
    }

    private fun triggerAutoHttp2Recovery(reason: String) {
        parallelPrefetchManager.clearMemoryOnly()
        onTransportAutoFallback?.invoke(reason)
        val targetUrl = candidateUrls.getOrNull(activeMirrorIndex) ?: activeMediaUri
        // If playback is actively stalled/buffering on a stuck QUIC socket, immediately reconnect
        // over HTTP/2 IPv4 TCP at the exact playback millisecond instead of waiting for timeout.
        if (!targetUrl.isNullOrBlank() && player.playbackState == Player.STATE_BUFFERING) {
            val resumeMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
            val wasPlaying = player.playWhenReady
            prepareResolvedUrl(
                targetUrl = targetUrl,
                format = activeMediaFormat,
                externalSubtitles = lastLoadedSubtitles,
                startPositionMs = resumeMs,
                autoPlay = wasPlaying,
                autoSelectSubtitleId = lastAutoSelectSubtitleId,
                resetSessionCounters = false
            )
        }
    }

    /**
     * Updates the preferred [NetworkTransportMode] (Auto HTTP/3->HTTP/2, HTTP/2 TCP, or HTTP/3 QUIC).
     * Clears in-flight prefetch buffers and optionally reloads the active stream at the exact millisecond.
     */
    fun setPreferredTransportMode(mode: NetworkTransportMode, reloadActiveStream: Boolean = true) {
        val changed = preferredTransportMode != mode || quicWatchdog.isDemoted
        preferredTransportMode = mode
        quicWatchdog.reset()
        parallelPrefetchManager.clearMemoryOnly()
        _state.update { it.copy(preferredTransportMode = mode) }
        if (changed && reloadActiveStream) {
            reloadActiveStreamAtCurrentPosition()
        }
    }

    @Volatile
    private var nordLynxTunnelActive: Boolean = false

    @Volatile
    private var nordLynxEndpointHost: String = ""

    /**
     * Applies updated Cloudflare 1.1.1.1 / Google 8.8.8.8 Private DNS and NordVPN (NordLynx or
     * SOCKS5) routing live across all streaming sockets, evicts stale pooled connections, and
     * seamlessly reloads any currently playing stream at the exact playback millisecond.
     */
    fun applyNetworkPrivacyConfig(
        dnsMode: PrivateDnsMode,
        nordConfig: DynamicSocksProxyManager.ActiveConfig,
        nordLynxActive: Boolean = false,
        nordLynxHost: String = "",
        reloadActiveStream: Boolean = true
    ) {
        val changed = fastDnsResolver.privateDnsMode != dnsMode ||
            socksProxyManager.config != nordConfig ||
            nordLynxTunnelActive != nordLynxActive ||
            !nordLynxEndpointHost.equals(nordLynxHost, ignoreCase = true)
        fastDnsResolver.setPrivateDnsMode(dnsMode)
        socksProxyManager.updateConfig(nordConfig)
        nordLynxTunnelActive = nordLynxActive
        nordLynxEndpointHost = nordLynxHost
        if (changed) {
            runCatching { okHttpClient.connectionPool.evictAll() }
            runCatching { streamingOkHttpClient.connectionPool.evictAll() }
            parallelPrefetchManager.clearMemoryOnly()
            if (reloadActiveStream) {
                reloadActiveStreamAtCurrentPosition()
            }
        }
    }

    fun reloadActiveStreamAtCurrentPosition() {
        val targetUrl = candidateUrls.getOrNull(activeMirrorIndex) ?: activeMediaUri
        if (!targetUrl.isNullOrBlank()) {
            val resumeMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
            val wasPlaying = player.playWhenReady || _state.value.errorMessage != null
            prepareResolvedUrl(
                targetUrl = targetUrl,
                format = activeMediaFormat,
                externalSubtitles = lastLoadedSubtitles,
                startPositionMs = resumeMs,
                autoPlay = wasPlaying,
                autoSelectSubtitleId = lastAutoSelectSubtitleId,
                resetSessionCounters = false
            )
        }
    }

    /**
     * Immediately retries the current stream using [mode] (e.g. from the PlayerErrorCard "Try Software Decoder" button)
     * without permanently overriding the user's saved AUTO preference.
     */
    fun retryWithDecoderMode(mode: VideoDecoderMode) {
        val targetUrl = candidateUrls.getOrNull(activeMirrorIndex) ?: activeMediaUri ?: return
        val resumeMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
        bypassEdgeCacheForSession = true
        effectiveDecoderMode = mode
        if (mode == VideoDecoderMode.HARDWARE) {
            sessionBlockedCodecs.removeIf { !DecoderFallbackHelper.isSoftwareCodecName(it) }
        } else if (mode == VideoDecoderMode.SOFTWARE) {
            sessionBlockedCodecs.removeIf { DecoderFallbackHelper.isSoftwareCodecName(it) }
        }
        autoSwFallbackAttempted = mode == VideoDecoderMode.SOFTWARE
        autoHwFallbackAttempted = mode == VideoDecoderMode.HARDWARE
        activeVideoDecoderName = ""
        prepareResolvedUrl(
            targetUrl = targetUrl,
            format = activeMediaFormat,
            externalSubtitles = lastLoadedSubtitles,
            startPositionMs = resumeMs,
            autoPlay = true,
            autoSelectSubtitleId = lastAutoSelectSubtitleId,
            resetSessionCounters = false,
            forceResetRenderer = true
        )
    }

    /**
     * Jumps past a single corrupted frame/GOP/HLS segment (+5s) with Edge Disk Cache bypassed
     * and decoder state freshly reset.
     */
    fun skipBadSegmentAndRetry(deltaMs: Long = 5_000L) {
        val targetUrl = candidateUrls.getOrNull(activeMirrorIndex) ?: activeMediaUri ?: return
        val currentMs = player.currentPosition.takeIf { it > 0L } ?: lastRequestedStartPosMs
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val nextPos = (currentMs + deltaMs).let { candidate ->
            if (durationMs != null) candidate.coerceAtMost((durationMs - 1_000L).coerceAtLeast(0L)) else candidate
        }
        bypassEdgeCacheForSession = true
        sessionBlockedCodecs.clear()
        autoSwFallbackAttempted = false
        autoHwFallbackAttempted = false
        effectiveDecoderMode = preferredDecoderMode
        activeVideoDecoderName = ""
        prepareResolvedUrl(
            targetUrl = targetUrl,
            format = activeMediaFormat,
            externalSubtitles = lastLoadedSubtitles,
            startPositionMs = nextPos,
            autoPlay = true,
            autoSelectSubtitleId = lastAutoSelectSubtitleId,
            resetSessionCounters = false,
            forceResetRenderer = true
        )
    }

    private fun findCompatibleFallbackVideoTrack(): DecoderFallbackHelper.VideoTrackCandidate? {
        val candidates = mutableListOf<DecoderFallbackHelper.VideoTrackCandidate>()
        currentTracks.groups.forEachIndexed { groupIdx, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
            for (trackIdx in 0 until group.length) {
                val fmt = group.getTrackFormat(trackIdx)
                val height = fmt.height.takeIf { it > 0 } ?: 0
                val codecShort = fmt.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US).orEmpty()
                val label = buildString {
                    if (height > 0) append("${height}p") else append("Track ${trackIdx + 1}")
                    if (codecShort.isNotBlank()) append(" $codecShort")
                }
                candidates.add(
                    DecoderFallbackHelper.VideoTrackCandidate(
                        id = fmt.id ?: "v_${groupIdx}_$trackIdx",
                        groupIndex = groupIdx,
                        trackIndex = trackIdx,
                        mimeType = fmt.sampleMimeType,
                        height = height,
                        bitrate = fmt.bitrate.takeIf { it > 0 } ?: 0,
                        isSelected = group.isTrackSelected(trackIdx),
                        label = label
                    )
                )
            }
        }
        return DecoderFallbackHelper.pickCompatibleFallbackTrack(
            candidates = candidates,
            triedTrackIds = triedFallbackVideoTrackIds
        )
    }

    /**
     * Manually hops to the next available CDN mirror node while preserving the exact playback timestamp.
     * Returns the new mirror host label if switched, or null if only one URL exists.
     */
    fun switchToNextCdnMirror(): String? {
        if (candidateUrls.size <= 1) return null
        val resumeMs = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        activeMirrorIndex = (activeMirrorIndex + 1) % candidateUrls.size
        val nextUrl = candidateUrls[activeMirrorIndex]
        prepareResolvedUrl(
            targetUrl = nextUrl,
            format = activeMediaFormat,
            externalSubtitles = lastLoadedSubtitles,
            startPositionMs = resumeMs,
            autoPlay = wasPlaying,
            autoSelectSubtitleId = lastAutoSelectSubtitleId,
            resetSessionCounters = false
        )
        return PlayerStatsFormatter.extractHostLabel(nextUrl, activeMediaIsLocal)
    }

    private fun prepareResolvedUrl(
        targetUrl: String,
        format: StreamFormat,
        externalSubtitles: List<Pair<ExternalSubtitle, Uri>>,
        startPositionMs: Long,
        autoPlay: Boolean,
        autoSelectSubtitleId: String?,
        resetSessionCounters: Boolean,
        forceResetRenderer: Boolean = false
    ) {
        pendingAutoSelectSubtitleId = autoSelectSubtitleId
        lastRequestedStartPosMs = startPositionMs.coerceAtLeast(0L)
        if (resetSessionCounters) {
            resetStatsSession(targetUrl, format)
        } else {
            activeMediaUri = targetUrl
            activeMediaIsLocal = targetUrl.startsWith("content://") || targetUrl.startsWith("file://")
            frameDropAnalyzer.markSeekOrTransition(SystemClock.elapsedRealtime())
        }

        val mimeType = MediaFormatDetector.resolveExoPlayerMimeType(format)

        val subtitleConfigs = externalSubtitles.mapIndexed { index, (sub, playableUri) ->
            val isTarget = sub.id == autoSelectSubtitleId ||
                (autoSelectSubtitleId == null && index == externalSubtitles.lastIndex)
            MediaItem.SubtitleConfiguration.Builder(playableUri)
                .setId(sub.id)
                .setMimeType(sub.mimeType)
                .setLabel("${sub.label} [${sub.formatBadge}]")
                .setLanguage("und")
                .setSelectionFlags(if (isTarget) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(targetUrl))
            .setSubtitleConfigurations(subtitleConfigs)

        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        val safeStart = startPositionMs.coerceAtLeast(0L)
        val totalNodes = candidateUrls.size.coerceAtLeast(1)
        val currentHost = PlayerStatsFormatter.extractHostLabel(targetUrl, activeMediaIsLocal)

        _progressState.value = PlaybackProgressState(
            currentPositionMs = safeStart,
            bufferedPositionMs = safeStart,
            durationMs = _progressState.value.durationMs.takeIf { !resetSessionCounters } ?: 0L,
            isLiveStream = false
        )
        _state.update {
            it.copy(
                errorMessage = null,
                isDecoderError = false,
                failedDecoderName = null,
                isBuffering = true,
                subtitlesDisabled = false,
                currentPositionMs = safeStart,
                bufferedPositionMs = safeStart,
                activeCdnIndex = activeMirrorIndex + 1,
                totalCdnNodes = totalNodes,
                activeCdnHost = currentHost,
                activeVideoDecoderName = activeVideoDecoderName,
                preferredDecoderMode = preferredDecoderMode,
                effectiveDecoderMode = effectiveDecoderMode,
                preferredTransportMode = preferredTransportMode
            )
        }

        if (subtitleConfigs.isNotEmpty()) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }

        if (forceResetRenderer) {
            // Detach surface BEFORE stopping so GPU MediaCodec or FFmpeg CPU ANativeWindow
            // releases its BufferQueue lock cleanly (prevents MediaCodec.configure() conflicts),
            // then stop/clear media items and trim 64 KB Java-heap segments immediately.
            player.clearVideoSurface()
            player.stop()
            player.clearMediaItems()
            allocator.trim()
            _state.update {
                it.copy(
                    surfaceResetToken = it.surfaceResetToken + 1,
                    ffmpegAvailable = isFfmpegExtensionAvailable
                )
            }
        } else {
            _state.update { it.copy(ffmpegAvailable = isFfmpegExtensionAvailable) }
        }

        val isHlsSource = mimeType == MimeTypes.APPLICATION_M3U8 ||
            format == StreamFormat.HLS ||
            format == StreamFormat.M3U8 ||
            targetUrl.contains(".m3u8", ignoreCase = true)

        val baseMediaSource: MediaSource = if (isHlsSource) {
            val baseItem = MediaItem.Builder()
                .setUri(Uri.parse(targetUrl))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            val hlsSource: MediaSource = hlsMediaSourceFactory.createMediaSource(baseItem)
            if (subtitleConfigs.isEmpty()) {
                hlsSource
            } else {
                val subSources = subtitleConfigs.map { subConfig ->
                    SubtitleMediaSourceBuilder.buildMediaSource(
                        subConfig = subConfig,
                        dataSourceFactory = directDataSourceFactory,
                        loadErrorHandlingPolicy = loadErrorHandlingPolicy
                    )
                }
                MergingMediaSource(hlsSource, *subSources.toTypedArray())
            }
        } else {
            mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
        }

        val syncMediaSource = AudioSyncMediaSource(
            mediaSource = baseMediaSource,
            audioDelayUsProvider = { audioDelayUs.get() }
        )
        val effectiveStartMs = AudioDelayMath.computeRealignSeekPositionMs(
            currentPositionMs = safeStart,
            audioDelayMs = _state.value.audioDelayMs
        )
        cancelPendingAudioDelayRealign()
        player.setMediaSource(syncMediaSource, effectiveStartMs)
        player.prepare()
        setVolumePercent(_state.value.volumePercent)
        player.playWhenReady = autoPlay
    }

    private fun isRecoverableCdnError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true
            else -> false
        }
    }

    private fun resetStatsSession(mediaUri: String, format: StreamFormat) {
        activeMediaUri = mediaUri
        activeMediaFormat = format
        activeMediaIsLocal = mediaUri.startsWith("content://") || mediaUri.startsWith("file://")
        windowBytesLoaded.set(0L)
        sessionBytesLoaded.set(0L)
        droppedFramesCount.set(0L)
        frameDropAnalyzer.reset(SystemClock.elapsedRealtime())
        hasReachedReadyOnce = false
        rebufferCount = 0
        rebufferStartedAtElapsedMs = 0L
        totalRebufferMs = 0L
        lastStatsSampleElapsedMs = SystemClock.elapsedRealtime()
        networkHistory.clear()
        bufferHistory.clear()
        val prefetchSnapshot = parallelPrefetchManager.snapshotStats()
        _networkStats.value = PlayerNetworkStats(
            streamHost = PlayerStatsFormatter.extractHostLabel(mediaUri, activeMediaIsLocal),
            streamFormatLabel = format.label,
            isLocalSource = activeMediaIsLocal,
            playbackStateLabel = "BUFFERING",
            targetBufferBytes = targetBufferBytes,
            asyncQueueingActive = asyncQueueingEnabled,
            activeCdnIndex = activeMirrorIndex + 1,
            totalCdnNodes = candidateUrls.size.coerceAtLeast(1),
            cdnFailoverCount = cdnFailoverCount,
            diskCacheUsedBytes = MediaEdgeCache.usedBytes(),
            diskCacheMaxBytes = MediaEdgeCache.maxBytes(),
            networkTransportLabel = httpDataSourceFactory.activeTransportLabel(),
            isRamDirectCacheMode = isRamDirectCacheMode,
            prefetchDepth = prefetchSnapshot.activeDepth,
            prefetchHitRatePercent = prefetchSnapshot.hitRatePercent,
            prefetchHits = prefetchSnapshot.cacheHits,
            privateDnsMode = fastDnsResolver.privateDnsMode,
            nordVpnActive = nordLynxTunnelActive || socksProxyManager.isProxyActive,
            nordVpnServerHost = nordLynxEndpointHost.ifBlank { socksProxyManager.config.host },
            systemVpnActive = SystemVpnMonitor.isSystemVpnActive(appContext)
        )
    }

    /**
     * Syncs live position into the isolated [progressState] flow without triggering full-screen
     * recompositions of [state] subscribers.
     */
    fun syncPlaybackProgress() {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val buffered = player.bufferedPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val isLive = player.isCurrentMediaItemLive
        val bufferAheadMs = (buffered - pos).coerceAtLeast(0L)
        parallelPrefetchManager.setLowBufferMode(bufferAheadMs < 8_000L)
        val isSwDec = DecoderFallbackHelper.isSoftwareCodecName(activeVideoDecoderName) ||
            effectiveDecoderMode == VideoDecoderMode.SOFTWARE
        val fdSnap = frameDropAnalyzer.snapshot(SystemClock.elapsedRealtime(), isSoftwareDecoder = isSwDec)
        parallelPrefetchManager.setCpuThrottleMode(fdSnap.shouldThrottlePrefetch)
        val cur = _progressState.value
        val loopStart = activeAbLoopStartMs
        val loopEnd = activeAbLoopEndMs
        if (loopStart != null && loopEnd != null && loopEnd > loopStart + 500L && pos >= loopEnd) {
            seekTo(loopStart)
            return
        }
        if (pos == cur.currentPositionMs &&
            buffered == cur.bufferedPositionMs &&
            dur == cur.durationMs &&
            isLive == cur.isLiveStream
        ) {
            return
        }
        _progressState.value = PlaybackProgressState(
            currentPositionMs = pos,
            bufferedPositionMs = buffered,
            durationMs = dur,
            isLiveStream = isLive
        )
    }

    /**
     * Snapshots live network speed, byte throughput, buffer health, allocator memory, JVM heap,
     * and video/audio codecs for the YouTube-style Stats for Nerds overlay.
     * Called only when the stats overlay is enabled so overhead is zero when hidden.
     */
    fun syncNetworkStats() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedSec = ((nowElapsed - lastStatsSampleElapsedMs).coerceAtLeast(250L)) / 1000.0
        lastStatsSampleElapsedMs = nowElapsed

        val bytesInWindow = windowBytesLoaded.getAndSet(0L)
        val activityBytesPerSec = (bytesInWindow / elapsedSec).toLong().coerceAtLeast(0L)

        val meterBps = bandwidthMeter.bitrateEstimate.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val connectionBps = when {
            latestBandwidthEstimateBps > 0L -> latestBandwidthEstimateBps
            meterBps > 0L -> meterBps
            activityBytesPerSec > 0L -> activityBytesPerSec * 8L
            else -> 0L
        }

        val currentPos = player.currentPosition.coerceAtLeast(0L)
        val bufferedPos = player.bufferedPosition.coerceAtLeast(0L)
        val bufferAheadMs = (bufferedPos - currentPos).coerceAtLeast(0L)
        val allocatedBytes = allocator.totalBytesAllocated.toLong().coerceAtLeast(0L)

        val runtime = Runtime.getRuntime()
        val jvmUsedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val jvmMaxBytes = runtime.maxMemory().coerceAtLeast(1L)

        val activeRebufferMs = if (rebufferStartedAtElapsedMs > 0L) {
            totalRebufferMs + (nowElapsed - rebufferStartedAtElapsedMs).coerceAtLeast(0L)
        } else {
            totalRebufferMs
        }

        if (networkHistory.size >= SPARKLINE_HISTORY_SIZE) networkHistory.removeFirst()
        networkHistory.addLast((activityBytesPerSec / 1024f).coerceAtLeast(0f))

        if (bufferHistory.size >= SPARKLINE_HISTORY_SIZE) bufferHistory.removeFirst()
        bufferHistory.addLast((bufferAheadMs / 1000f).coerceAtLeast(0f))

        val videoFmt = player.videoFormat
        val audioFmt = player.audioFormat
        val stateLabel = when (player.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> if (player.isPlaying) "PLAYING" else "PAUSED"
            Player.STATE_ENDED -> "ENDED"
            else -> "IDLE"
        }

        val prefetchSnapshot = parallelPrefetchManager.snapshotStats()
        val isSwDec = DecoderFallbackHelper.isSoftwareCodecName(activeVideoDecoderName) ||
            effectiveDecoderMode == VideoDecoderMode.SOFTWARE
        val fdSnap = frameDropAnalyzer.snapshot(nowElapsed, isSoftwareDecoder = isSwDec)
        parallelPrefetchManager.setCpuThrottleMode(fdSnap.shouldThrottlePrefetch)
        _networkStats.value = PlayerNetworkStats(
            streamHost = PlayerStatsFormatter.extractHostLabel(activeMediaUri, activeMediaIsLocal),
            streamFormatLabel = activeMediaFormat.label,
            isLocalSource = activeMediaIsLocal,
            playbackStateLabel = stateLabel,
            connectionSpeedBps = connectionBps,
            networkActivityBytesPerSec = activityBytesPerSec,
            sessionDownloadedBytes = sessionBytesLoaded.get(),
            bufferHealthMs = bufferAheadMs,
            bufferedPercent = player.bufferedPercentage.coerceIn(0, 100),
            allocatedBufferBytes = allocatedBytes,
            targetBufferBytes = targetBufferBytes,
            jvmHeapUsedBytes = jvmUsedBytes,
            jvmHeapMaxBytes = jvmMaxBytes,
            rebufferCount = rebufferCount,
            totalRebufferMs = activeRebufferMs,
            videoWidth = videoFmt?.width?.takeIf { it > 0 } ?: _state.value.videoWidth,
            videoHeight = videoFmt?.height?.takeIf { it > 0 } ?: _state.value.videoHeight,
            videoFps = videoFmt?.frameRate?.takeIf { it > 0f } ?: 0f,
            videoCodec = PlayerStatsFormatter.formatCodecLabel(videoFmt?.codecs, videoFmt?.sampleMimeType),
            videoBitrateBps = videoFmt?.bitrate?.takeIf { it > 0 } ?: 0,
            audioCodec = PlayerStatsFormatter.formatCodecLabel(audioFmt?.codecs, audioFmt?.sampleMimeType),
            audioChannels = audioFmt?.channelCount?.takeIf { it > 0 } ?: 0,
            audioSampleRateHz = audioFmt?.sampleRate?.takeIf { it > 0 } ?: 0,
            audioBitrateBps = audioFmt?.bitrate?.takeIf { it > 0 } ?: 0,
            droppedFrames = fdSnap.totalDropped,
            liveDroppedFramesPerSec = fdSnap.liveDropsPerSec,
            steadyDroppedFrames = fdSnap.steadyDropped,
            seekJoinDroppedFrames = fdSnap.seekJoinDropped,
            frameDropDiagnosis = fdSnap.diagnosis,
            asyncQueueingActive = asyncQueueingEnabled,
            activeCdnIndex = activeMirrorIndex + 1,
            totalCdnNodes = candidateUrls.size.coerceAtLeast(1),
            cdnFailoverCount = cdnFailoverCount,
            diskCacheUsedBytes = MediaEdgeCache.usedBytes(),
            diskCacheMaxBytes = MediaEdgeCache.maxBytes(),
            networkTransportLabel = httpDataSourceFactory.activeTransportLabel(),
            isRamDirectCacheMode = isRamDirectCacheMode,
            prefetchDepth = prefetchSnapshot.activeDepth,
            prefetchHitRatePercent = prefetchSnapshot.hitRatePercent,
            prefetchHits = prefetchSnapshot.cacheHits,
            activeVideoDecoderName = activeVideoDecoderName,
            effectiveDecoderMode = effectiveDecoderMode,
            privateDnsMode = fastDnsResolver.privateDnsMode,
            nordVpnActive = nordLynxTunnelActive || socksProxyManager.isProxyActive,
            nordVpnServerHost = nordLynxEndpointHost.ifBlank { socksProxyManager.config.host },
            systemVpnActive = SystemVpnMonitor.isSystemVpnActive(appContext),
            networkActivityHistoryKBps = networkHistory.toList(),
            bufferHealthHistorySec = bufferHistory.toList()
        )
    }

    /**
     * Immediately stops playback, detaches video surfaces (releasing 4K GPU/CPU ION buffers),
     * clears media items and calls allocator.trim() (returning 64 KB segments immediately to GC),
     * and releases native audio DSP effects before navigating back to HomeScreen.
     */
    fun stopAndTrimBuffers() {
        cancelPendingAudioDelayRealign()
        syncPlaybackProgress()
        parallelPrefetchManager.resetSession()
        player.playWhenReady = false
        player.clearVideoSurface()
        player.stop()
        player.clearMediaItems()
        allocator.trim()
        volumeBooster.release()
        currentTracks = Tracks.EMPTY
        _state.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                playbackState = Player.STATE_IDLE,
                currentPositionMs = _progressState.value.currentPositionMs,
                bufferedPositionMs = 0L,
                durationMs = _progressState.value.durationMs
            )
        }
    }

    fun hasLoadedMedia(): Boolean = player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        cancelPendingAudioDelayRealign()
        val clamped = positionMs.coerceAtLeast(0L)
        val effectiveSeekMs = AudioDelayMath.computeRealignSeekPositionMs(
            currentPositionMs = clamped,
            audioDelayMs = _state.value.audioDelayMs,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        )
        frameDropAnalyzer.markSeekOrTransition(SystemClock.elapsedRealtime())
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.seekTo(effectiveSeekMs)
        _progressState.update { it.copy(currentPositionMs = clamped) }
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val clamped = if (duration != null) target.coerceAtMost(duration) else target
        seekTo(clamped)
    }

    @Volatile private var activeAbLoopStartMs: Long? = null
    @Volatile private var activeAbLoopEndMs: Long? = null
    @Volatile private var sleepFadeMultiplier: Float = 1.0f
    @Volatile private var savedBaseSpeed: Float = 1.0f

    fun setAbLoopBounds(startMs: Long?, endMs: Long?) {
        activeAbLoopStartMs = startMs
        activeAbLoopEndMs = endMs
    }

    fun setPlaybackSpeed(speed: Float) {
        savedBaseSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(playbackSpeed = speed, temporarySpeedActive = false) }
    }

    fun setTemporarySpeed(tempSpeed: Float?) {
        if (tempSpeed != null) {
            if (!_state.value.temporarySpeedActive) {
                savedBaseSpeed = _state.value.playbackSpeed
            }
            player.playbackParameters = PlaybackParameters(tempSpeed)
            _state.update { it.copy(temporarySpeedActive = true) }
        } else {
            player.playbackParameters = PlaybackParameters(savedBaseSpeed)
            _state.update { it.copy(playbackSpeed = savedBaseSpeed, temporarySpeedActive = false) }
        }
    }

    fun setVolumePercent(percent: Int) {
        val clamped = AudioBoostMath.clampPercent(percent)
        val baseVol = AudioBoostMath.computePlayerVolume(clamped)
        player.volume = (baseVol * sleepFadeMultiplier).coerceIn(0f, 1f)
        volumeBooster.applyBoost(clamped, fallbackAudioSessionId = player.audioSessionId)
        _state.update { it.copy(volumePercent = clamped) }
    }

    fun setSleepFadeMultiplier(multiplier: Float) {
        sleepFadeMultiplier = multiplier.coerceIn(0f, 1f)
        val baseVol = AudioBoostMath.computePlayerVolume(_state.value.volumePercent)
        player.volume = (baseVol * sleepFadeMultiplier).coerceIn(0f, 1f)
    }

    fun setNightModeAudio(enabled: Boolean) {
        volumeBooster.setNightModeEnabled(enabled, fallbackAudioSessionId = player.audioSessionId)
        _state.update { it.copy(nightModeAudioEnabled = enabled) }
    }

    fun applyPreferredLanguages(audioLangCode: String, subtitleLangCode: String) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (audioLangCode.isBlank()) {
            builder.setPreferredAudioLanguages()
        } else {
            builder.setPreferredAudioLanguage(audioLangCode)
        }
        when {
            subtitleLangCode.equals("off", ignoreCase = true) -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                builder.setPreferredTextLanguages()
            }
            subtitleLangCode.isBlank() -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setPreferredTextLanguages()
            }
            else -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setPreferredTextLanguage(subtitleLangCode)
            }
        }
        player.trackSelectionParameters = builder.build()
        _state.update { it.copy(subtitlesDisabled = subtitleLangCode.equals("off", ignoreCase = true)) }
    }

    fun setAudioDelayMs(
        delayMs: Long,
        realignPlayback: Boolean = true,
        debounceMs: Long = 0L
    ) {
        val clamped = AudioDelayMath.clampDelayMs(delayMs)
        val previous = _state.value.audioDelayMs
        audioDelayUs.set(clamped * 1_000L)
        _state.update { it.copy(audioDelayMs = clamped) }
        if (realignPlayback && clamped != previous) {
            scheduleAudioDelayRealign(targetDelayMs = clamped, debounceMs = debounceMs)
        }
    }

    fun adjustAudioDelayMs(deltaMs: Long): Long {
        val next = AudioDelayMath.clampDelayMs(_state.value.audioDelayMs + deltaMs)
        setAudioDelayMs(next, realignPlayback = true, debounceMs = 180L)
        return next
    }

    fun resetAudioDelayMs() {
        setAudioDelayMs(0L, realignPlayback = true, debounceMs = 0L)
    }

    private fun cancelPendingAudioDelayRealign() {
        pendingAudioDelayRealign?.let { mainHandler.removeCallbacks(it) }
        pendingAudioDelayRealign = null
        pendingAudioRealignAnchorMs = null
    }

    /**
     * Performs an in-buffer exact micro-seek so [AudioSyncMediaSource] flushes `AudioSink` and
     * applies the updated audio timestamp offset immediately (even for fine ±25ms/±50ms changes
     * below DefaultAudioSink's 200ms discontinuity threshold) across HLS muxed/demuxed segments,
     * DASH, MKV, MP4, PCM, FFmpeg, and HDMI passthrough without reloading the stream.
     */
    private fun scheduleAudioDelayRealign(targetDelayMs: Long, debounceMs: Long) {
        pendingAudioDelayRealign?.let { mainHandler.removeCallbacks(it) }
        pendingAudioDelayRealign = null

        if (!hasLoadedMedia() ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val anchorMs = if (pendingAudioRealignAnchorMs != null &&
            (nowElapsed - pendingAudioRealignSetAtMs) < 2_500L
        ) {
            pendingAudioRealignAnchorMs!!
        } else {
            player.currentPosition.coerceAtLeast(0L).also {
                pendingAudioRealignAnchorMs = it
                pendingAudioRealignSetAtMs = nowElapsed
            }
        }

        val realignAction = Runnable {
            pendingAudioDelayRealign = null
            if (!hasLoadedMedia()) return@Runnable
            if (player.isCurrentMediaItemLive && !player.isCurrentMediaItemSeekable) return@Runnable
            val seekTargetMs = AudioDelayMath.computeRealignSeekPositionMs(
                currentPositionMs = anchorMs,
                audioDelayMs = targetDelayMs,
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            )
            frameDropAnalyzer.markSeekOrTransition(SystemClock.elapsedRealtime())
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo(seekTargetMs)
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }

        if (debounceMs > 0L) {
            pendingAudioDelayRealign = realignAction
            mainHandler.postDelayed(realignAction, debounceMs)
        } else {
            realignAction.run()
        }
    }

    fun selectSubtitleTrack(option: TrackOption) {
        val group = currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)))
            .build()
        _state.update { it.copy(subtitlesDisabled = false) }
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        _state.update { it.copy(subtitlesDisabled = true) }
    }

    fun selectAudioTrack(option: TrackOption) {
        val group = currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)))
            .build()
    }

    fun selectVideoTrack(option: TrackOption?) {
        val builder = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (option != null) {
            val group = currentTracks.groups.getOrNull(option.groupIndex)
            if (group != null) {
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)))
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    fun pause() {
        player.pause()
    }

    fun release() {
        cancelPendingAudioDelayRealign()
        parallelPrefetchManager.release()
        volumeBooster.release()
        player.removeAnalyticsListener(analyticsListener)
        player.removeListener(listener)
        player.clearVideoSurface()
        player.stop()
        player.clearMediaItems()
        allocator.trim()
        player.release()
    }

    private fun refreshTrackList(tracks: Tracks) {
        val videoList = mutableListOf<TrackOption>()
        val audioList = mutableListOf<TrackOption>()
        val subList = mutableListOf<TrackOption>()

        tracks.groups.forEachIndexed { groupIdx, group ->
            for (trackIdx in 0 until group.length) {
                if (!group.isTrackSupported(trackIdx)) continue
                val format = group.getTrackFormat(trackIdx)
                val selected = group.isTrackSelected(trackIdx)

                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        val height = format.height.takeIf { it > 0 }
                        val bitrateKbps = format.bitrate.takeIf { it > 0 }?.div(1000)
                        val label = height?.let { "${it}p" } ?: "Video Track ${videoList.size + 1}"
                        val detail = buildString {
                            if (format.width > 0 && format.height > 0) append("${format.width}×${format.height}")
                            if (bitrateKbps != null) {
                                if (isNotEmpty()) append(" · ")
                                append("${bitrateKbps} kbps")
                            }
                            format.frameRate.takeIf { it > 0f }?.let { fps ->
                                if (isNotEmpty()) append(" · ")
                                append("${fps.toInt()} fps")
                            }
                        }
                        videoList.add(
                            TrackOption(
                                id = format.id ?: "v_${groupIdx}_$trackIdx",
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                label = label,
                                detail = detail.ifBlank { "Adaptive Video" },
                                language = format.language,
                                isSelected = selected
                            )
                        )
                    }

                    C.TRACK_TYPE_AUDIO -> {
                        val langName = format.language?.let {
                            Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != "und" }
                        }
                        val label = format.label?.takeIf { it.isNotBlank() }
                            ?: langName
                            ?: "Audio Track ${audioList.size + 1}"
                        val detail = buildString {
                            format.channelCount.takeIf { it > 0 }?.let { ch ->
                                append(if (ch == 6) "5.1" else if (ch == 2) "Stereo" else "${ch}ch")
                            }
                            format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US)?.let { codec ->
                                if (isNotEmpty()) append(" · ")
                                append(codec)
                            }
                        }
                        audioList.add(
                            TrackOption(
                                id = format.id ?: "a_${groupIdx}_$trackIdx",
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                label = label,
                                detail = detail.ifBlank { "Audio" },
                                language = format.language,
                                isSelected = selected
                            )
                        )
                    }

                    C.TRACK_TYPE_TEXT -> {
                        val isExternal = format.label?.contains("[") == true
                        val langName = format.language?.let {
                            Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != "und" }
                        }
                        val label = format.label?.takeIf { it.isNotBlank() }
                            ?: langName
                            ?: "Subtitle Track ${subList.size + 1}"
                        val detail = if (isExternal) {
                            "Loaded External Subtitle"
                        } else {
                            "Embedded · ${SubtitleMediaSourceBuilder.formatSubtitleCodecLabel(format.sampleMimeType, format.codecs)}"
                        }
                        subList.add(
                            TrackOption(
                                id = format.id ?: "t_${groupIdx}_$trackIdx",
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                label = label,
                                detail = detail,
                                language = format.language,
                                isSelected = selected,
                                isExternal = isExternal
                            )
                        )
                    }
                }
            }
        }

        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        _state.update {
            it.copy(
                videoTracks = videoList.sortedByDescending { v -> v.label.removeSuffix("p").toIntOrNull() ?: 0 },
                audioTracks = audioList,
                subtitleTracks = subList,
                subtitlesDisabled = disabled
            )
        }
    }
}
