package com.example.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import com.example.CineStreamApplication
import com.example.model.ExternalSubtitle
import com.example.model.StreamFormat
import com.example.model.TrackOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    val subtitlesDisabled: Boolean = false
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

    // Use DefaultHttpDataSource for video streaming to avoid OkHttp HTTP/2 okio.Buffer segment churn on low-RAM TVs
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(DEFAULT_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)

    // DefaultDataSource wraps DefaultHttpDataSource so remote http/https streams AND local content://
    // videos/subtitles seamlessly combine inside MergingMediaSource across all formats.
    private val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    private val extractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)

    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

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

    // Cap adaptive HLS/DASH auto-selection on constrained devices so 4K renditions don't saturate RAM
    private val trackSelector = DefaultTrackSelector(context).apply {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isConstrainedDevice = (activityManager?.isLowRamDevice == true) ||
            Runtime.getRuntime().maxMemory() <= 400L * 1024L * 1024L
        if (isConstrainedDevice) {
            parameters = buildUponParameters()
                .setMaxVideoSize(TV_SAFE_MAX_VIDEO_WIDTH, TV_SAFE_MAX_VIDEO_HEIGHT)
                .setMaxVideoBitrate(TV_SAFE_MAX_VIDEO_BITRATE)
                .build()
        }
    }

    val audioCaptureProcessor = PcmAudioCaptureProcessor()
    private val volumeBooster = AudioVolumeBooster()

    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(audioCaptureProcessor))
                .build()
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
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

    private var currentTracks: Tracks = Tracks.EMPTY
    private var pendingAutoSelectSubtitleId: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
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
            val readable = buildString {
                append(
                    error.errorCodeName.replace('_', ' ').lowercase(Locale.US)
                        .replaceFirstChar { it.titlecase(Locale.US) }
                )
                error.cause?.localizedMessage?.takeIf { it.isNotBlank() }?.let { cause ->
                    append(": ")
                    append(cause)
                }
            }
            _state.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = readable
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
        autoSelectSubtitleId: String? = null
    ) {
        pendingAutoSelectSubtitleId = autoSelectSubtitleId
        audioCaptureProcessor.clearCapturedAudio()
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
            .setUri(Uri.parse(mediaUri))
            .setSubtitleConfigurations(subtitleConfigs)

        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        val safeStart = startPositionMs.coerceAtLeast(0L)
        _progressState.value = PlaybackProgressState(
            currentPositionMs = safeStart,
            bufferedPositionMs = safeStart,
            durationMs = 0L,
            isLiveStream = false
        )
        _state.update {
            it.copy(
                errorMessage = null,
                isBuffering = true,
                subtitlesDisabled = false,
                currentPositionMs = safeStart,
                bufferedPositionMs = safeStart
            )
        }

        // Ensure text tracks are enabled when loading subtitles
        if (subtitleConfigs.isNotEmpty()) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }

        player.setMediaItem(mediaItemBuilder.build(), safeStart)
        player.prepare()
        setVolumePercent(_state.value.volumePercent)
        player.playWhenReady = autoPlay
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
     * Immediately stops playback and clears all media items so [DefaultAllocator] (trimOnReset=true)
     * releases buffered video segments back to GC before navigating back to HomeScreen.
     */
    fun stopAndTrimBuffers() {
        syncPlaybackProgress()
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        audioCaptureProcessor.clearCapturedAudio()
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
        audioCaptureProcessor.clearCapturedAudio()
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
        player.stop()
        player.clearMediaItems()
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
