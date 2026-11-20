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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.R
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
import com.example.player.PlayerPlaybackState
import com.example.ui.components.FormatBadge
import com.example.ui.components.formatDuration
import com.example.ui.components.rememberIsAndroidTv
import com.example.ui.components.rememberLocalFilePicker
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaAmberBright
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SignalRose
import com.example.ui.theme.SubtitleEmerald
import com.example.viewmodel.ActiveMediaSession
import com.example.viewmodel.AiSubtitleUiState
import kotlinx.coroutines.delay
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

    // Scrubber active state (pauses auto-hide while scrubbing)
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
        onPauseAndSave()
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

    // Request focus cleanly for TV remotes AND TV boxes / keyboards regardless of touchscreen flags
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
        if (!showSubtitleSheet && !showTracksSheet && !showM3uSheet && !showJsonSheet && !showSpeedMenu && !showVolumeBoostDialog && playbackState.errorMessage == null) {
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

    // Toast feedback inside player
    LaunchedEffect(toastMessage) {
        if (!toastMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(toastMessage)
            onToastShown()
        }
    }

    // Clear seek indicator
    LaunchedEffect(seekBadgeText) {
        if (seekBadgeText != null) {
            delay(750L)
            seekBadgeText = null
        }
    }

    // Auto-hide vertical volume / boost HUD
    LaunchedEffect(volumeHudNonce) {
        if (volumeHudNonce > 0) {
            isVolumeHudVisible = true
            delay(1150L)
            isVolumeHudVisible = false
        }
    }

    // Auto-hide controls while playing
    LaunchedEffect(controlsVisible, playbackState.isPlaying, isScrubbing, showSubtitleSheet, showTracksSheet, showM3uSheet, showJsonSheet, showSpeedMenu, showVolumeBoostDialog, interactionNonce) {
        if (controlsVisible && playbackState.isPlaying && !isScrubbing && !showSubtitleSheet && !showTracksSheet && !showM3uSheet && !showJsonSheet && !showSpeedMenu && !showVolumeBoostDialog) {
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

                // Always keep controls timer fresh and mark D-Pad/keyboard mode on any key press
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
                        } else {
                            false
                        }
                    }
                    Key.DirectionRight -> {
                        if (!controlsVisible && !isLocked) {
                            onSeekBy(10_000L)
                            seekBadgeText = "+10s"
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionDown -> {
                        if (!controlsVisible) {
                            focusSeekBarOnReveal = true
                            controlsVisible = true
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp -> {
                        if (!controlsVisible) {
                            focusSeekBarOnReveal = false
                            controlsVisible = true
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        // Video Surface + Native SubtitleView via Media3 PlayerView
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
            }
        )

        // Touch Tap, Double-Tap Seek, and VLC/MX Right-Side Vertical 0-200% Sound Swipe
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

        // Live AI English Subtitle Overlay (synchronized with playback & user subtitle appearance)
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

        // VLC / MX Player Vertical 0–200% Sound Booster HUD
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

        // Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible && playbackState.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            if (isLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(safeInsets)
                        .padding(horizontal = tvOverscanHorizontal, vertical = tvOverscanVertical)
                ) {
                    Surface(
                        onClick = {
                            isLocked = false
                            interactionNonce++
                        },
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .minimumInteractiveComponentSize()
                            .tvFocusHighlight(shape = RoundedCornerShape(24.dp))
                            .testTag("player_unlock_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LockOpen,
                                contentDescription = stringResource(R.string.cd_unlock_controls),
                                tint = CinemaAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Unlock",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top Gradient & Header Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.84f), Color.Transparent)
                                )
                            )
                            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                            .padding(horizontal = tvOverscanHorizontal, vertical = tvOverscanVertical)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = handleExit,
                                modifier = Modifier
                                    .tvFocusHighlight(shape = CircleShape)
                                    .testTag("player_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back),
                                    tint = Color.White
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = session?.title ?: "Playing Media",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FormatBadge(format = session?.format ?: StreamFormat.AUTO)
                                    if (playbackState.videoHeight > 0) {
                                        Text(
                                            text = "${playbackState.videoHeight}p",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.78f)
                                        )
                                    }
                                    if (hasActiveSubtitles) {
                                        Text(
                                            text = "· CC ON",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SubtitleEmerald
                                        )
                                    }
                                    if (aiSubtitleState.isAutoMode || aiSubtitleState.isEnabled || aiSubtitleState.isGenerating) {
                                        Text(
                                            text = if (aiSubtitleState.isGenerating) "· AI EN…" else "· AI EN",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CinemaAmber
                                        )
                                    }
                                }
                            }

                            // Subtitle Sheet Button (highlights when native CC or AI EN is active)
                            val aiSubActive = aiSubtitleState.isAutoMode || aiSubtitleState.isEnabled || aiSubtitleState.isGenerating
                            val subTint = when {
                                aiSubActive -> CinemaAmber
                                hasActiveSubtitles -> SubtitleEmerald
                                else -> Color.White
                            }
                            Surface(
                                onClick = {
                                    returnFocusTarget = subButtonFocusRequester
                                    showSubtitleSheet = true
                                    interactionNonce++
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    aiSubActive -> CinemaAmber.copy(alpha = 0.24f)
                                    hasActiveSubtitles -> SubtitleEmerald.copy(alpha = 0.24f)
                                    else -> Color.White.copy(alpha = 0.14f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        aiSubActive -> CinemaAmber
                                        hasActiveSubtitles -> SubtitleEmerald
                                        else -> Color.White.copy(alpha = 0.22f)
                                    }
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .focusRequester(subButtonFocusRequester)
                                    .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                                    .testTag("player_subtitles_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Subtitles,
                                        contentDescription = stringResource(R.string.cd_subtitles),
                                        tint = subTint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (aiSubActive) "AI Sub" else "Sub",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = subTint
                                    )
                                }
                            }

                            if (jsonPlaylistItems.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        returnFocusTarget = jsonButtonFocusRequester
                                        showJsonSheet = true
                                        interactionNonce++
                                    },
                                    modifier = Modifier
                                        .focusRequester(jsonButtonFocusRequester)
                                        .tvFocusHighlight(shape = CircleShape)
                                        .testTag("player_json_catalog_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VideoLibrary,
                                        contentDescription = stringResource(R.string.cd_json_playlist),
                                        tint = CinemaAmber
                                    )
                                }
                            }

                            if (m3uEntries.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        returnFocusTarget = m3uButtonFocusRequester
                                        showM3uSheet = true
                                        interactionNonce++
                                    },
                                    modifier = Modifier
                                        .focusRequester(m3uButtonFocusRequester)
                                        .tvFocusHighlight(shape = CircleShape)
                                        .testTag("player_m3u_queue_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlaylistPlay,
                                        contentDescription = stringResource(R.string.cd_playlist),
                                        tint = Color.White
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    returnFocusTarget = tracksButtonFocusRequester
                                    showTracksSheet = true
                                    interactionNonce++
                                },
                                modifier = Modifier
                                    .focusRequester(tracksButtonFocusRequester)
                                    .tvFocusHighlight(shape = CircleShape)
                                    .testTag("player_tracks_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = stringResource(R.string.cd_tracks),
                                    tint = Color.White
                                )
                            }

                            if (!isTv) {
                                IconButton(
                                    onClick = {
                                        isLocked = true
                                        interactionNonce++
                                    },
                                    modifier = Modifier
                                        .tvFocusHighlight(shape = CircleShape)
                                        .testTag("player_lock_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = stringResource(R.string.cd_lock_controls),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Center Transport Controls
                    if (!playbackState.isBuffering) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(32.dp)
                        ) {
                            Surface(
                                onClick = {
                                    onSeekBy(-10_000L)
                                    seekBadgeText = "-10s"
                                    interactionNonce++
                                },
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .size(56.dp)
                                    .tvFocusHighlight(shape = CircleShape, focusedScale = 1.1f)
                                    .testTag("player_rewind_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Replay10,
                                        contentDescription = stringResource(R.string.cd_rewind),
                                        tint = Color.White,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    onTogglePlayPause()
                                    interactionNonce++
                                },
                                shape = CircleShape,
                                color = CinemaAmber,
                                modifier = Modifier
                                    .size(74.dp)
                                    .focusRequester(playPauseFocusRequester)
                                    .tvFocusHighlight(
                                        shape = CircleShape,
                                        borderColor = Color.White,
                                        borderWidth = 3.dp,
                                        focusedScale = 1.1f
                                    )
                                    .testTag("player_play_pause_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (playbackState.isPlaying) {
                                            Icons.Rounded.Pause
                                        } else {
                                            Icons.Rounded.PlayArrow
                                        },
                                        contentDescription = stringResource(R.string.cd_play_pause),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    onSeekBy(10_000L)
                                    seekBadgeText = "+10s"
                                    interactionNonce++
                                },
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .size(56.dp)
                                    .tvFocusHighlight(shape = CircleShape, focusedScale = 1.1f)
                                    .testTag("player_forward_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Forward10,
                                        contentDescription = stringResource(R.string.cd_forward),
                                        tint = Color.White,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Scrubber & Utility Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                                )
                            )
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                            .padding(horizontal = if (isTv) 32.dp else 16.dp, vertical = if (isTv) 18.dp else 12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (playbackState.isLiveStream && playbackState.durationMs <= 0L) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = SignalRose.copy(alpha = 0.22f),
                                        border = BorderStroke(1.dp, SignalRose)
                                    ) {
                                        Text(
                                            text = "● LIVE HLS",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SignalRose,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "${formatDuration(playbackState.currentPositionMs)} / ${formatDuration(playbackState.durationMs)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                }

                                // Quick Bottom Controls: Sound Boost (200%), Speed, Aspect Ratio, (Rotate on Mobile)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val volPct = playbackState.volumePercent
                                    val isBoosted = volPct > AudioBoostMath.NORMAL_MAX_PERCENT
                                    val volAccent = when {
                                        volPct >= 160 -> SignalRose
                                        isBoosted -> CinemaAmberBright
                                        else -> CinemaAmber
                                    }
                                    Surface(
                                        onClick = {
                                            returnFocusTarget = volumeButtonFocusRequester
                                            showVolumeBoostDialog = true
                                            interactionNonce++
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isBoosted) volAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.14f),
                                        border = if (isBoosted) BorderStroke(1.dp, volAccent.copy(alpha = 0.65f)) else null,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .focusRequester(volumeButtonFocusRequester)
                                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                            .testTag("player_volume_boost_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = when {
                                                    volPct == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
                                                    isBoosted -> Icons.Rounded.GraphicEq
                                                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                                                },
                                                contentDescription = stringResource(R.string.cd_volume_boost),
                                                tint = volAccent,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = AudioBoostMath.formatShortLabel(volPct),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isBoosted) volAccent else Color.White
                                            )
                                        }
                                    }

                                    Box {
                                        Surface(
                                            onClick = {
                                                returnFocusTarget = speedButtonFocusRequester
                                                showSpeedMenu = true
                                                interactionNonce++
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color.White.copy(alpha = 0.14f),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .focusRequester(speedButtonFocusRequester)
                                                .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                                .testTag("player_speed_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Speed,
                                                    contentDescription = null,
                                                    tint = CinemaAmber,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "${playbackState.playbackSpeed}x",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = showSpeedMenu,
                                            onDismissRequest = { showSpeedMenu = false },
                                            containerColor = ObsidianSurface,
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.5.dp, CinemaAmber.copy(alpha = 0.65f))
                                        ) {
                                            val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                                            val activeSpeedFocusRequester = remember { FocusRequester() }
                                            LaunchedEffect(showSpeedMenu) {
                                                if (showSpeedMenu) {
                                                    delay(80L)
                                                    runCatching { activeSpeedFocusRequester.requestFocus() }
                                                }
                                            }
                                            speedOptions.forEach { speed ->
                                                val isCurrent = speed == playbackState.playbackSpeed
                                                var isRowFocused by remember { mutableStateOf(false) }
                                                DropdownMenuItem(
                                                    modifier = Modifier
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        .then(
                                                            if (isCurrent) {
                                                                Modifier.focusRequester(activeSpeedFocusRequester)
                                                            } else {
                                                                Modifier
                                                            }
                                                        )
                                                        .tvFocusHighlight(
                                                            shape = RoundedCornerShape(10.dp),
                                                            focusedScale = 1.03f,
                                                            onFocusStateChanged = { isRowFocused = it }
                                                        )
                                                        .background(
                                                            color = when {
                                                                isRowFocused -> CinemaAmber.copy(alpha = 0.24f)
                                                                isCurrent -> CinemaAmber.copy(alpha = 0.10f)
                                                                else -> Color.Transparent
                                                            },
                                                            shape = RoundedCornerShape(10.dp)
                                                        )
                                                        .testTag("speed_option_${speed}x"),
                                                    leadingIcon = {
                                                        when {
                                                            isRowFocused -> Icon(
                                                                imageVector = Icons.Rounded.PlayArrow,
                                                                contentDescription = null,
                                                                tint = Color.White,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            isCurrent -> Icon(
                                                                imageVector = Icons.Rounded.Check,
                                                                contentDescription = null,
                                                                tint = CinemaAmber,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            else -> Spacer(Modifier.size(18.dp))
                                                        }
                                                    },
                                                    text = {
                                                        Text(
                                                            text = "${speed}x",
                                                            style = MaterialTheme.typography.titleSmall,
                                                            color = when {
                                                                isRowFocused -> Color.White
                                                                isCurrent -> CinemaAmber
                                                                else -> MaterialTheme.colorScheme.onSurface
                                                            }
                                                        )
                                                    },
                                                    onClick = {
                                                        onSetSpeed(speed)
                                                        showSpeedMenu = false
                                                        interactionNonce++
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Surface(
                                        onClick = {
                                            onCycleAspectRatio()
                                            interactionNonce++
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White.copy(alpha = 0.14f),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                            .testTag("player_aspect_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AspectRatio,
                                                contentDescription = stringResource(R.string.cd_aspect_ratio),
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = aspectRatioMode.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White
                                            )
                                        }
                                    }

                                    if (!isTv) {
                                        IconButton(
                                            onClick = {
                                                isLandscape = !isLandscape
                                                activity?.requestedOrientation = if (isLandscape) {
                                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                } else {
                                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                }
                                                interactionNonce++
                                            },
                                            modifier = Modifier
                                                .tvFocusHighlight(shape = CircleShape)
                                                .testTag("player_rotate_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ScreenRotation,
                                                contentDescription = stringResource(R.string.cd_rotate),
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }

                            if (!playbackState.isLiveStream || playbackState.durationMs > 0L) {
                                SelectableCinemaSeekBar(
                                    currentPositionMs = playbackState.currentPositionMs,
                                    bufferedPositionMs = playbackState.bufferedPositionMs,
                                    durationMs = playbackState.durationMs,
                                    isTv = isTv,
                                    focusRequester = seekBarFocusRequester,
                                    onSeekCommit = onSeekTo,
                                    onScrubbingStateChanged = { scrubbing -> isScrubbing = scrubbing },
                                    onInteraction = { interactionNonce++ }
                                )
                            }
                        }
                    }
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
            onPickSubtitleFile = {
                subtitleFilePicker.pickSubtitle()
            },
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

@Composable
private fun PlayerErrorCard(
    message: String,
    currentFormat: StreamFormat,
    onRetry: () -> Unit,
    onForceHls: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(20.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, SignalRose.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = SignalRose,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "Playback Interrupted",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp)),
                    border = BorderStroke(1.dp, ObsidianOutline)
                ) {
                    Text("Back")
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp), borderColor = Color.White)
                        .testTag("player_error_retry_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
            if (currentFormat != StreamFormat.HLS && currentFormat != StreamFormat.M3U8) {
                OutlinedButton(
                    onClick = onForceHls,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                        .testTag("player_force_hls_button"),
                    border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.6f))
                ) {
                    Text("Try as HLS / M3U8 Stream", color = CinemaAmber)
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
