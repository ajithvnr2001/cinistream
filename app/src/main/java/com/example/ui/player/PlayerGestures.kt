package com.example.ui.player

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StreamCyan
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure clamping & window brightness helpers for left-side brightness swipe and 2-finger pinch zoom.
 * Adjusts only the Activity's local window brightness (zero system settings permission required).
 */
object PlayerGestureMath {
    const val MIN_BRIGHTNESS_PERCENT = 2
    const val MAX_BRIGHTNESS_PERCENT = 100
    const val MIN_ZOOM_SCALE = 1.0f
    const val MAX_ZOOM_SCALE = 2.5f
    const val SNAP_TO_FIT_THRESHOLD = 1.04f

    fun clampBrightnessPercent(percent: Int): Int =
        percent.coerceIn(MIN_BRIGHTNESS_PERCENT, MAX_BRIGHTNESS_PERCENT)

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
}

/**
 * Frosted-obsidian HUD pill shown while dragging vertically on the left half of the video
 * to adjust per-window brightness (matching CineStream's right-side volume boost HUD).
 */
@Composable
fun BrightnessHudOverlay(
    brightnessPercent: Int,
    modifier: Modifier = Modifier
) {
    val clamped = PlayerGestureMath.clampBrightnessPercent(brightnessPercent)
    val fraction = clamped / 100f

    Surface(
        modifier = modifier
            .width(172.dp)
            .testTag("player_brightness_hud"),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, StreamCyan.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Brightness6,
                        contentDescription = null,
                        tint = StreamCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Brightness",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
                Text(
                    text = "$clamped%",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = StreamCyan
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = StreamCyan,
                trackColor = Color.White.copy(alpha = 0.18f)
            )
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
