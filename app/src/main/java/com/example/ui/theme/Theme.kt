package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorMixHighest = androidx.compose.ui.graphics.Color(0xFF2B3548)

private val CineStreamDarkColorScheme = darkColorScheme(
    primary = CinemaAmber,
    onPrimary = OnCinemaAmber,
    primaryContainer = CinemaAmberContainer,
    onPrimaryContainer = OnCinemaAmberContainer,
    secondary = StreamCyan,
    onSecondary = OnStreamCyan,
    secondaryContainer = StreamCyanContainer,
    onSecondaryContainer = OnStreamCyanContainer,
    tertiary = SubtitleEmerald,
    onTertiary = OnSubtitleEmerald,
    tertiaryContainer = SubtitleEmeraldContainer,
    onTertiaryContainer = OnSubtitleEmeraldContainer,
    background = ObsidianCanvas,
    onBackground = IvoryInk,
    surface = ObsidianSurface,
    onSurface = IvoryInk,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = SlateInkSecondary,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceHigh,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    surfaceContainerHighest = ColorMixHighest,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle,
    error = SignalRose,
    onError = OnSignalRose,
    errorContainer = SignalRoseContainer,
    onErrorContainer = OnSignalRose
)

val CineStreamShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CineStreamTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CineStreamDarkColorScheme,
        typography = CineStreamTypography,
        shapes = CineStreamShapes,
        content = content
    )
}
