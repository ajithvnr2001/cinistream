package com.example.player

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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
    }

    val okHttpClient: OkHttpClient = (context.applicationContext as? CineStreamApplication)?.sharedOkHttpClient
        ?: OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent(DEFAULT_USER_AGENT)

    // DefaultDataSource wraps OkHttpDataSource so remote http/https streams AND local content://
    // videos/subtitles seamlessly combine inside MergingMediaSource across all formats.
    private val dataSourceFactory = DefaultDataSource.Factory(context, okHttpDataSourceFactory)

    private val extractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)

    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

    // Fast startup (1.5s vs default 2.5s) and 15s retained keyframe back-buffer for instant -10s rewind
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
        .setBackBuffer(15_000, true)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

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

    private var currentTracks: Tracks = Tracks.EMPTY
    private var pendingAutoSelectSubtitleId: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    playbackState = playbackState,
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: it.durationMs,
                    isLiveStream = player.isCurrentMediaItemLive,
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
                append(error.errorCodeName.replace('_', ' ').lowercase(Locale.US)
                    .replaceFirstChar { it.titlecase(Locale.US) })
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

        _state.update {
            it.copy(
                errorMessage = null,
                isBuffering = true,
                subtitlesDisabled = false
            )
        }

        // Ensure text tracks are enabled when loading subtitles
        if (subtitleConfigs.isNotEmpty()) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }

        player.setMediaItem(mediaItemBuilder.build(), startPositionMs.coerceAtLeast(0L))
        player.prepare()
        setVolumePercent(_state.value.volumePercent)
        player.playWhenReady = autoPlay
    }

    fun syncPlaybackProgress() {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val buffered = player.bufferedPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val isLive = player.isCurrentMediaItemLive
        val cur = _state.value
        if (pos == cur.currentPositionMs &&
            buffered == cur.bufferedPositionMs &&
            dur == cur.durationMs &&
            isLive == cur.isLiveStream
        ) {
            return
        }
        _state.update {
            it.copy(
                currentPositionMs = pos,
                bufferedPositionMs = buffered,
                durationMs = dur,
                isLiveStream = isLive
            )
        }
    }

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
        _state.update { it.copy(currentPositionMs = clamped) }
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
