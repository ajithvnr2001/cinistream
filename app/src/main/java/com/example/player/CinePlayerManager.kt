package com.example.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
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
import androidx.media3.datasource.DefaultHttpDataSource
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
import androidx.media3.exoplayer.source.SingleSampleMediaSource
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
    val isDecoderError: Boolean = false,
    val failedDecoderName: String? = null,
    val surfaceResetToken: Int = 0,
    val ffmpegAvailable: Boolean = false
)

@OptIn(UnstableApi::class)
class CinePlayerManager(context: Context) {

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) CineStream/1.0 ExoPlayer"

        const val MIN_TARGET_BUFFER_BYTES = 12 * 1024 * 1024
        const val MAX_TARGET_BUFFER_BYTES = 32 * 1024 * 1024
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 20_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        const val TV_SAFE_MAX_VIDEO_WIDTH = 1920
        const val TV_SAFE_MAX_VIDEO_HEIGHT = 1080
        const val TV_SAFE_MAX_VIDEO_BITRATE = 12_000_000
        private const val SPARKLINE_HISTORY_SIZE = 24

        /**
         * Caps ExoPlayer's Java-heap media buffer to 20% of JVM maxMemory, clamped to 12–32 MB.
         * Prevents OOM crashes on 32-bit Android TVs (80 MB default heap -> 16 MB; 384 MB largeHeap -> 32 MB).
         */
        fun computeSafeTargetBufferBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
            return (maxHeapBytes * 0.20)
                .toLong()
                .coerceIn(MIN_TARGET_BUFFER_BYTES.toLong(), MAX_TARGET_BUFFER_BYTES.toLong())
                .toInt()
        }
    }

    val okHttpClient: OkHttpClient = (context.applicationContext as? CineStreamApplication)?.sharedOkHttpClient
        ?: OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 3, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    // Explicit BandwidthMeter shared between HTTP DataSource and ExoPlayer for live connection speed telemetry
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
        .setResetOnNetworkTypeChange(true)
        .build()

    // Use DefaultHttpDataSource for video streaming to avoid OkHttp HTTP/2 okio.Buffer segment churn on low-RAM TVs
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(DEFAULT_USER_AGENT)
        .setTransferListener(bandwidthMeter)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)

    // DefaultDataSource wraps DefaultHttpDataSource so remote http/https streams AND local content://
    // videos/subtitles seamlessly combine inside MergingMediaSource across all formats.
    private val directDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    // Automatically bypasses on-device disk edge cache after any decode error so neither HW nor SW
    // decoders can ever be re-fed a truncated or corrupted cached segment.
    @Volatile private var bypassEdgeCacheForSession: Boolean = false

    // TV-safe local disk edge cache (64 MB TV / 128 MB Mobile) caching VOD media segments while
    // bypassing .m3u8/.mpd manifests, subtitles, and local files.
    private val smartEdgeDataSourceFactory = MediaEdgeCache.createSmartDataSourceFactory(
        context = context,
        directDataSourceFactory = directDataSourceFactory,
        bypassCacheProvider = { bypassEdgeCacheForSession }
    )

    // Tolerant TS flags fix FFmpeg-muxed HEVC/H.264 HLS streams with CRA/BLA non-IDR keyframes
    private val tolerantTsFlags: Int =
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

    private val extractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setTsExtractorFlags(tolerantTsFlags)

    private val hlsMediaSourceFactory = HlsMediaSource.Factory(smartEdgeDataSourceFactory)
        .setExtractorFactory(DefaultHlsExtractorFactory(tolerantTsFlags, true))
        .setAllowChunklessPreparation(true)

    private val mediaSourceFactory = DefaultMediaSourceFactory(smartEdgeDataSourceFactory, extractorsFactory)

    val targetBufferBytes: Int = computeSafeTargetBufferBytes()

    // trimOnReset = true immediately returns 64 KB buffer segments to GC when stopping or switching media
    private val allocator = DefaultAllocator(
        /* trimOnReset = */ true,
        /* individualAllocationSize = */ C.DEFAULT_BUFFER_SEGMENT_SIZE
    )

    // Strict heap-capped LoadControl: respects targetBufferBytes over time thresholds and frees played frames immediately
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
            /* backBufferDurationMs = */ 0,
            /* retainBackBufferFromKeyframe = */ false
        )
        .build()

    // Cap adaptive HLS/DASH auto-selection on constrained devices so 4K renditions don't saturate RAM,
    // while enabling mixed-MIME (H.264 + HEVC) & non-seamless adaptiveness across all devices.
    private val trackSelector = DefaultTrackSelector(context).apply {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isConstrainedDevice = (activityManager?.isLowRamDevice == true) ||
            Runtime.getRuntime().maxMemory() <= 400L * 1024L * 1024L
        val builder = buildUponParameters()
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowVideoNonSeamlessAdaptiveness(true)
        if (isConstrainedDevice) {
            builder
                .setMaxVideoSize(TV_SAFE_MAX_VIDEO_WIDTH, TV_SAFE_MAX_VIDEO_HEIGHT)
                .setMaxVideoBitrate(TV_SAFE_MAX_VIDEO_BITRATE)
        }
        parameters = builder.build()
    }

    private val volumeBooster = AudioVolumeBooster()

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
    // Synchronous MediaCodec queueing prevents Qualcomm C2 async queue HEVC crashes on Snapdragon devices.
    private val renderersFactory = NextRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
        .forceDisableMediaCodecAsynchronousQueueing()
        .setMediaCodecSelector(smartMediaCodecSelector)
        .setAllowedVideoJoiningTimeMs(5_000L)

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
    private var activeHeaders: Map<String, String> = emptyMap()
    private var lastLoadedSubtitles: List<Pair<ExternalSubtitle, Uri>> = emptyList()
    private var lastAutoSelectSubtitleId: String? = null
    private var lastRequestedStartPosMs: Long = 0L
    private var lastAutoPlay: Boolean = true

    var onCdnFailover: ((host: String, nodeIndex: Int, totalNodes: Int) -> Unit)? = null
    var onDecoderFallback: ((failedCodec: String?, switchedMode: VideoDecoderMode) -> Unit)? = null
    var onTrackFallback: ((trackLabel: String) -> Unit)? = null
    var onMediaEnded: (() -> Unit)? = null

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
                    hasReachedReadyOnce = true
                }
                Player.STATE_BUFFERING -> {
                    if (hasReachedReadyOnce && rebufferStartedAtElapsedMs == 0L) {
                        rebufferCount++
                        rebufferStartedAtElapsedMs = nowElapsed
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

            // 2. Automatic Multi-CDN Mirror Failover at exact playback position on upstream network/IO errors
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

        // Fresh media resets per-session failed codec blocklist, cache bypass, track fallbacks & decoder mode
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
                effectiveDecoderMode = effectiveDecoderMode
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

        if (isHlsSource) {
            val baseItem = MediaItem.Builder()
                .setUri(Uri.parse(targetUrl))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            val hlsSource: MediaSource = hlsMediaSourceFactory.createMediaSource(baseItem)
            val finalSource: MediaSource = if (subtitleConfigs.isEmpty()) {
                hlsSource
            } else {
                val subSources = subtitleConfigs.map { subConfig ->
                    SingleSampleMediaSource.Factory(directDataSourceFactory)
                        .createMediaSource(subConfig, C.TIME_UNSET)
                }
                MergingMediaSource(hlsSource, *subSources.toTypedArray())
            }
            player.setMediaSource(finalSource, safeStart)
        } else {
            player.setMediaItem(mediaItemBuilder.build(), safeStart)
        }
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
        hasReachedReadyOnce = false
        rebufferCount = 0
        rebufferStartedAtElapsedMs = 0L
        totalRebufferMs = 0L
        lastStatsSampleElapsedMs = SystemClock.elapsedRealtime()
        networkHistory.clear()
        bufferHistory.clear()
        _networkStats.value = PlayerNetworkStats(
            streamHost = PlayerStatsFormatter.extractHostLabel(mediaUri, activeMediaIsLocal),
            streamFormatLabel = format.label,
            isLocalSource = activeMediaIsLocal,
            playbackStateLabel = "BUFFERING",
            targetBufferBytes = targetBufferBytes,
            activeCdnIndex = activeMirrorIndex + 1,
            totalCdnNodes = candidateUrls.size.coerceAtLeast(1),
            cdnFailoverCount = cdnFailoverCount,
            diskCacheUsedBytes = MediaEdgeCache.usedBytes(),
            diskCacheMaxBytes = MediaEdgeCache.maxBytes()
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
        val cur = _progressState.value
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
            droppedFrames = droppedFramesCount.get(),
            activeCdnIndex = activeMirrorIndex + 1,
            totalCdnNodes = candidateUrls.size.coerceAtLeast(1),
            cdnFailoverCount = cdnFailoverCount,
            diskCacheUsedBytes = MediaEdgeCache.usedBytes(),
            diskCacheMaxBytes = MediaEdgeCache.maxBytes(),
            activeVideoDecoderName = activeVideoDecoderName,
            effectiveDecoderMode = effectiveDecoderMode,
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
        syncPlaybackProgress()
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
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        _progressState.update { it.copy(currentPositionMs = clamped) }
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val clamped = if (duration != null) target.coerceAtMost(duration) else target
        seekTo(clamped)
    }

    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(playbackSpeed = speed) }
    }

    fun setVolumePercent(percent: Int) {
        val clamped = AudioBoostMath.clampPercent(percent)
        player.volume = AudioBoostMath.computePlayerVolume(clamped)
        volumeBooster.applyBoost(clamped, fallbackAudioSessionId = player.audioSessionId)
        _state.update { it.copy(volumePercent = clamped) }
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
                            "Embedded · ${format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "TEXT"}"
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
