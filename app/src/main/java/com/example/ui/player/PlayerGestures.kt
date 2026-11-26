package com.example.ui.player

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaAmberBright
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.StreamCyan
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure clamping, window brightness, and TV brightness overlay helpers for mobile vertical
 * brightness swipe, Android TV D-Pad brightness controls, and 2-finger pinch zoom.
 * Adjusts only the Activity's local window brightness (zero system settings permission required).
 */
object PlayerGestureMath {
    const val MIN_BRIGHTNESS_PERCENT = 2
    const val MAX_BRIGHTNESS_PERCENT = 100
    const val TV_MIN_BRIGHTNESS_PERCENT = 10
    const val TV_DEFAULT_BRIGHTNESS_PERCENT = 100
    const val TV_MAX_BRIGHTNESS_PERCENT = 150
    const val TV_BRIGHTNESS_STEP = 10
    const val MIN_ZOOM_SCALE = 1.0f
    const val MAX_ZOOM_SCALE = 2.5f
    const val SNAP_TO_FIT_THRESHOLD = 1.04f

    fun clampBrightnessPercent(percent: Int): Int =
        percent.coerceIn(MIN_BRIGHTNESS_PERCENT, MAX_BRIGHTNESS_PERCENT)

    fun clampTvBrightnessPercent(percent: Int): Int =
        percent.coerceIn(TV_MIN_BRIGHTNESS_PERCENT, TV_MAX_BRIGHTNESS_PERCENT)

    /**
     * Software dimming overlay alpha for Android TV / HDMI boxes when brightness is lowered
     * below 100%. Returns 0f at 100% or above, ramping smoothly up to 0.78f at 10%.
     */
    fun computeTvDimOverlayAlpha(percent: Int): Float {
        val clamped = clampTvBrightnessPercent(percent)
        if (clamped >= TV_DEFAULT_BRIGHTNESS_PERCENT) return 0f
        val range = (TV_DEFAULT_BRIGHTNESS_PERCENT - TV_MIN_BRIGHTNESS_PERCENT).toFloat()
        return (((TV_DEFAULT_BRIGHTNESS_PERCENT - clamped) / range) * 0.78f).coerceIn(0f, 0.78f)
    }

    /**
     * Subtle luminance lift overlay alpha for Android TV / HDMI boxes when brightness is boosted
     * above 100% (paired with 1.0f hardware window backlight). Returns 0f at 100% or below.
     */
    fun computeTvBoostOverlayAlpha(percent: Int): Float {
        val clamped = clampTvBrightnessPercent(percent)
        if (clamped <= TV_DEFAULT_BRIGHTNESS_PERCENT) return 0f
        val range = (TV_MAX_BRIGHTNESS_PERCENT - TV_DEFAULT_BRIGHTNESS_PERCENT).toFloat()
        return (((clamped - TV_DEFAULT_BRIGHTNESS_PERCENT) / range) * 0.18f).coerceIn(0f, 0.18f)
    }

    fun clampZoomScale(scale: Float): Float {
        val clamped = scale.coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        return if (clamped < SNAP_TO_FIT_THRESHOLD) MIN_ZOOM_SCALE else clamped
    }

    fun formatZoomBadge(scale: Float): String {
        val clamped = clampZoomScale(scale)
        return if (clamped <= MIN_ZOOM_SCALE + 0.01f) {
            "Fit · 1.0×"
        } else {
            String.format(Locale.US, "%.2f×", clamped)
        }
    }

    fun readWindowBrightnessPercent(activity: Activity?): Int {
        val attr = activity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        return if (attr > 0f) {
            clampBrightnessPercent((attr * 100f).roundToInt())
        } else {
            50
        }
    }

    fun applyWindowBrightnessPercent(activity: Activity?, percent: Int) {
        val win = activity?.window ?: return
        val clamped = clampBrightnessPercent(percent)
        val lp = win.attributes
        lp.screenBrightness = (clamped / 100f).coerceIn(0.02f, 1.0f)
        win.attributes = lp
    }

    fun applyTvBrightnessPercent(activity: Activity?, percent: Int) {
        val win = activity?.window ?: return
        val clamped = clampTvBrightnessPercent(percent)
        val lp = win.attributes
        lp.screenBrightness = if (clamped == TV_DEFAULT_BRIGHTNESS_PERCENT) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            (clamped / 100f).coerceIn(0.10f, 1.0f)
        }
        win.attributes = lp
    }
}

/**
 * VLC / MX Player style vertical brightness HUD matching [VerticalVolumeBoostHud].
 * Shown while dragging vertically on the left half of the video on mobile or stepping
 * brightness on Android TV.
 */
@Composable
fun BrightnessHudOverlay(
    brightnessPercent: Int,
    modifier: Modifier = Modifier,
    maxPercent: Int = PlayerGestureMath.MAX_BRIGHTNESS_PERCENT
) {
    val isTvBoostScale = maxPercent > PlayerGestureMath.MAX_BRIGHTNESS_PERCENT
    val clamped = if (isTvBoostScale) {
        PlayerGestureMath.clampTvBrightnessPercent(brightnessPercent)
    } else {
        PlayerGestureMath.clampBrightnessPercent(brightnessPercent)
    }
    val isBoosted = clamped > PlayerGestureMath.MAX_BRIGHTNESS_PERCENT
    val accent = when {
        isBoosted -> CinemaAmberBright
        clamped <= 20 -> StreamCyan.copy(alpha = 0.78f)
        else -> StreamCyan
    }
    val fraction = (clamped / maxPercent.toFloat()).coerceIn(0f, 1f)

    Surface(
        modifier = modifier.testTag("player_brightness_hud"),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.80f),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isBoosted) accent.copy(alpha = 0.85f) else StreamCyan.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Brightness6,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )

            // Vertical Brightness Gauge Bar (mirrors VerticalVolumeBoostHud 10dp × 132dp geometry)
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(132.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .testTag("player_brightness_vertical_bar"),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (isBoosted) {
                                Brush.verticalGradient(listOf(CinemaAmberBright, StreamCyan))
                            } else {
                                Brush.verticalGradient(listOf(StreamCyan, StreamCyan.copy(alpha = 0.65f)))
                            }
                        )
                )
                if (isTvBoostScale) {
                    val baselineFraction = PlayerGestureMath.MAX_BRIGHTNESS_PERCENT / maxPercent.toFloat()
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = (132f * baselineFraction).dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Color.White.copy(alpha = 0.75f))
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$clamped%",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
                if (isBoosted) {
                    Text(
                        text = "BRIGHT",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = accent
                    )
                }
            }
        }
    }
}

/**
 * Non-dimming Android TV D-Pad brightness control popover anchored below the player top bar
 * so viewers can visibly decrease (10%–99% dim) or increase (101%–150% bright boost) playback
 * brightness live without obscuring the video frame.
 */
@Composable
fun TvBrightnessControlPanel(
    brightnessPercent: Int,
    onSetBrightnessPercent: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clamped = PlayerGestureMath.clampTvBrightnessPercent(brightnessPercent)
    val isBoosted = clamped > PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT
    val isDimmed = clamped < PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT
    val accent = when {
        isBoosted -> CinemaAmberBright
        isDimmed -> StreamCyan
        else -> StreamCyan
    }
    val statusLabel = when {
        isBoosted -> "Bright Boost (+${clamped - 100}%)"
        isDimmed -> "Night Dim (${clamped}%)"
        else -> "Standard (100%)"
    }
    val plusFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { plusFocusRequester.requestFocus() }
    }

    Surface(
        modifier = modifier
            .widthIn(max = 368.dp)
            .testTag("tv_brightness_panel"),
        shape = RoundedCornerShape(20.dp),
        color = ObsidianSurface.copy(alpha = 0.94f),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Brightness6,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.brightness_control_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isBoosted || isDimmed) accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "$clamped%",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = accent,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("tv_brightness_percent_label")
                    )
                }
            }

            // Decrease (-10%) | Slider (10%..150%) | Increase (+10%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalIconButton(
                    onClick = {
                        onSetBrightnessPercent(
                            PlayerGestureMath.clampTvBrightnessPercent(clamped - PlayerGestureMath.TV_BRIGHTNESS_STEP)
                        )
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = ObsidianSurfaceHigh,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("tv_brightness_minus_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = stringResource(R.string.cd_brightness_decrease)
                    )
                }

                Slider(
                    value = clamped.toFloat(),
                    onValueChange = { onSetBrightnessPercent(PlayerGestureMath.clampTvBrightnessPercent(it.roundToInt())) },
                    valueRange = PlayerGestureMath.TV_MIN_BRIGHTNESS_PERCENT.toFloat()..PlayerGestureMath.TV_MAX_BRIGHTNESS_PERCENT.toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tv_brightness_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = ObsidianOutline.copy(alpha = 0.55f)
                    )
                )

                FilledTonalIconButton(
                    onClick = {
                        onSetBrightnessPercent(
                            PlayerGestureMath.clampTvBrightnessPercent(clamped + PlayerGestureMath.TV_BRIGHTNESS_STEP)
                        )
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = ObsidianSurfaceHigh,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .focusRequester(plusFocusRequester)
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("tv_brightness_plus_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.cd_brightness_increase)
                    )
                }
            }

            // Quick TV Brightness Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val presets = listOf(30 to "30%", 60 to "60%", 100 to "100%", 125 to "125%", 150 to "150%")
                presets.forEach { (pct, label) ->
                    val selected = clamped == pct
                    val isBoostPreset = pct > PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT
                    val chipAccent = if (isBoostPreset) CinemaAmberBright else StreamCyan
                    FilterChip(
                        selected = selected,
                        onClick = { onSetBrightnessPercent(pct) },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .tvFocusHighlight(shape = RoundedCornerShape(8.dp))
                            .testTag("tv_brightness_preset_$pct"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipAccent.copy(alpha = 0.22f),
                            selectedLabelColor = chipAccent
                        )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (clamped != PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT) {
                    TextButton(
                        onClick = { onSetBrightnessPercent(PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                            .testTag("tv_brightness_reset_button")
                    ) {
                        Text("Reset 100%", color = StreamCyan)
                    }
                } else {
                    Text(
                        text = "Press − or + to adjust",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                        .testTag("tv_brightness_done_button")
                ) {
                    Text("Done", color = CinemaAmber)
                }
            }
        }
    }
}

/**
 * Transient center zoom pill displayed during 2-finger pinch-to-zoom gestures.
 */
@Composable
fun ZoomHudBadge(
    zoomScale: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag("player_zoom_hud"),
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.65f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ZoomIn,
                contentDescription = null,
                tint = CinemaAmber,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = PlayerGestureMath.formatZoomBadge(zoomScale),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = CinemaAmber
            )
        }
    }
}
