package com.example.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ui.theme.CinemaAmberBright

fun isAndroidTv(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val noTouchscreen = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return isTvMode || (hasLeanback && noTouchscreen)
}

@Composable
fun rememberIsAndroidTv(): Boolean {
    val context = LocalContext.current
    return remember(context) { isAndroidTv(context) }
}

/**
 * Informational check for whether the device exposes a system document or content picker.
 * Do NOT use this to hard-block picker launches — always attempt launching with try/catch fallback.
 */
fun canOpenDocumentPicker(context: Context): Boolean {
    val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    return openDocIntent.resolveActivity(context.packageManager) != null ||
        getContentIntent.resolveActivity(context.packageManager) != null
}

/**
 * Draws a high-contrast, unmistakable D-Pad / TV / keyboard focus selector ON TOP of any
 * composable (Button, Surface, IconButton, FilterChip, DropdownMenuItem, or Card).
 *
 * Uses drawWithContent so the focus ring & luminous highlight tint are NEVER covered by a
 * Surface or Button's own background fill, lifts zIndex to prevent sibling clipping, and
 * tracks exact focus (`it.isFocused` by default) so parent cards don't falsely light up when
 * a child action icon is focused.
 */
fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(14.dp),
    borderColor: Color = CinemaAmberBright,
    outerRingColor: Color = Color.White,
    borderWidth: Dp = 3.dp,
    focusedScale: Float = 1.05f,
    glowAlpha: Float = 0.22f,
    includeChildFocus: Boolean = false,
    onFocusStateChanged: ((Boolean) -> Unit)? = null
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "tv_focus_scale"
    )
    this
        .zIndex(if (isFocused) 2f else 0f)
        .onFocusChanged { focusState ->
            val nowFocused = if (includeChildFocus) {
                focusState.isFocused || focusState.hasFocus
            } else {
                focusState.isFocused
            }
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                onFocusStateChanged?.invoke(nowFocused)
            }
        }
        .scale(scale)
        .drawWithContent {
            drawContent()
            if (isFocused) {
                val outline = shape.createOutline(size, layoutDirection, this)
                // Luminous interior tint so dark or transparent controls visibly light up
                drawOutline(
                    outline = outline,
                    color = borderColor.copy(alpha = glowAlpha)
                )
                // Crisp outer white halo so focus pops against both dark and amber controls
                drawOutline(
                    outline = outline,
                    color = outerRingColor.copy(alpha = 0.95f),
                    style = Stroke(width = (borderWidth + 1.5.dp).toPx())
                )
                // Vibrant inner amber focus ring
                drawOutline(
                    outline = outline,
                    color = borderColor,
                    style = Stroke(width = (borderWidth * 0.65f).toPx())
                )
            }
        }
}
