package com.example.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.data.OfflineDownloadEntity
import com.example.model.AbLoopState
import com.example.model.AspectRatioMode
import com.example.model.ChapterMarker
import com.example.model.ChannelEpgNowNext
import com.example.model.JsonPlaylistItem
import com.example.model.M3uEntry
import com.example.model.SeriesEpisode
import com.example.model.SleepTimerMode
import com.example.model.SleepTimerUiState
import com.example.model.StreamFormat
import com.example.model.SubtitleAppearance
import com.example.model.SubtitleBgPreset
import com.example.model.SubtitleColorPreset
import com.example.model.SubtitleTextSize
import com.example.model.TrackOption
import com.example.model.UpNextCountdownState
import com.example.player.AudioBoostMath
import com.example.player.MediaEdgeCache
import com.example.player.NetworkTransportMode
import com.example.player.PlaybackProgressState
import com.example.player.PlayerNetworkStats
import com.example.player.PlayerPlaybackState
import com.example.player.VideoDecoderMode
import com.example.ui.components.rememberIsAndroidTv
import com.example.ui.components.rememberLocalFilePicker
import com.example.ui.theme.CinemaAmber
import com.example.viewmodel.ActiveMediaSession
import com.example.viewmodel.ChannelZapBannerState
import com.example.viewmodel.EpgUiState
import com.example.viewmodel.LoadedJsonPlaylist
import com.example.viewmodel.SecondarySubtitleUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class AppliedPlayerViewConfig(
    val aspectRatioMode: AspectRatioMode,
    val subtitleAppearance: SubtitleAppearance,
    val isTv: Boolean
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    player: Player,
    session: ActiveMediaSession?,
    playbackState: PlayerPlaybackState,
    progressStateFlow: StateFlow<PlaybackProgressState> = remember { MutableStateFlow(PlaybackProgressState()) },
    networkStatsFlow: StateFlow<PlayerNetworkStats> = remember { MutableStateFlow(PlayerNetworkStats()) },
    statsForNerdsEnabled: Boolean = false,
    subtitleAppearance: SubtitleAppearance,
    aspectRatioMode: AspectRatioMode,
    m3uEntries: List<M3uEntry>,
    activeM3uEntryId: String?,
    jsonPlaylistLabel: String? = null,
    jsonPlaylistItems: List<JsonPlaylistItem> = emptyList(),
    jsonPlaylists: List<LoadedJsonPlaylist> = emptyList(),
    selectedJsonPlaylistId: String? = null,
    activeJsonItemId: String? = null,
    activeSeries: JsonPlaylistItem? = null,
    activeEpisodeId: String? = null,
    hasNextEpisode: Boolean = false,
    diskCacheUsedBytes: Long = 0L,
    diskCacheMaxBytes: Long = 0L,
    isEnrichingImdb: Boolean = false,
    canRefreshJson: Boolean = false,
    toastMessage: String?,
    onBack: () -> Unit,
    onPauseAndSave: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetVolumePercent: (Int) -> Unit = {},
    onToggleStatsForNerds: () -> Unit = {},
    onCycleAspectRatio: () -> Unit,
    onLoadSubtitleFile: (Uri) -> Unit,
    onLoadSubtitleUrl: (String) -> Unit,
    onSelectSubtitleTrack: (TrackOption) -> Unit,
    onDisableSubtitles: () -> Unit,
    onAdjustSubtitleOffset: (Long) -> Unit,
    onSetSubtitleOffset: ((Long) -> Unit)? = null,
    onResetSubtitleOffset: () -> Unit,
    onUpdateSubtitleSize: (SubtitleTextSize) -> Unit,
    onUpdateSubtitleColor: (SubtitleColorPreset) -> Unit,
    onUpdateSubtitleBg: (SubtitleBgPreset) -> Unit,
    onSelectVideoTrack: (TrackOption?) -> Unit,
    onSelectAudioTrack: (TrackOption) -> Unit,
    onRetryFormat: (StreamFormat) -> Unit,
    onSelectM3uEntry: (M3uEntry) -> Unit,
    onSelectJsonItem: (JsonPlaylistItem) -> Unit = {},
    onSelectJsonPlaylist: (String?) -> Unit = {},
    onRemoveJsonPlaylist: ((String) -> Unit)? = null,
    onAddJsonPlaylistUrl: ((String) -> Unit)? = null,
    onSelectSeriesEpisode: (JsonPlaylistItem, SeriesEpisode) -> Unit = { _, _ -> },
    onPlayNextEpisode: () -> Unit = {},
    onSwitchCdnMirror: () -> Unit = {},
    onClearEdgeCache: () -> Unit = {},
    onSelectDecoderMode: (VideoDecoderMode) -> Unit = {},
    onSelectTransportMode: (NetworkTransportMode) -> Unit = {},
    networkPrivacyBadge: String = "1.1.1.1 / VPN",
    isPrivacyActive: Boolean = false,
    onOpenNetworkPrivacy: (() -> Unit)? = null,
    onRetryWithDecoderMode: (VideoDecoderMode) -> Unit = {},
    onSkipBadSegment: () -> Unit = {},
    onRefreshJsonPlaylist: () -> Unit = {},
    isInPipMode: Boolean = false,
    backgroundAudioEnabled: Boolean = false,
    episodeProgressMap: Map<String, Float> = emptyMap(),
    onEnterPip: (() -> Unit)? = null,
    onToggleBackgroundAudio: (Boolean) -> Unit = {},
    onAdjustAudioDelay: (Long) -> Unit = {},
    onSetAudioDelay: ((Long) -> Unit)? = null,
    onResetAudioDelay: () -> Unit = {},
    onPersistProgressOnly: () -> Unit = {},
    seekIntervalSeconds: Int = 10,
    onSelectSeekInterval: (Int) -> Unit = {},
    nightModeAudioEnabled: Boolean = false,
    onToggleNightModeAudio: (Boolean) -> Unit = {},
    sleepTimerState: SleepTimerUiState = SleepTimerUiState(),
    onSelectSleepTimer: (SleepTimerMode) -> Unit = {},
    preferredAudioLanguage: String = "",
    onSelectPreferredAudioLanguage: (String) -> Unit = {},
    preferredSubtitleLanguage: String = "",
    onSelectPreferredSubtitleLanguage: (String) -> Unit = {},
    abLoopState: AbLoopState = AbLoopState(),
    onSetAbLoopPointA: () -> Unit = {},
    onSetAbLoopPointB: () -> Unit = {},
    onClearAbLoop: () -> Unit = {},
    chapters: List<ChapterMarker> = emptyList(),
    onJumpToChapter: (ChapterMarker) -> Unit = {},
    upNextCountdown: UpNextCountdownState? = null,
    onPlayUpNextNow: () -> Unit = {},
    onDismissUpNext: () -> Unit = {},
    onSetTemporaryFastForward: (Boolean) -> Unit = {},
    onSkipIntro: () -> Unit = {},
    isFavorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    activeDownload: OfflineDownloadEntity? = null,
    activeDownloadSpeedBps: Long = 0L,
    onDownloadStream: (() -> Unit)? = null,
    onPauseDownload: ((Long) -> Unit)? = null,
    onResumeDownload: ((Long) -> Unit)? = null,
    onCancelDownload: ((Long) -> Unit)? = null,
    onOpenDownloads: (() -> Unit)? = null,
    secondarySubtitleState: SecondarySubtitleUiState = SecondarySubtitleUiState(),
    onLoadSecondarySubtitleFile: (Uri) -> Unit = {},
    onLoadSecondarySubtitleUrl: (String) -> Unit = {},
    onClearSecondarySubtitle: () -> Unit = {},
    epgState: EpgUiState = EpgUiState(),
    onLoadEpgUrl: (String) -> Unit = {},
    resolveChannelEpg: (M3uEntry) -> ChannelEpgNowNext? = { null },
    favoriteMediaKeys: Set<String> = emptySet(),
    onToggleFavoriteM3uEntry: (M3uEntry) -> Unit = {},
    channelZapBanner: ChannelZapBannerState? = null,
    onZapChannel: (Int) -> Unit = {},
    onToastShown: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isTv = rememberIsAndroidTv()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val seekBarFocusRequester = remember { FocusRequester() }
    val subButtonFocusRequester = remember { FocusRequester() }
    val brightnessButtonFocusRequester = remember { FocusRequester() }
    val statsButtonFocusRequester = remember { FocusRequester() }
    val episodesButtonFocusRequester = remember { FocusRequester() }
    val speedButtonFocusRequester = remember { FocusRequester() }
    val volumeButtonFocusRequester = remember { FocusRequester() }
    val tracksButtonFocusRequester = remember { FocusRequester() }
    val m3uButtonFocusRequester = remember { FocusRequester() }
    val jsonButtonFocusRequester = remember { FocusRequester() }

    var controlsVisible by remember { mutableStateOf(true) }
    var focusSeekBarOnReveal by remember { mutableStateOf(false) }
    var lastInputWasKey by remember { mutableStateOf(isTv) }
    var returnFocusTarget by remember { mutableStateOf<FocusRequester?>(null) }
    var isLocked by rememberSaveable { mutableStateOf(false) }
    var isLandscape by rememberSaveable { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var showM3uSheet by remember { mutableStateOf(false) }
    var showJsonSheet by remember { mutableStateOf(false) }
    var showSeriesSheet by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showVolumeBoostDialog by remember { mutableStateOf(false) }
    var showBrightnessPanel by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }

    // Seek, Volume, Brightness & Pinch-Zoom visual feedback HUD state
    var seekBadgeText by remember { mutableStateOf<String?>(null) }
    var isVolumeHudVisible by remember { mutableStateOf(false) }
    var volumeHudNonce by remember { mutableIntStateOf(0) }
    var brightnessPercent by remember(isTv) {
        mutableIntStateOf(
            if (isTv) {
                PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT
            } else {
                PlayerGestureMath.readWindowBrightnessPercent(activity)
            }
        )
    }
    var isBrightnessHudVisible by remember { mutableStateOf(false) }
    var brightnessHudNonce by remember { mutableIntStateOf(0) }
    var zoomScale by remember { mutableFloatStateOf(PlayerGestureMath.MIN_ZOOM_SCALE) }
    var isZoomHudVisible by remember { mutableStateOf(false) }
    var zoomHudNonce by remember { mutableIntStateOf(0) }

    val currentVolumeState = rememberUpdatedState(playbackState.volumePercent)
    val latestOnSetVolumePercent = rememberUpdatedState(onSetVolumePercent)
    val latestIsInPip = rememberUpdatedState(isInPipMode)
    val latestBgAudio = rememberUpdatedState(backgroundAudioEnabled)
    val latestOnPauseAndSave = rememberUpdatedState(onPauseAndSave)
    val latestOnPersistProgressOnly = rememberUpdatedState(onPersistProgressOnly)
    var isScrubbing by remember { mutableStateOf(false) }
    var pickingSecondarySub by remember { mutableStateOf(false) }
    var skipIntroDismissedForUri by remember(session?.sourceUri) { mutableStateOf(false) }
    val latestSeekIntervalSec = rememberUpdatedState(seekIntervalSeconds.coerceIn(5, 30))
    val latestOnTempFf = rememberUpdatedState(onSetTemporaryFastForward)

    val subtitleFilePicker = rememberLocalFilePicker(
        onSubtitlePicked = { uri ->
            showSubtitleSheet = false
            if (pickingSecondarySub) {
                pickingSecondarySub = false
                onLoadSecondarySubtitleFile(uri)
            } else {
                onLoadSubtitleFile(uri)
            }
        },
        onPickerUnavailable = {
            pickingSecondarySub = false
            coroutineScope.launch {
                snackbarHostState.showSnackbar("No file picker app found on this device. Use Subtitle URL to load captions.")
            }
        }
    )

    val handleExit: () -> Unit = {
        if (!isTv) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // onBack frees ExoPlayer buffers before popping the backstack
        onBack()
    }

    // On TV / D-Pad, pressing Back first closes Brightness panel, then dismisses controls cleanly
    BackHandler {
        if (showBrightnessPanel) {
            showBrightnessPanel = false
            returnFocusTarget = brightnessButtonFocusRequester
            interactionNonce++
        } else if ((isTv || lastInputWasKey) && controlsVisible && playbackState.errorMessage == null && !isLocked) {
            controlsVisible = false
            runCatching { rootFocusRequester.requestFocus() }
        } else {
            handleExit()
        }
    }

    // Focus management for Android TV remotes and keyboards
    LaunchedEffect(
        controlsVisible,
        focusSeekBarOnReveal,
        isLocked,
        playbackState.isBuffering,
        playbackState.errorMessage,
        showSubtitleSheet,
        showTracksSheet,
        showM3uSheet,
        showJsonSheet,
        showSeriesSheet,
        showSpeedMenu,
        showVolumeBoostDialog,
        showBrightnessPanel
    ) {
        val noModalOpen = !showSubtitleSheet && !showTracksSheet && !showM3uSheet &&
            !showJsonSheet && !showSeriesSheet && !showSpeedMenu && !showVolumeBoostDialog &&
            !showBrightnessPanel && playbackState.errorMessage == null
        if (noModalOpen) {
            if (controlsVisible && !isLocked && (isTv || lastInputWasKey)) {
                delay(60L)
                val pendingTarget = returnFocusTarget
                if (pendingTarget != null) {
                    returnFocusTarget = null
                    runCatching { pendingTarget.requestFocus() }
                } else if (focusSeekBarOnReveal) {
                    focusSeekBarOnReveal = false
                    runCatching { seekBarFocusRequester.requestFocus() }
                } else if (!playbackState.isBuffering) {
                    runCatching { playPauseFocusRequester.requestFocus() }
                } else {
                    runCatching { subButtonFocusRequester.requestFocus() }
                }
            } else if (!controlsVisible) {
                delay(30L)
                runCatching { rootFocusRequester.requestFocus() }
            }
        }
    }

    LaunchedEffect(toastMessage) {
        if (!toastMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(toastMessage)
            onToastShown()
        }
    }

    LaunchedEffect(seekBadgeText) {
        if (seekBadgeText != null) {
            delay(750L)
            seekBadgeText = null
        }
    }

    LaunchedEffect(volumeHudNonce) {
        if (volumeHudNonce > 0) {
            isVolumeHudVisible = true
            delay(1150L)
            isVolumeHudVisible = false
        }
    }

    LaunchedEffect(brightnessHudNonce) {
        if (brightnessHudNonce > 0) {
            isBrightnessHudVisible = true
            delay(1150L)
            isBrightnessHudVisible = false
        }
    }

    LaunchedEffect(zoomHudNonce) {
        if (zoomHudNonce > 0) {
            isZoomHudVisible = true
            delay(1150L)
            isZoomHudVisible = false
        }
    }

    LaunchedEffect(isInPipMode) {
        if (isInPipMode) {
            zoomScale = PlayerGestureMath.MIN_ZOOM_SCALE
            controlsVisible = false
            showSubtitleSheet = false
            showTracksSheet = false
            showM3uSheet = false
            showJsonSheet = false
            showSeriesSheet = false
            showSpeedMenu = false
            showVolumeBoostDialog = false
            showBrightnessPanel = false
        }
    }

    LaunchedEffect(
        controlsVisible,
        playbackState.isPlaying,
        isScrubbing,
        showSubtitleSheet,
        showTracksSheet,
        showM3uSheet,
        showJsonSheet,
        showSeriesSheet,
        showSpeedMenu,
        showVolumeBoostDialog,
        showBrightnessPanel,
        interactionNonce
    ) {
        val canAutoHide = controlsVisible && playbackState.isPlaying && !isScrubbing &&
            !showSubtitleSheet && !showTracksSheet && !showM3uSheet && !showJsonSheet &&
            !showSeriesSheet && !showSpeedMenu && !showVolumeBoostDialog && !showBrightnessPanel
        if (canAutoHide) {
            delay(if (isTv) 5000L else 4000L)
            controlsVisible = false
        }
    }

    // Keep screen on, manage immersive bars, and restore window brightness override on exit
    DisposableEffect(activity, view) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                val lp = window.attributes
                if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                }
            }
            if (!isTv) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(controlsVisible, activity, isInPipMode) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible && !isInPipMode) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Pause playback on ON_STOP unless Picture-in-Picture or Background Audio is active
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!latestIsInPip.value && !latestBgAudio.value) {
                    latestOnPauseAndSave.value()
                } else {
                    latestOnPersistProgressOnly.value()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val safeInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.displayCutout)
    val tvOverscanHorizontal = if (isTv) 28.dp else 12.dp
    val tvOverscanVertical = if (isTv) 20.dp else 10.dp
    val hasActiveSubtitles = !playbackState.subtitlesDisabled && playbackState.subtitleTracks.any { it.isSelected }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable(enabled = !controlsVisible)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (showSubtitleSheet || showTracksSheet || showM3uSheet || showJsonSheet ||
                    showSeriesSheet || showSpeedMenu || showVolumeBoostDialog || showBrightnessPanel
                ) {
                    return@onPreviewKeyEvent false
                }
                lastInputWasKey = true
                interactionNonce++

                when (keyEvent.key) {
                    Key.MediaPlayPause, Key.Spacebar -> {
                        onTogglePlayPause()
                        controlsVisible = true
                        true
                    }
                    Key.MediaPlay -> {
                        if (!playbackState.isPlaying) onTogglePlayPause()
                        controlsVisible = true
                        true
                    }
                    Key.MediaPause -> {
                        if (playbackState.isPlaying) onTogglePlayPause()
                        controlsVisible = true
                        true
                    }
                    Key.MediaRewind, Key.MediaSkipBackward -> {
                        val sec = latestSeekIntervalSec.value
                        onSeekBy(-sec * 1_000L)
                        seekBadgeText = "-${sec}s"
                        true
                    }
                    Key.MediaFastForward, Key.MediaSkipForward -> {
                        val sec = latestSeekIntervalSec.value
                        onSeekBy(sec * 1_000L)
                        seekBadgeText = "+${sec}s"
                        true
                    }
                    Key.ChannelUp -> {
                        if (m3uEntries.size >= 2) {
                            onZapChannel(1)
                            true
                        } else false
                    }
                    Key.ChannelDown -> {
                        if (m3uEntries.size >= 2) {
                            onZapChannel(-1)
                            true
                        } else false
                    }
                    Key.Captions -> {
                        showSubtitleSheet = true
                        true
                    }
                    Key.DirectionLeft -> {
                        if (!controlsVisible && !isLocked) {
                            val sec = latestSeekIntervalSec.value
                            onSeekBy(-sec * 1_000L)
                            seekBadgeText = "-${sec}s"
                            true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (!controlsVisible && !isLocked) {
                            val sec = latestSeekIntervalSec.value
                            onSeekBy(sec * 1_000L)
                            seekBadgeText = "+${sec}s"
                            true
                        } else false
                    }
                    Key.DirectionDown -> {
                        if (!controlsVisible) {
                            focusSeekBarOnReveal = true
                            controlsVisible = true
                            true
                        } else false
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp -> {
                        if (!controlsVisible) {
                            focusSeekBarOnReveal = false
                            controlsVisible = true
                            true
                        } else false
                    }
                    else -> false
                }
            }
    ) {
        // Video Surface + Native SubtitleView via Media3 PlayerView (keyed on surfaceResetToken so
        // HW MediaCodec <-> FFmpeg CPU transitions always mount a fresh Surface without BufferQueue lock)
        key(playbackState.surfaceResetToken) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val effectiveZoom = if (isInPipMode) PlayerGestureMath.MIN_ZOOM_SCALE else zoomScale
                        scaleX = effectiveZoom
                        scaleY = effectiveZoom
                    }
                    .testTag("exoplayer_surface"),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        isFocusable = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setShutterBackgroundColor(AndroidColor.BLACK)
                    }
                },
                update = { playerView ->
                    if (playerView.player !== player) {
                        playerView.player = player
                    }
                    val desiredConfig = AppliedPlayerViewConfig(aspectRatioMode, subtitleAppearance, isTv)
                    if (playerView.tag != desiredConfig) {
                        playerView.tag = desiredConfig
                        playerView.resizeMode = when (aspectRatioMode) {
                            AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            AspectRatioMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                        playerView.subtitleView?.let { subView ->
                            subView.setApplyEmbeddedStyles(false)
                            subView.setApplyEmbeddedFontSizes(false)
                            val tvBoost = if (isTv) 1.35f else 1.0f
                            subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleAppearance.textSize.sp * tvBoost)
                            subView.setStyle(
                                CaptionStyleCompat(
                                    subtitleAppearance.colorPreset.argb,
                                    subtitleAppearance.bgPreset.argb,
                                    AndroidColor.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                    AndroidColor.BLACK,
                                    Typeface.SANS_SERIF
                                )
                            )
                            subView.setBottomPaddingFraction(if (isTv) 0.10f else 0.08f)
                        }
                    }
                },
                onRelease = { playerView ->
                    playerView.player = null
                }
            )
        }

        // Android TV Live Video Brightness Filter (software dim/boost over video surface so
        // HDMI TV boxes and all TV panels reflect brightness changes even if window backlight is ignored)
        if (isTv && !isInPipMode) {
            val dimAlpha = PlayerGestureMath.computeTvDimOverlayAlpha(brightnessPercent)
            val boostAlpha = PlayerGestureMath.computeTvBoostOverlayAlpha(brightnessPercent)
            if (dimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dimAlpha))
                        .testTag("tv_brightness_dim_overlay")
                )
            } else if (boostAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = boostAlpha))
                        .testTag("tv_brightness_boost_overlay")
                )
            }
        }

        // Touch Tap, Double-Tap Seek, Left Brightness Swipe, Right 0-200% Sound Swipe & 2-Finger Pinch Zoom
        if (!isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isLocked, isTv, isInPipMode) {
                        if (isLocked || isTv || isInPipMode) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange != 1f) {
                                        zoomScale = PlayerGestureMath.clampZoomScale(zoomScale * zoomChange)
                                        zoomHudNonce++
                                        interactionNonce++
                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) change.consume()
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(isLocked, isTv) {
                        detectTapGestures(
                            onTap = {
                                lastInputWasKey = false
                                controlsVisible = !controlsVisible
                                interactionNonce++
                            },
                            onDoubleTap = { offset ->
                                if (isLocked) return@detectTapGestures
                                val sec = latestSeekIntervalSec.value
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                when {
                                    offset.x < width * 0.35f -> {
                                        onSeekBy(-sec * 1_000L)
                                        seekBadgeText = "-${sec}s"
                                        interactionNonce++
                                    }
                                    offset.x > width * 0.65f -> {
                                        onSeekBy(sec * 1_000L)
                                        seekBadgeText = "+${sec}s"
                                        interactionNonce++
                                    }
                                    else -> {
                                        onTogglePlayPause()
                                        interactionNonce++
                                    }
                                }
                            },
                            onPress = {
                                if (isLocked || isTv) {
                                    tryAwaitRelease()
                                    return@detectTapGestures
                                }
                                var activated2x = false
                                val holdJob = coroutineScope.launch {
                                    delay(450L)
                                    activated2x = true
                                    latestOnTempFf.value(true)
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    holdJob.cancel()
                                    if (activated2x) {
                                        latestOnTempFf.value(false)
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(isLocked, isTv) {
                        var dragZone = 0 // -1 = left brightness, +1 = right volume
                        var dragVolumeFloat = 100f
                        var dragBrightnessFloat = 50f
                        detectVerticalDragGestures(
                            onDragStart = { startOffset ->
                                if (isLocked || isTv) {
                                    dragZone = 0
                                } else if (startOffset.x < size.width * 0.50f) {
                                    dragZone = -1
                                    dragBrightnessFloat = brightnessPercent.toFloat()
                                } else {
                                    dragZone = 1
                                    dragVolumeFloat = currentVolumeState.value.toFloat()
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (dragZone == 0) return@detectVerticalDragGestures
                                change.consume()
                                val travelPx = (size.height * 0.55f).coerceAtLeast(180f)
                                if (dragZone == -1) {
                                    val delta = (-dragAmount / travelPx) *
                                        (PlayerGestureMath.MAX_BRIGHTNESS_PERCENT - PlayerGestureMath.MIN_BRIGHTNESS_PERCENT).toFloat()
                                    dragBrightnessFloat = (dragBrightnessFloat + delta).coerceIn(
                                        PlayerGestureMath.MIN_BRIGHTNESS_PERCENT.toFloat(),
                                        PlayerGestureMath.MAX_BRIGHTNESS_PERCENT.toFloat()
                                    )
                                    val updatedPct = PlayerGestureMath.clampBrightnessPercent(dragBrightnessFloat.roundToInt())
                                    brightnessPercent = updatedPct
                                    PlayerGestureMath.applyWindowBrightnessPercent(activity, updatedPct)
                                    brightnessHudNonce++
                                    interactionNonce++
                                } else if (dragZone == 1) {
                                    val delta = (-dragAmount / travelPx) * AudioBoostMath.MAX_PERCENT.toFloat()
                                    dragVolumeFloat = (dragVolumeFloat + delta)
                                        .coerceIn(
                                            AudioBoostMath.MIN_PERCENT.toFloat(),
                                            AudioBoostMath.MAX_PERCENT.toFloat()
                                        )
                                    latestOnSetVolumePercent.value(dragVolumeFloat.roundToInt())
                                    volumeHudNonce++
                                    interactionNonce++
                                }
                            }
                        )
                    }
            )
        }

        // YouTube-style "Stats for Nerds" Floating Network & Buffer Overlay
        if (statsForNerdsEnabled && !isInPipMode && playbackState.errorMessage == null) {
            PlayerStatsOverlay(
                statsFlow = networkStatsFlow,
                isTv = isTv,
                showCloseButton = controlsVisible && !isLocked,
                onClose = onToggleStatsForNerds,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                    .padding(
                        start = tvOverscanHorizontal,
                        end = tvOverscanHorizontal,
                        top = if (controlsVisible && !isLocked) {
                            if (isTv) 64.dp else 108.dp
                        } else {
                            tvOverscanVertical
                        }
                    )
            )
        }

        // Buffering Indicator
        if (playbackState.isBuffering && playbackState.errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (isInPipMode) 32.dp else 52.dp),
                color = CinemaAmber,
                strokeWidth = 3.5.dp
            )
        }

        // Seek Feedback Badge
        AnimatedVisibility(
            visible = seekBadgeText != null && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.55f))
            ) {
                Text(
                    text = seekBadgeText.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    color = CinemaAmber,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                )
            }
        }

        // Pinch-to-Zoom Feedback Badge
        AnimatedVisibility(
            visible = isZoomHudVisible && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            ZoomHudBadge(zoomScale = zoomScale)
        }

        // Vertical 0–200% Sound Booster HUD (left side so right thumb doesn't occlude it)
        AnimatedVisibility(
            visible = isVolumeHudVisible && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(safeInsets)
                .padding(start = tvOverscanHorizontal)
        ) {
            VerticalVolumeBoostHud(volumePercent = playbackState.volumePercent)
        }

        // Vertical Brightness Swipe HUD (right side so left thumb doesn't occlude it)
        AnimatedVisibility(
            visible = isBrightnessHudVisible && !showBrightnessPanel && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(safeInsets)
                .padding(end = tvOverscanHorizontal)
        ) {
            BrightnessHudOverlay(
                brightnessPercent = brightnessPercent,
                maxPercent = if (isTv) {
                    PlayerGestureMath.TV_MAX_BRIGHTNESS_PERCENT
                } else {
                    PlayerGestureMath.MAX_BRIGHTNESS_PERCENT
                }
            )
        }

        // Hold-to-2x Fast Forward Pill
        AnimatedVisibility(
            visible = playbackState.temporarySpeedActive && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(top = 18.dp)
        ) {
            Hold2xSpeedPill()
        }

        // Dual Subtitles Synchronized Secondary Top Overlay
        val secondaryCue = secondarySubtitleState.currentText
        if (!secondaryCue.isNullOrBlank() && !isInPipMode) {
            DualSubtitleOverlay(
                text = secondaryCue,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                    .padding(top = if (controlsVisible && !isLocked) { if (isTv) 66.dp else 108.dp } else 18.dp)
            )
        }

        // EPG Channel Zap Banner
        if (channelZapBanner != null && !isInPipMode) {
            ChannelZapEpgBanner(
                banner = channelZapBanner,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                    .padding(end = tvOverscanHorizontal, top = if (isTv) 66.dp else 108.dp)
            )
        }

        // Skip Intro (+85s) Floating Pill during intro window (5s..300s on non-live videos)
        if (!isInPipMode && !skipIntroDismissedForUri && upNextCountdown == null && playbackState.errorMessage == null) {
            val liveProgress by progressStateFlow.collectAsStateWithLifecycle()
            val showSkipIntro = !liveProgress.isLiveStream &&
                liveProgress.durationMs > 300_000L &&
                liveProgress.currentPositionMs in 5_000L..300_000L
            AnimatedVisibility(
                visible = showSkipIntro,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(safeInsets)
                    .padding(
                        end = tvOverscanHorizontal,
                        bottom = if (controlsVisible && !isLocked) 118.dp else 28.dp
                    )
            ) {
                SkipIntroButton(
                    onClick = {
                        skipIntroDismissedForUri = true
                        onSkipIntro()
                        seekBadgeText = "+85s"
                        interactionNonce++
                    }
                )
            }
        }

        // Up-Next Episode 10s Countdown Card
        if (upNextCountdown != null && !isInPipMode && playbackState.errorMessage == null) {
            UpNextCountdownCard(
                state = upNextCountdown,
                onPlayNow = onPlayUpNextNow,
                onCancel = onDismissUpNext,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(safeInsets)
                    .padding(
                        end = tvOverscanHorizontal,
                        bottom = if (controlsVisible && !isLocked) 118.dp else 28.dp
                    )
            )
        }

        // Error Recovery Overlay
        if (playbackState.errorMessage != null) {
            PlayerErrorCard(
                message = playbackState.errorMessage,
                currentFormat = session?.format ?: StreamFormat.AUTO,
                isDecoderError = playbackState.isDecoderError,
                effectiveDecoderMode = playbackState.effectiveDecoderMode,
                onRetry = { onRetryFormat(session?.format ?: StreamFormat.AUTO) },
                onSwitchDecoderMode = { mode ->
                    onRetryWithDecoderMode(mode)
                    interactionNonce++
                },
                onSkipBadSegment = {
                    onSkipBadSegment()
                    interactionNonce++
                },
                onForceHls = { onRetryFormat(StreamFormat.HLS) },
                onBack = handleExit,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }

        // Controls Overlay (lightweight modular sub-composables; timeline ticks are isolated)
        AnimatedVisibility(
            visible = controlsVisible && !isInPipMode && playbackState.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            if (isLocked) {
                PlayerLockedOverlay(
                    safeInsets = safeInsets,
                    tvOverscanHorizontal = tvOverscanHorizontal,
                    tvOverscanVertical = tvOverscanVertical,
                    onUnlock = {
                        isLocked = false
                        interactionNonce++
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    PlayerTopBar(
                        title = session?.title ?: "Playing Media",
                        format = session?.format ?: StreamFormat.AUTO,
                        videoHeight = playbackState.videoHeight,
                        hasActiveSubtitles = hasActiveSubtitles,
                        statsForNerdsEnabled = statsForNerdsEnabled,
                        hasSeriesEpisodes = activeSeries != null,
                        episodeCodeBadge = session?.episodeCodeLabel,
                        hasJsonPlaylist = jsonPlaylistItems.isNotEmpty() || jsonPlaylists.isNotEmpty(),
                        hasM3uPlaylist = m3uEntries.isNotEmpty(),
                        isTv = isTv,
                        backgroundAudioEnabled = backgroundAudioEnabled,
                        tvOverscanHorizontal = tvOverscanHorizontal,
                        tvOverscanVertical = tvOverscanVertical,
                        subButtonFocusRequester = subButtonFocusRequester,
                        statsButtonFocusRequester = statsButtonFocusRequester,
                        episodesButtonFocusRequester = episodesButtonFocusRequester,
                        jsonButtonFocusRequester = jsonButtonFocusRequester,
                        m3uButtonFocusRequester = m3uButtonFocusRequester,
                        tracksButtonFocusRequester = tracksButtonFocusRequester,
                        onBack = handleExit,
                        onOpenSubtitles = {
                            returnFocusTarget = subButtonFocusRequester
                            showSubtitleSheet = true
                            interactionNonce++
                        },
                        onToggleStatsForNerds = {
                            onToggleStatsForNerds()
                            interactionNonce++
                        },
                        onOpenSeriesEpisodes = {
                            returnFocusTarget = episodesButtonFocusRequester
                            showSeriesSheet = true
                            interactionNonce++
                        },
                        onOpenJsonCatalog = {
                            returnFocusTarget = jsonButtonFocusRequester
                            showJsonSheet = true
                            interactionNonce++
                        },
                        onOpenM3uQueue = {
                            returnFocusTarget = m3uButtonFocusRequester
                            showM3uSheet = true
                            interactionNonce++
                        },
                        onOpenTracks = {
                            returnFocusTarget = tracksButtonFocusRequester
                            showTracksSheet = true
                            interactionNonce++
                        },
                        onToggleBackgroundAudio = if (!isTv) {
                            {
                                onToggleBackgroundAudio(!backgroundAudioEnabled)
                                interactionNonce++
                            }
                        } else {
                            null
                        },
                        onEnterPip = if (!isTv) onEnterPip else null,
                        onLockControls = {
                            isLocked = true
                            interactionNonce++
                        },
                        isFavorited = isFavorited,
                        onToggleFavorite = onToggleFavorite,
                        canDownload = !isTv && session != null && !session.isLocalFile,
                        activeDownload = activeDownload,
                        onDownloadStream = onDownloadStream,
                        onOpenDownloads = onOpenDownloads,
                        sleepTimerBadge = sleepTimerState.compactBadge(),
                        hasM3uZapping = m3uEntries.size >= 2,
                        onChannelPrev = if (m3uEntries.size >= 2) { { onZapChannel(-1) } } else null,
                        onChannelNext = if (m3uEntries.size >= 2) { { onZapChannel(1) } } else null,
                        brightnessPercent = brightnessPercent,
                        showBrightnessPanel = showBrightnessPanel,
                        brightnessButtonFocusRequester = brightnessButtonFocusRequester,
                        onToggleBrightnessPanel = if (isTv) {
                            {
                                showBrightnessPanel = !showBrightnessPanel
                                if (!showBrightnessPanel) {
                                    returnFocusTarget = brightnessButtonFocusRequester
                                }
                                interactionNonce++
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    if (!playbackState.isBuffering) {
                        PlayerCenterTransport(
                            isPlaying = playbackState.isPlaying,
                            playPauseFocusRequester = playPauseFocusRequester,
                            onRewind10s = {
                                val sec = latestSeekIntervalSec.value
                                onSeekBy(-sec * 1_000L)
                                seekBadgeText = "-${sec}s"
                                interactionNonce++
                            },
                            onTogglePlayPause = {
                                onTogglePlayPause()
                                interactionNonce++
                            },
                            onForward10s = {
                                val sec = latestSeekIntervalSec.value
                                onSeekBy(sec * 1_000L)
                                seekBadgeText = "+${sec}s"
                                interactionNonce++
                            },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    PlayerBottomBar(
                        progressStateFlow = progressStateFlow,
                        volumePercent = playbackState.volumePercent,
                        playbackSpeed = playbackState.playbackSpeed,
                        aspectRatioMode = aspectRatioMode,
                        hasNextEpisode = hasNextEpisode,
                        isTv = isTv,
                        showSpeedMenu = showSpeedMenu,
                        volumeButtonFocusRequester = volumeButtonFocusRequester,
                        speedButtonFocusRequester = speedButtonFocusRequester,
                        seekBarFocusRequester = seekBarFocusRequester,
                        onPlayNextEpisode = {
                            onPlayNextEpisode()
                            interactionNonce++
                        },
                        onOpenVolumeBoost = {
                            returnFocusTarget = volumeButtonFocusRequester
                            showVolumeBoostDialog = true
                            interactionNonce++
                        },
                        onOpenSpeedMenu = {
                            returnFocusTarget = speedButtonFocusRequester
                            showSpeedMenu = true
                            interactionNonce++
                        },
                        onDismissSpeedMenu = { showSpeedMenu = false },
                        onSelectSpeed = { speed ->
                            onSetSpeed(speed)
                            showSpeedMenu = false
                            interactionNonce++
                        },
                        onCycleAspectRatio = {
                            onCycleAspectRatio()
                            interactionNonce++
                        },
                        onToggleOrientation = {
                            isLandscape = !isLandscape
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            interactionNonce++
                        },
                        onSeekCommit = onSeekTo,
                        onScrubbingStateChanged = { scrubbing -> isScrubbing = scrubbing },
                        onInteraction = { interactionNonce++ },
                        chapters = chapters,
                        abLoop = abLoopState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        // Android TV Inline Non-Dimming Brightness Control Popover (below top bar)
        AnimatedVisibility(
            visible = isTv && showBrightnessPanel && !isInPipMode && playbackState.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(top = 74.dp, end = tvOverscanHorizontal)
        ) {
            TvBrightnessControlPanel(
                brightnessPercent = brightnessPercent,
                onSetBrightnessPercent = { targetPct ->
                    val clamped = PlayerGestureMath.clampTvBrightnessPercent(targetPct)
                    brightnessPercent = clamped
                    PlayerGestureMath.applyTvBrightnessPercent(activity, clamped)
                    interactionNonce++
                },
                onDismiss = {
                    showBrightnessPanel = false
                    returnFocusTarget = brightnessButtonFocusRequester
                    interactionNonce++
                }
            )
        }

        // Player Snackbar Host
        if (!isInPipMode) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 72.dp)
            )
        }
    }

    if (showSubtitleSheet) {
        SubtitleSheet(
            subtitleTracks = playbackState.subtitleTracks,
            subtitlesDisabled = playbackState.subtitlesDisabled,
            hasExternalSubtitles = session?.externalSubtitles?.isNotEmpty() == true,
            subtitleOffsetMs = session?.subtitleOffsetMs ?: 0L,
            appearance = subtitleAppearance,
            onDismiss = { showSubtitleSheet = false },
            onPickSubtitleFile = {
                pickingSecondarySub = false
                subtitleFilePicker.pickSubtitle()
            },
            onLoadSubtitleUrl = onLoadSubtitleUrl,
            onSelectTrack = onSelectSubtitleTrack,
            onDisableSubtitles = onDisableSubtitles,
            onAdjustOffset = onAdjustSubtitleOffset,
            onSetOffset = onSetSubtitleOffset ?: { target ->
                val current = session?.subtitleOffsetMs ?: 0L
                onAdjustSubtitleOffset(target - current)
            },
            onResetOffset = onResetSubtitleOffset,
            onUpdateTextSize = onUpdateSubtitleSize,
            onUpdateColor = onUpdateSubtitleColor,
            onUpdateBg = onUpdateSubtitleBg,
            secondarySubtitleState = secondarySubtitleState,
            onPickSecondarySubtitleFile = {
                pickingSecondarySub = true
                subtitleFilePicker.pickSubtitle()
            },
            onLoadSecondarySubtitleUrl = onLoadSecondarySubtitleUrl,
            onClearSecondarySubtitle = onClearSecondarySubtitle
        )
    }

    if (showTracksSheet) {
        val sheetStats by networkStatsFlow.collectAsStateWithLifecycle()
        val liveCacheUsed = diskCacheUsedBytes.takeIf { it > 0L }
            ?: sheetStats.diskCacheUsedBytes.takeIf { it > 0L }
            ?: MediaEdgeCache.usedBytes()
        val liveCacheMax = diskCacheMaxBytes.takeIf { it > 0L }
            ?: sheetStats.diskCacheMaxBytes.takeIf { it > 0L }
            ?: MediaEdgeCache.maxBytes()

        TracksSheet(
            videoTracks = playbackState.videoTracks,
            audioTracks = playbackState.audioTracks,
            volumePercent = playbackState.volumePercent,
            onSetVolumePercent = { pct ->
                onSetVolumePercent(pct)
                volumeHudNonce++
            },
            audioDelayMs = playbackState.audioDelayMs,
            onAdjustAudioDelay = { delta ->
                onAdjustAudioDelay(delta)
                interactionNonce++
            },
            onSetAudioDelay = { targetMs ->
                if (onSetAudioDelay != null) {
                    onSetAudioDelay(targetMs)
                } else {
                    onAdjustAudioDelay(targetMs - playbackState.audioDelayMs)
                }
                interactionNonce++
            },
            onResetAudioDelay = {
                onResetAudioDelay()
                interactionNonce++
            },
            isTv = isTv,
            backgroundAudioEnabled = backgroundAudioEnabled,
            onToggleBackgroundAudio = { enabled ->
                onToggleBackgroundAudio(enabled)
                interactionNonce++
            },
            statsForNerdsEnabled = statsForNerdsEnabled,
            onToggleStatsForNerds = {
                onToggleStatsForNerds()
                interactionNonce++
            },
            activeCdnIndex = playbackState.activeCdnIndex,
            totalCdnNodes = playbackState.totalCdnNodes,
            activeCdnHost = playbackState.activeCdnHost,
            onSwitchCdnMirror = {
                onSwitchCdnMirror()
                interactionNonce++
            },
            diskCacheUsedBytes = liveCacheUsed,
            diskCacheMaxBytes = liveCacheMax,
            onClearEdgeCache = {
                onClearEdgeCache()
                interactionNonce++
            },
            preferredDecoderMode = playbackState.preferredDecoderMode,
            effectiveDecoderMode = playbackState.effectiveDecoderMode,
            activeVideoDecoderName = playbackState.activeVideoDecoderName,
            onSelectDecoderMode = { mode ->
                onSelectDecoderMode(mode)
                interactionNonce++
            },
            preferredTransportMode = playbackState.preferredTransportMode,
            activeTransportLabel = sheetStats.networkTransportLabel,
            onSelectTransportMode = { mode ->
                onSelectTransportMode(mode)
                interactionNonce++
            },
            networkPrivacyBadge = networkPrivacyBadge,
            isPrivacyActive = isPrivacyActive,
            onOpenNetworkPrivacy = onOpenNetworkPrivacy?.let { openPrivacy ->
                {
                    showTracksSheet = false
                    openPrivacy()
                }
            },
            nightModeAudioEnabled = nightModeAudioEnabled,
            onToggleNightModeAudio = onToggleNightModeAudio,
            sleepTimerState = sleepTimerState,
            onSelectSleepTimer = onSelectSleepTimer,
            seekIntervalSeconds = seekIntervalSeconds,
            onSelectSeekInterval = onSelectSeekInterval,
            preferredAudioLanguage = preferredAudioLanguage,
            onSelectPreferredAudioLanguage = onSelectPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            onSelectPreferredSubtitleLanguage = onSelectPreferredSubtitleLanguage,
            abLoopState = abLoopState,
            onSetAbLoopPointA = onSetAbLoopPointA,
            onSetAbLoopPointB = onSetAbLoopPointB,
            onClearAbLoop = onClearAbLoop,
            chapters = chapters,
            onJumpToChapter = { ch ->
                showTracksSheet = false
                onJumpToChapter(ch)
            },
            canDownload = !isTv && session != null && !session.isLocalFile,
            activeDownload = activeDownload,
            activeDownloadSpeedBps = activeDownloadSpeedBps,
            onDownloadStream = onDownloadStream,
            onPauseDownload = onPauseDownload,
            onResumeDownload = onResumeDownload,
            onCancelDownload = onCancelDownload,
            onOpenDownloads = onOpenDownloads?.let { openDownloads ->
                {
                    showTracksSheet = false
                    openDownloads()
                }
            },
            onDismiss = { showTracksSheet = false },
            onSelectVideoTrack = onSelectVideoTrack,
            onSelectAudioTrack = onSelectAudioTrack
        )
    }

    if (showVolumeBoostDialog) {
        VolumeBoostDialog(
            volumePercent = playbackState.volumePercent,
            onSetVolumePercent = { pct ->
                onSetVolumePercent(pct)
                volumeHudNonce++
            },
            onDismiss = { showVolumeBoostDialog = false }
        )
    }

    if (showM3uSheet && m3uEntries.isNotEmpty()) {
        M3uPlaylistSheet(
            entries = m3uEntries,
            activeEntryId = activeM3uEntryId,
            onDismiss = { showM3uSheet = false },
            onSelectEntry = onSelectM3uEntry,
            epgState = epgState,
            onLoadEpgUrl = onLoadEpgUrl,
            resolveChannelEpg = resolveChannelEpg,
            favoriteMediaKeys = favoriteMediaKeys,
            onToggleFavoriteEntry = onToggleFavoriteM3uEntry
        )
    }

    if (showJsonSheet && (jsonPlaylistItems.isNotEmpty() || jsonPlaylists.isNotEmpty())) {
        JsonPlaylistSheet(
            sourceLabel = jsonPlaylistLabel,
            items = jsonPlaylistItems,
            activeItemId = activeJsonItemId,
            isEnrichingImdb = isEnrichingImdb,
            canRefreshUrl = canRefreshJson,
            onRefresh = onRefreshJsonPlaylist,
            onDismiss = { showJsonSheet = false },
            onSelectItem = onSelectJsonItem,
            playlists = jsonPlaylists,
            selectedPlaylistId = selectedJsonPlaylistId,
            onSelectPlaylist = onSelectJsonPlaylist,
            onRemovePlaylist = onRemoveJsonPlaylist,
            onAddPlaylistUrl = onAddJsonPlaylistUrl
        )
    }

    if (showSeriesSheet && activeSeries != null) {
        SeriesDetailSheet(
            series = activeSeries,
            activeEpisodeId = activeEpisodeId,
            episodeProgressMap = episodeProgressMap,
            onDismiss = { showSeriesSheet = false },
            onSelectEpisode = onSelectSeriesEpisode
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
