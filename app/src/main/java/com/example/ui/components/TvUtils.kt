package com.example.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * Zero-allocation [ModifierNodeElement] D-Pad / TV / keyboard focus highlight.
 *
 * Replaces `Modifier.composed { animateFloatAsState(...) }` to eliminate Compose SlotTable
 * materialization overhead and per-button animation coroutines across HomeScreen and PlayerScreen.
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
): Modifier = this.then(
    TvFocusHighlightElement(
        shape = shape,
        borderColor = borderColor,
        outerRingColor = outerRingColor,
        borderWidth = borderWidth,
        focusedScale = focusedScale,
        glowAlpha = glowAlpha,
        includeChildFocus = includeChildFocus,
        onFocusStateChanged = onFocusStateChanged
    )
)

private data class TvFocusHighlightElement(
    val shape: Shape,
    val borderColor: Color,
    val outerRingColor: Color,
    val borderWidth: Dp,
    val focusedScale: Float,
    val glowAlpha: Float,
    val includeChildFocus: Boolean,
    val onFocusStateChanged: ((Boolean) -> Unit)?
) : ModifierNodeElement<TvFocusHighlightNode>() {

    override fun create(): TvFocusHighlightNode = TvFocusHighlightNode(
        shape = shape,
        borderColor = borderColor,
        outerRingColor = outerRingColor,
        borderWidth = borderWidth,
        focusedScale = focusedScale,
        glowAlpha = glowAlpha,
        includeChildFocus = includeChildFocus,
        onFocusStateChanged = onFocusStateChanged
    )

    override fun update(node: TvFocusHighlightNode) {
        node.shape = shape
        node.borderColor = borderColor
        node.outerRingColor = outerRingColor
        node.borderWidth = borderWidth
        node.focusedScale = focusedScale
        node.glowAlpha = glowAlpha
        node.includeChildFocus = includeChildFocus
        node.onFocusStateChanged = onFocusStateChanged
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusHighlight"
        properties["borderColor"] = borderColor
        properties["focusedScale"] = focusedScale
        properties["includeChildFocus"] = includeChildFocus
    }
}

private class TvFocusHighlightNode(
    var shape: Shape,
    var borderColor: Color,
    var outerRingColor: Color,
    var borderWidth: Dp,
    var focusedScale: Float,
    var glowAlpha: Float,
    var includeChildFocus: Boolean,
    var onFocusStateChanged: ((Boolean) -> Unit)?
) : Modifier.Node(), FocusEventModifierNode, LayoutModifierNode, DrawModifierNode {

    private var isFocused: Boolean = false

    override fun onFocusEvent(focusState: FocusState) {
        val nowFocused = if (includeChildFocus) {
            focusState.isFocused || focusState.hasFocus
        } else {
            focusState.isFocused
        }
        if (isFocused != nowFocused) {
            isFocused = nowFocused
            onFocusStateChanged?.invoke(nowFocused)
            invalidatePlacement()
            invalidateDraw()
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val activeScale = if (isFocused) focusedScale else 1f
            val activeZIndex = if (isFocused) 2f else 0f
            if (activeScale != 1f || activeZIndex != 0f) {
                placeable.placeWithLayer(0, 0, zIndex = activeZIndex) {
                    scaleX = activeScale
                    scaleY = activeScale
                }
            } else {
                placeable.place(0, 0)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (isFocused) {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                color = borderColor.copy(alpha = glowAlpha)
            )
            drawOutline(
                outline = outline,
                color = outerRingColor.copy(alpha = 0.95f),
                style = Stroke(width = (borderWidth + 1.5.dp).toPx())
            )
            drawOutline(
                outline = outline,
                color = borderColor,
                style = Stroke(width = (borderWidth * 0.65f).toPx())
            )
        }
    }
}
