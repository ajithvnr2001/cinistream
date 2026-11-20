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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.ai.AiModelTier
import com.example.ai.RateLimitSnapshot
import com.example.model.AspectRatioMode
import com.example.model.JsonPlaylistItem
import com.example.model.M3uEntry
import com.example.model.StreamFormat
import com.example.model.SubtitleAppearance
import com.example.model.SubtitleBgPreset
import com.example.model.SubtitleColorPreset
import com.example.model.SubtitleTextSize
import com.example.model.TrackOption
import com.example.player.AudioBoostMath
import com.example.player.PlaybackProgressState
import com.example.player.PlayerPlaybackState
import com.example.ui.components.rememberIsAndroidTv
import com.example.ui.components.rememberLocalFilePicker
import com.example.ui.theme.CinemaAmber
import com.example.viewmodel.ActiveMediaSession
import com.example.viewmodel.AiSubtitleUiState
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
    subtitleAppearance: SubtitleAppearance,
    aspectRatioMode: AspectRatioMode,
    aiSubtitleState: AiSubtitleUiState,
    rateLimitSnapshot: RateLimitSnapshot,
    m3uEntries: List<M3uEntry>,
    activeM3uEntryId: String?,
    jsonPlaylistLabel: String? = null,
    jsonPlaylistItems: List<JsonPlaylistItem> = emptyList(),
    activeJsonItemId: String? = null,
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
    onCycleAspectRatio: () -> Unit,
    onLoadSubtitleFile: (Uri) -> Unit,
    onLoadSubtitleUrl: (String) -> Unit,
    onSelectSubtitleTrack: (TrackOption) -> Unit,
    onDisableSubtitles: () -> Unit,
    onAdjustSubtitleOffset: (Long) -> Unit,
    onResetSubtitleOffset: () -> Unit,
    onUpdateSubtitleSize: (SubtitleTextSize) -> Unit,
    onUpdateSubtitleColor: (SubtitleColorPreset) -> Unit,
    onUpdateSubtitleBg: (SubtitleBgPreset) -> Unit,
    onSelectAiTier: (AiModelTier) -> Unit,
    onGenerateAiClipNow: () -> Unit,
    onToggleAutoAi: () -> Unit,
    onApplyAiAsSrt: () -> Unit,
    onClearAiSubtitles: () -> Unit,
    onSelectVideoTrack: (TrackOption?) -> Unit,
    onSelectAudioTrack: (TrackOption) -> Unit,
    onRetryFormat: (StreamFormat) -> Unit,
    onSelectM3uEntry: (M3uEntry) -> Unit,
    onSelectJsonItem: (JsonPlaylistItem) -> Unit = {},
    onRefreshJsonPlaylist: () -> Unit = {},
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
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showVolumeBoostDialog by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }

    // Seek & Volume visual feedback HUD state
    var seekBadgeText by remember { mutableStateOf<String?>(null) }
    var isVolumeHudVisible by remember { mutableStateOf(false) }
    var volumeHudNonce by remember { mutableIntStateOf(0) }
    val currentVolumeState = rememberUpdatedState(playbackState.volumePercent)
    val latestOnSetVolumePercent = rememberUpdatedState(onSetVolumePercent)
    var isScrubbing by remember { mutableStateOf(false) }

    val subtitleFilePicker = rememberLocalFilePicker(
        onSubtitlePicked = { uri ->
            showSubtitleSheet = false
            onLoadSubtitleFile(uri)
        },
        onPickerUnavailable = {
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

    // On TV / D-Pad, pressing Back while controls are visible first dismisses controls cleanly
    BackHandler {
        if ((isTv || lastInputWasKey) && controlsVisible && playbackState.errorMessage == null && !isLocked) {
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
        showSpeedMenu,
        showVolumeBoostDialog
    ) {
        val noModalOpen = !showSubtitleSheet && !showTracksSheet && !showM3uSheet &&
            !showJsonSheet && !showSpeedMenu && !showVolumeBoostDialog && playbackState.errorMessage == null
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

    LaunchedEffect(
        controlsVisible,
        playbackState.isPlaying,
        isScrubbing,
        showSubtitleSheet,
        showTracksSheet,
        showM3uSheet,
        showJsonSheet,
        showSpeedMenu,
        showVolumeBoostDialog,
        interactionNonce
    ) {
        val canAutoHide = controlsVisible && playbackState.isPlaying && !isScrubbing &&
            !showSubtitleSheet && !showTracksSheet && !showM3uSheet && !showJsonSheet &&
            !showSpeedMenu && !showVolumeBoostDialog
        if (canAutoHide) {
            delay(if (isTv) 5000L else 4000L)
            controlsVisible = false
        }
    }

    // Keep screen on and manage immersive system bars cleanly
    DisposableEffect(activity, view) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isTv) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(controlsVisible, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Pause playback when app moves to background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                onPauseAndSave()
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
    val aiSubActive = aiSubtitleState.isAutoMode || aiSubtitleState.isEnabled || aiSubtitleState.isGenerating

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable(enabled = !controlsVisible)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (showSubtitleSheet || showTracksSheet || showM3uSheet || showJsonSheet || showSpeedMenu || showVolumeBoostDialog) {
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
                        onSeekBy(-10_000L)
                        seekBadgeText = "-10s"
                        true
                    }
                    Key.MediaFastForward, Key.MediaSkipForward -> {
                        onSeekBy(10_000L)
                        seekBadgeText = "+10s"
                        true
                    }
                    Key.Captions -> {
                        showSubtitleSheet = true
                        true
                    }
                    Key.DirectionLeft -> {
                        if (!controlsVisible && !isLocked) {
                            onSeekBy(-10_000L)
                            seekBadgeText = "-10s"
                            true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (!controlsVisible && !isLocked) {
                            onSeekBy(10_000L)
                            seekBadgeText = "+10s"
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
        // Video Surface + Native SubtitleView via Media3 PlayerView (detaches player cleanly onRelease)
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
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

        // Touch Tap, Double-Tap Seek, and Right-Side Vertical 0-200% Sound Swipe
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    detectTapGestures(
                        onTap = {
                            lastInputWasKey = false
                            controlsVisible = !controlsVisible
                            interactionNonce++
                        },
                        onDoubleTap = { offset ->
                            if (isLocked) return@detectTapGestures
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            when {
                                offset.x < width * 0.35f -> {
                                    onSeekBy(-10_000L)
                                    seekBadgeText = "-10s"
                                    interactionNonce++
                                }
                                offset.x > width * 0.65f -> {
                                    onSeekBy(10_000L)
                                    seekBadgeText = "+10s"
                                    interactionNonce++
                                }
                                else -> {
                                    onTogglePlayPause()
                                    interactionNonce++
                                }
                            }
                        }
                    )
                }
                .pointerInput(isLocked) {
                    var activeOnRightHalf = false
                    var dragPercentFloat = 100f
                    detectVerticalDragGestures(
                        onDragStart = { startOffset ->
                            activeOnRightHalf = !isLocked && startOffset.x >= size.width * 0.50f
                            dragPercentFloat = currentVolumeState.value.toFloat()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            if (!activeOnRightHalf) return@detectVerticalDragGestures
                            change.consume()
                            val travelPx = (size.height * 0.55f).coerceAtLeast(180f)
                            val delta = (-dragAmount / travelPx) * AudioBoostMath.MAX_PERCENT.toFloat()
                            dragPercentFloat = (dragPercentFloat + delta)
                                .coerceIn(
                                    AudioBoostMath.MIN_PERCENT.toFloat(),
                                    AudioBoostMath.MAX_PERCENT.toFloat()
                                )
                            latestOnSetVolumePercent.value(dragPercentFloat.roundToInt())
                            volumeHudNonce++
                            interactionNonce++
                        }
                    )
                }
        )

        // Live AI English Subtitle Overlay
        val liveAiCaption = aiSubtitleState.activeOverlayText
        if (aiSubtitleState.isEnabled && !liveAiCaption.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        horizontal = if (isTv) 48.dp else 20.dp,
                        vertical = when {
                            controlsVisible && !isLocked -> if (isTv) 152.dp else 132.dp
                            else -> if (isTv) 44.dp else 28.dp
                        }
                    )
                    .background(
                        color = Color(subtitleAppearance.bgPreset.argb),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("ai_live_subtitle_overlay")
            ) {
                Text(
                    text = liveAiCaption,
                    color = Color(subtitleAppearance.colorPreset.argb),
                    fontSize = (subtitleAppearance.textSize.sp * (if (isTv) 1.35f else 1.0f)).sp,
                    textAlign = TextAlign.Center,
                    lineHeight = (subtitleAppearance.textSize.sp * (if (isTv) 1.65f else 1.3f)).sp
                )
            }
        }

        // Buffering Indicator
        if (playbackState.isBuffering && playbackState.errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp),
                color = CinemaAmber,
                strokeWidth = 3.5.dp
            )
        }

        // Seek Feedback Badge
        AnimatedVisibility(
            visible = seekBadgeText != null,
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

        // Vertical 0–200% Sound Booster HUD
        AnimatedVisibility(
            visible = isVolumeHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(safeInsets)
                .padding(start = tvOverscanHorizontal)
        ) {
            VerticalVolumeBoostHud(volumePercent = playbackState.volumePercent)
        }

        // Error Recovery Overlay
        if (playbackState.errorMessage != null) {
            PlayerErrorCard(
                message = playbackState.errorMessage,
                currentFormat = session?.format ?: StreamFormat.AUTO,
                onRetry = { onRetryFormat(session?.format ?: StreamFormat.AUTO) },
                onForceHls = { onRetryFormat(StreamFormat.HLS) },
                onBack = handleExit,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }

        // Controls Overlay (lightweight modular sub-composables; timeline ticks are isolated)
        AnimatedVisibility(
            visible = controlsVisible && playbackState.errorMessage == null,
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
                        aiSubActive = aiSubActive,
                        aiSubGenerating = aiSubtitleState.isGenerating,
                        hasJsonPlaylist = jsonPlaylistItems.isNotEmpty(),
                        hasM3uPlaylist = m3uEntries.isNotEmpty(),
                        isTv = isTv,
                        tvOverscanHorizontal = tvOverscanHorizontal,
                        tvOverscanVertical = tvOverscanVertical,
                        subButtonFocusRequester = subButtonFocusRequester,
                        jsonButtonFocusRequester = jsonButtonFocusRequester,
                        m3uButtonFocusRequester = m3uButtonFocusRequester,
                        tracksButtonFocusRequester = tracksButtonFocusRequester,
                        onBack = handleExit,
                        onOpenSubtitles = {
                            returnFocusTarget = subButtonFocusRequester
                            showSubtitleSheet = true
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
                        onLockControls = {
                            isLocked = true
                            interactionNonce++
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    if (!playbackState.isBuffering) {
                        PlayerCenterTransport(
                            isPlaying = playbackState.isPlaying,
                            playPauseFocusRequester = playPauseFocusRequester,
                            onRewind10s = {
                                onSeekBy(-10_000L)
                                seekBadgeText = "-10s"
                                interactionNonce++
                            },
                            onTogglePlayPause = {
                                onTogglePlayPause()
                                interactionNonce++
                            },
                            onForward10s = {
                                onSeekBy(10_000L)
                                seekBadgeText = "+10s"
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
                        isTv = isTv,
                        showSpeedMenu = showSpeedMenu,
                        volumeButtonFocusRequester = volumeButtonFocusRequester,
                        speedButtonFocusRequester = speedButtonFocusRequester,
                        seekBarFocusRequester = seekBarFocusRequester,
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
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        // Player Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 72.dp)
        )
    }

    if (showSubtitleSheet) {
        SubtitleSheet(
            subtitleTracks = playbackState.subtitleTracks,
            subtitlesDisabled = playbackState.subtitlesDisabled,
            hasExternalSubtitles = session?.externalSubtitles?.isNotEmpty() == true,
            subtitleOffsetMs = session?.subtitleOffsetMs ?: 0L,
            appearance = subtitleAppearance,
            aiSubtitleState = aiSubtitleState,
            rateLimitSnapshot = rateLimitSnapshot,
            onDismiss = { showSubtitleSheet = false },
            onPickSubtitleFile = { subtitleFilePicker.pickSubtitle() },
            onLoadSubtitleUrl = onLoadSubtitleUrl,
            onSelectTrack = onSelectSubtitleTrack,
            onDisableSubtitles = onDisableSubtitles,
            onAdjustOffset = onAdjustSubtitleOffset,
            onResetOffset = onResetSubtitleOffset,
            onUpdateTextSize = onUpdateSubtitleSize,
            onUpdateColor = onUpdateSubtitleColor,
            onUpdateBg = onUpdateSubtitleBg,
            onSelectAiTier = onSelectAiTier,
            onGenerateAiClipNow = onGenerateAiClipNow,
            onToggleAutoAi = onToggleAutoAi,
            onApplyAiAsSrt = onApplyAiAsSrt,
            onClearAiSubtitles = onClearAiSubtitles
        )
    }

    if (showTracksSheet) {
        TracksSheet(
            videoTracks = playbackState.videoTracks,
            audioTracks = playbackState.audioTracks,
            volumePercent = playbackState.volumePercent,
            onSetVolumePercent = { pct ->
                onSetVolumePercent(pct)
                volumeHudNonce++
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
            onSelectEntry = onSelectM3uEntry
        )
    }

    if (showJsonSheet && jsonPlaylistItems.isNotEmpty()) {
        JsonPlaylistSheet(
            sourceLabel = jsonPlaylistLabel,
            items = jsonPlaylistItems,
            activeItemId = activeJsonItemId,
            isEnrichingImdb = isEnrichingImdb,
            canRefreshUrl = canRefreshJson,
            onRefresh = onRefreshJsonPlaylist,
            onDismiss = { showJsonSheet = false },
            onSelectItem = onSelectJsonItem
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
