package com.example.ui.player

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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Remove
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.player.AudioBoostMath
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaAmberBright
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SignalRose
import kotlinx.coroutines.delay

/**
 * VLC / MX Player style vertical volume + 200% boost HUD displayed during vertical swipe
 * gestures or volume step adjustments.
 */
@Composable
fun VerticalVolumeBoostHud(
    volumePercent: Int,
    modifier: Modifier = Modifier
) {
    val clamped = AudioBoostMath.clampPercent(volumePercent)
    val isBoosted = clamped > AudioBoostMath.NORMAL_MAX_PERCENT
    val accent = when {
        clamped == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        clamped >= 160 -> SignalRose
        isBoosted -> CinemaAmberBright
        else -> CinemaAmber
    }
    val fraction = clamped / AudioBoostMath.MAX_PERCENT.toFloat()

    Surface(
        modifier = modifier.testTag("volume_boost_hud"),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.80f),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isBoosted) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = when {
                    clamped == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
                    isBoosted -> Icons.Rounded.GraphicEq
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )

            // Vertical Gauge Bar with 100% Threshold Notch
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(132.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (isBoosted) {
                                Brush.verticalGradient(listOf(SignalRose, CinemaAmberBright, CinemaAmber))
                            } else {
                                Brush.verticalGradient(listOf(CinemaAmber, CinemaAmber.copy(alpha = 0.7f)))
                            }
                        )
                )
                // 100% nominal divider mark in middle of 0–200% gauge
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.75f))
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$clamped%",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
                if (isBoosted) {
                    Text(
                        text = "BOOST",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = accent
                    )
                }
            }
        }
    }
}

/**
 * Interactive 0%–200% Sound Booster control panel shared between the TracksSheet and the
 * quick Player volume/boost dialog (Mobile + Android TV D-Pad compatible).
 */
@Composable
fun VolumeBoostControlSection(
    volumePercent: Int,
    onSetVolumePercent: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequesterForPlus: FocusRequester? = null
) {
    val clamped = AudioBoostMath.clampPercent(volumePercent)
    val isBoosted = clamped > AudioBoostMath.NORMAL_MAX_PERCENT
    val accent = when {
        clamped >= 160 -> SignalRose
        isBoosted -> CinemaAmberBright
        else -> CinemaAmber
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ObsidianSurfaceHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (isBoosted) accent.copy(alpha = 0.65f) else ObsidianOutline.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                        imageVector = if (isBoosted) Icons.Rounded.GraphicEq else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Sound Booster (Max 200%)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isBoosted) {
                                "Loudness Amplifier ${AudioBoostMath.formatGainDb(clamped)}"
                            } else {
                                "Standard audio output (drag > 100% to boost)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isBoosted) accent else MaterialTheme.colorScheme.onSurfaceVariant
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
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = accent,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("volume_boost_percent_label")
                    )
                }
            }

            // -10% | Slider (0..200) | +10%
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalIconButton(
                    onClick = { onSetVolumePercent(AudioBoostMath.clampPercent(clamped - 10)) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = ObsidianSurface,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("volume_boost_minus_button")
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Decrease sound 10%")
                }

                Slider(
                    value = clamped.toFloat(),
                    onValueChange = { onSetVolumePercent(it.toInt()) },
                    valueRange = 0f..200f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("volume_boost_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = ObsidianOutline.copy(alpha = 0.55f)
                    )
                )

                FilledTonalIconButton(
                    onClick = { onSetVolumePercent(AudioBoostMath.clampPercent(clamped + 10)) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = ObsidianSurface,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .then(
                            if (focusRequesterForPlus != null) {
                                Modifier.focusRequester(focusRequesterForPlus)
                            } else {
                                Modifier
                            }
                        )
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("volume_boost_plus_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Increase sound 10%")
                }
            }

            // Quick VLC / MX Preset Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val presets = listOf(0 to "Mute", 100 to "100%", 150 to "150%", 200 to "200%")
                presets.forEach { (pct, label) ->
                    val selected = clamped == pct
                    val isBoostPreset = pct > 100
                    FilterChip(
                        selected = selected,
                        onClick = { onSetVolumePercent(pct) },
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
                            .testTag("volume_preset_${pct}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = (if (isBoostPreset) SignalRose else CinemaAmber).copy(alpha = 0.22f),
                            selectedLabelColor = if (isBoostPreset) SignalRose else CinemaAmber
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun VolumeBoostDialog(
    volumePercent: Int,
    onSetVolumePercent: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val plusFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(90L)
        runCatching { plusFocusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(22.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.5.dp, CinemaAmber.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VolumeBoostControlSection(
                    volumePercent = volumePercent,
                    onSetVolumePercent = onSetVolumePercent,
                    focusRequesterForPlus = plusFocusRequester
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tip: Swipe up/down on right side of video",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                            .testTag("volume_dialog_done_button")
                    ) {
                        Text("Done", color = CinemaAmber)
                    }
                }
            }
        }
    }
}
