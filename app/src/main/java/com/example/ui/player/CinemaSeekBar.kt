package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.model.AbLoopState
import com.example.model.ChapterMarker
import com.example.player.SeekBarScrubEngine
import com.example.ui.components.formatDuration
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaAmberBright
import com.example.ui.theme.StreamCyan
import kotlinx.coroutines.delay

/**
 * Selectable, D-Pad & Touch friendly Cinema Play Bar.
 *
 * - Focusable via Android TV D-Pad (with expanded track, glowing amber border & floating minute preview).
 * - Pressing D-Pad Left / Right while focused rapidly scrubs across minutes (±1m -> ±2m -> ±5m -> ±10m)
 *   and commits cleanly on OK/Center or automatically after 350ms without stalling decoding.
 * - Flanking -1m / +1m buttons allow instant single-click minute jumps separate from the ±10s controls.
 * - Touch tap anywhere jumps immediately to that timestamp; drag scrubs smoothly with live preview.
 */
@Composable
fun SelectableCinemaSeekBar(
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    isTv: Boolean,
    focusRequester: FocusRequester,
    onSeekCommit: (Long) -> Unit,
    onScrubbingStateChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    chapters: List<ChapterMarker> = emptyList(),
    abLoop: AbLoopState = AbLoopState(),
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var isTouchScrubbing by remember { mutableStateOf(false) }
    var isKeyScrubbing by remember { mutableStateOf(false) }
    var previewPositionMs by remember { mutableLongStateOf(currentPositionMs) }
    var activeStepBadge by remember { mutableStateOf<String?>(null) }
    var consecutiveSteps by remember { mutableIntStateOf(0) }
    var lastStepDirection by remember { mutableIntStateOf(0) }
    var lastStepTimestamp by remember { mutableLongStateOf(0L) }
    var keyScrubNonce by remember { mutableIntStateOf(0) }
    var barWidthPx by remember { mutableIntStateOf(1) }

    val isScrubbing = isTouchScrubbing || isKeyScrubbing

    LaunchedEffect(isScrubbing) {
        onScrubbingStateChanged(isScrubbing)
    }

    // Keep preview synced when not actively scrubbing
    LaunchedEffect(currentPositionMs, isScrubbing) {
        if (!isScrubbing) {
            previewPositionMs = currentPositionMs
        }
    }

    // Auto-commit fast D-Pad minute scrub 350ms after the last Left/Right press
    LaunchedEffect(keyScrubNonce, isKeyScrubbing) {
        if (isKeyScrubbing) {
            delay(350L)
            onSeekCommit(previewPositionMs)
            isKeyScrubbing = false
            consecutiveSteps = 0
            lastStepDirection = 0
            delay(450L)
            if (!isKeyScrubbing) {
                activeStepBadge = null
            }
        }
    }

    val displayedPositionMs = if (isScrubbing) previewPositionMs else currentPositionMs
    val progressFraction = SeekBarScrubEngine.positionToFraction(displayedPositionMs, durationMs)
    val bufferedFraction = SeekBarScrubEngine.positionToFraction(bufferedPositionMs, durationMs)

    val trackHeight by animateDpAsState(
        targetValue = if (isFocused || isScrubbing) 8.dp else 4.dp,
        animationSpec = tween(140),
        label = "seekbar_track_height"
    )
    val thumbRadius by animateDpAsState(
        targetValue = if (isFocused || isScrubbing) 10.dp else 6.dp,
        animationSpec = tween(140),
        label = "seekbar_thumb_radius"
    )

    fun performKeyMinuteScrub(direction: Int) {
        val now = System.currentTimeMillis()
        val sameDirection = direction == lastStepDirection && (now - lastStepTimestamp) < 900L
        consecutiveSteps = if (sameDirection) consecutiveSteps + 1 else 1
        lastStepDirection = direction
        lastStepTimestamp = now

        val baseMs = if (isKeyScrubbing) previewPositionMs else currentPositionMs
        val result = SeekBarScrubEngine.stepPosition(
            currentMs = baseMs,
            durationMs = durationMs,
            direction = direction,
            consecutiveSteps = consecutiveSteps
        )
        previewPositionMs = result.targetPositionMs
        activeStepBadge = result.stepLabel
        isKeyScrubbing = true
        keyScrubNonce++
        onInteraction()
    }

    fun jumpByOneMinute(direction: Int) {
        val baseMs = if (isScrubbing) previewPositionMs else currentPositionMs
        val step = if (durationMs in 1L..59_999L) 15_000L else SeekBarScrubEngine.ONE_MINUTE_MS
        val target = if (durationMs > 0L) {
            (baseMs + direction * step).coerceIn(0L, durationMs)
        } else {
            (baseMs + direction * step).coerceAtLeast(0L)
        }
        previewPositionMs = target
        activeStepBadge = SeekBarScrubEngine.formatStepBadge(direction * step)
        isKeyScrubbing = false
        consecutiveSteps = 0
        onSeekCommit(target)
        onInteraction()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Floating Preview Timestamp Pill when focused or scrubbing
        AnimatedVisibility(
            visible = isScrubbing || isFocused,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(150))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xD90E0E11),
                    border = BorderStroke(0.75.dp, CinemaAmber.copy(alpha = 0.65f)),
                    modifier = Modifier.testTag("seekbar_preview_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = formatDuration(displayedPositionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = CinemaAmberBright
                        )
                        if (activeStepBadge != null) {
                            Text(
                                text = activeStepBadge!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }

                if (isFocused) {
                    Text(
                        text = if (isTv) "◀ ▶ Jump ±1m–±10m · OK to seek" else "Scrubbing",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
            }
        }

        // Row: [-1m] [ Selectable Interactive Play Bar ] [+1m]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Fast -1m Minute Backward Pill
            Surface(
                onClick = { jumpByOneMinute(-1) },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xB8121215),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.16f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.06f)
                    .testTag("player_rewind_1m_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FastRewind,
                        contentDescription = "Rewind 1 minute",
                        tint = CinemaAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "1m",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            // Selectable Play Bar Track
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                        if (focusState.isFocused) {
                            onInteraction()
                        }
                    }
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (keyEvent.key) {
                            Key.DirectionLeft -> {
                                performKeyMinuteScrub(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                performKeyMinuteScrub(1)
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (isKeyScrubbing) {
                                    isKeyScrubbing = false
                                    consecutiveSteps = 0
                                    lastStepDirection = 0
                                    onSeekCommit(previewPositionMs)
                                    activeStepBadge = null
                                    onInteraction()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                    .then(
                        if (isFocused) {
                            Modifier
                                .background(CinemaAmber.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                                .border(2.5.dp, Color.White, RoundedCornerShape(12.dp))
                                .padding(1.5.dp)
                                .border(2.dp, CinemaAmberBright, RoundedCornerShape(11.dp))
                        } else {
                            Modifier
                        }
                    )
                    .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                            val targetMs = SeekBarScrubEngine.fractionToPositionMs(frac, durationMs)
                            previewPositionMs = targetMs
                            onSeekCommit(targetMs)
                            onInteraction()
                        }
                    }
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isTouchScrubbing = true
                                val frac = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                                previewPositionMs = SeekBarScrubEngine.fractionToPositionMs(frac, durationMs)
                                onInteraction()
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val frac = (change.position.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                                previewPositionMs = SeekBarScrubEngine.fractionToPositionMs(frac, durationMs)
                                onInteraction()
                            },
                            onDragEnd = {
                                isTouchScrubbing = false
                                onSeekCommit(previewPositionMs)
                                onInteraction()
                            },
                            onDragCancel = {
                                isTouchScrubbing = false
                            }
                        )
                    }
                    .padding(horizontal = 10.dp)
                    .semantics {
                        contentDescription = "Playback timeline seek bar"
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = progressFraction,
                            range = 0f..1f
                        )
                    }
                    .testTag("player_seek_bar"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                ) {
                    val trackPx = trackHeight.toPx()
                    val thumbPx = thumbRadius.toPx()
                    val centerY = size.height / 2f
                    val trackTop = centerY - trackPx / 2f
                    val corner = CornerRadius(trackPx / 2f, trackPx / 2f)

                    // Background track
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.25f),
                        topLeft = Offset(0f, trackTop),
                        size = Size(size.width, trackPx),
                        cornerRadius = corner
                    )

                    // Buffered track
                    if (bufferedFraction > 0f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.42f),
                            topLeft = Offset(0f, trackTop),
                            size = Size(size.width * bufferedFraction, trackPx),
                            cornerRadius = corner
                        )
                    }

                    // Active played/previewed track
                    val activeWidth = (size.width * progressFraction).coerceIn(0f, size.width)
                    if (activeWidth > 0f) {
                        drawRoundRect(
                            color = if (isFocused || isScrubbing) CinemaAmberBright else CinemaAmber,
                            topLeft = Offset(0f, trackTop),
                            size = Size(activeWidth, trackPx),
                            cornerRadius = corner
                        )
                    }

                    // A-B Loop cyan range highlight & pins
                    if (durationMs > 0L && abLoop.startMs != null) {
                        val startFrac = SeekBarScrubEngine.positionToFraction(abLoop.startMs, durationMs)
                        val startX = (size.width * startFrac).coerceIn(0f, size.width)
                        if (abLoop.endMs != null && abLoop.endMs > abLoop.startMs) {
                            val endFrac = SeekBarScrubEngine.positionToFraction(abLoop.endMs, durationMs)
                            val endX = (size.width * endFrac).coerceIn(startX, size.width)
                            drawRoundRect(
                                color = StreamCyan.copy(alpha = 0.78f),
                                topLeft = Offset(startX, trackTop),
                                size = Size((endX - startX).coerceAtLeast(3f), trackPx),
                                cornerRadius = corner
                            )
                            drawCircle(
                                color = StreamCyan,
                                radius = 4.5.dp.toPx(),
                                center = Offset(endX, centerY)
                            )
                        }
                        drawCircle(
                            color = StreamCyan,
                            radius = 4.5.dp.toPx(),
                            center = Offset(startX, centerY)
                        )
                    }

                    // Chapter Marker ticks
                    if (durationMs > 0L && chapters.size > 1) {
                        chapters.drop(1).forEach { ch ->
                            val frac = SeekBarScrubEngine.positionToFraction(ch.startMs, durationMs)
                            if (frac in 0.02f..0.98f) {
                                val cx = size.width * frac
                                drawLine(
                                    color = Color.Black.copy(alpha = 0.8f),
                                    start = Offset(cx, trackTop - 1.dp.toPx()),
                                    end = Offset(cx, trackTop + trackPx + 1.dp.toPx()),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                    }

                    // Outer glow halo when focused or scrubbing
                    if (isFocused || isScrubbing) {
                        drawCircle(
                            color = CinemaAmber.copy(alpha = 0.35f),
                            radius = thumbPx + 5.dp.toPx(),
                            center = Offset(activeWidth, centerY)
                        )
                    }

                    // Scrub Thumb
                    drawCircle(
                        color = Color.White,
                        radius = thumbPx,
                        center = Offset(activeWidth, centerY)
                    )
                    drawCircle(
                        color = CinemaAmber,
                        radius = thumbPx * 0.62f,
                        center = Offset(activeWidth, centerY)
                    )
                }
            }

            // Fast +1m Minute Forward Pill
            Surface(
                onClick = { jumpByOneMinute(1) },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xB8121215),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.16f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.06f)
                    .testTag("player_forward_1m_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "1m",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = "Forward 1 minute",
                        tint = CinemaAmber,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
