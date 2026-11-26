package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.material.icons.rounded.Translate
import com.example.R
import com.example.model.SubtitleAppearance
import com.example.model.SubtitleBgPreset
import com.example.model.SubtitleColorPreset
import com.example.model.SubtitleTextSize
import com.example.model.TrackOption
import com.example.player.AudioDelayMath
import com.example.player.SyncTimeUnit
import com.example.ui.components.SubtitlePillBadge
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SubtitleEmerald
import com.example.viewmodel.SecondarySubtitleUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSheet(
    subtitleTracks: List<TrackOption>,
    subtitlesDisabled: Boolean,
    hasExternalSubtitles: Boolean,
    subtitleOffsetMs: Long,
    appearance: SubtitleAppearance,
    onDismiss: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onLoadSubtitleUrl: (String) -> Unit,
    onSelectTrack: (TrackOption) -> Unit,
    onDisableSubtitles: () -> Unit,
    onAdjustOffset: (Long) -> Unit,
    onSetOffset: ((Long) -> Unit)? = null,
    onResetOffset: () -> Unit,
    onUpdateTextSize: (SubtitleTextSize) -> Unit,
    onUpdateColor: (SubtitleColorPreset) -> Unit,
    onUpdateBg: (SubtitleBgPreset) -> Unit,
    secondarySubtitleState: SecondarySubtitleUiState = SecondarySubtitleUiState(),
    onPickSecondarySubtitleFile: () -> Unit = {},
    onLoadSecondarySubtitleUrl: (String) -> Unit = {},
    onClearSecondarySubtitle: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val loadFileFocusRequester = remember { FocusRequester() }
    var showUrlField by rememberSaveable { mutableStateOf(false) }
    var subtitleUrl by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        delay(120L)
        runCatching { loadFileFocusRequester.requestFocus() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianSurface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sheet Header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.subtitle_sheet_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Load .srt, .vtt, .ass, .ssa, or .ttml captions from device or URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Load External Subtitle Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickSubtitleFile,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .focusRequester(loadFileFocusRequester)
                        .tvFocusHighlight(shape = RoundedCornerShape(14.dp))
                        .testTag("player_load_subtitle_file_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Load File", maxLines = 1)
                }

                OutlinedButton(
                    onClick = { showUrlField = !showUrlField },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .tvFocusHighlight(shape = RoundedCornerShape(14.dp))
                        .testTag("player_toggle_subtitle_url_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ObsidianOutline)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Subtitle URL", maxLines = 1)
                }
            }

            // Expandable Subtitle URL Field
            AnimatedVisibility(visible = showUrlField) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = subtitleUrl,
                        onValueChange = { subtitleUrl = it },
                        placeholder = { Text(stringResource(R.string.subtitle_url_placeholder)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .tvFocusHighlight(
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.01f,
                                glowAlpha = 0.12f,
                                includeChildFocus = true
                            )
                            .testTag("player_subtitle_url_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            onLoadSubtitleUrl(subtitleUrl)
                            subtitleUrl = ""
                            showUrlField = false
                        },
                        enabled = subtitleUrl.isNotBlank(),
                        modifier = Modifier
                            .height(52.dp)
                            .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                            .testTag("player_subtitle_url_apply"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Load")
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.6f))

            // Subtitle Track Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Available Tracks (${subtitleTracks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SubtitleTrackRow(
                    label = stringResource(R.string.subtitle_off),
                    detail = "Hide captions",
                    badge = null,
                    isSelected = subtitlesDisabled || subtitleTracks.none { it.isSelected },
                    isOffRow = true,
                    onClick = onDisableSubtitles,
                    testTag = "subtitle_track_off"
                )

                subtitleTracks.forEachIndexed { index, track ->
                    SubtitleTrackRow(
                        label = track.label,
                        detail = track.detail,
                        badge = if (track.isExternal) "EXT" else "EMB",
                        isSelected = !subtitlesDisabled && track.isSelected,
                        isOffRow = false,
                        onClick = { onSelectTrack(track) },
                        testTag = "subtitle_track_$index"
                    )
                }
            }

            // Subtitle Sync Offset (for loaded external SRT/VTT files)
            if (hasExternalSubtitles) {
                HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.6f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.subtitle_sync_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (subtitleOffsetMs != 0L) {
                            TextButton(
                                onClick = onResetOffset,
                                modifier = Modifier
                                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                    .testTag("subtitle_offset_reset")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Reset")
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ObsidianSurfaceHigh,
                        border = BorderStroke(1.dp, ObsidianOutline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = AudioDelayMath.formatSubtitleOffsetLabel(subtitleOffsetMs),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (subtitleOffsetMs == 0L) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        CinemaAmber
                                    },
                                    modifier = Modifier.testTag("subtitle_offset_value")
                                )
                                Text(
                                    text = "±100ms / ±500ms (-10s..+10s) · Enter ms or s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { onAdjustOffset(-500L) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("subtitle_offset_minus_500"),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text("-0.5s", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = { onAdjustOffset(-100L) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("subtitle_offset_minus"),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text("-0.1s", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = { onAdjustOffset(100L) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("subtitle_offset_plus"),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text("+0.1s", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = { onAdjustOffset(500L) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("subtitle_offset_plus_500"),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text("+0.5s", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            SyncOffsetNumericInputRow(
                                currentOffsetMs = subtitleOffsetMs,
                                testTagPrefix = "subtitle_offset",
                                defaultUnit = SyncTimeUnit.SECONDS,
                                hintDirection = "− earlier · + later",
                                onApplyOffsetMs = { targetMs ->
                                    if (onSetOffset != null) {
                                        onSetOffset(targetMs)
                                    } else {
                                        onAdjustOffset(targetMs - subtitleOffsetMs)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.6f))

            // Subtitle Appearance Customizer
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.subtitle_style_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Live Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(ObsidianCanvas, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(appearance.bgPreset.argb), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "CineStream Live Subtitle Preview",
                            color = Color(appearance.colorPreset.argb),
                            fontSize = (appearance.textSize.sp * 0.85f).sp
                        )
                    }
                }

                // Size Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubtitleTextSize.entries.forEach { size ->
                        val selected = appearance.textSize == size
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateTextSize(size) },
                            label = { Text(size.label) },
                            modifier = Modifier
                                .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.05f)
                                .testTag("subtitle_size_chip_${size.name.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CinemaAmber.copy(alpha = 0.2f),
                                selectedLabelColor = CinemaAmber
                            )
                        )
                    }
                }

                // Color Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubtitleColorPreset.entries.forEach { preset ->
                        val selected = appearance.colorPreset == preset
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateColor(preset) },
                            label = { Text(preset.label) },
                            modifier = Modifier
                                .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.05f)
                                .testTag("subtitle_color_chip_${preset.name.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CinemaAmber.copy(alpha = 0.2f),
                                selectedLabelColor = CinemaAmber
                            )
                        )
                    }
                }

                // Background Style Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubtitleBgPreset.entries.forEach { bg ->
                        val selected = appearance.bgPreset == bg
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateBg(bg) },
                            label = { Text(bg.label) },
                            modifier = Modifier
                                .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.05f)
                                .testTag("subtitle_bg_chip_${bg.name.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CinemaAmber.copy(alpha = 0.2f),
                                selectedLabelColor = CinemaAmber
                            )
                        )
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.6f))

            // Dual Subtitles (Secondary Top Overlay)
            var showDualUrl by rememberSaveable { mutableStateOf(false) }
            var dualUrl by rememberSaveable { mutableStateOf("") }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dual_subtitles_section"),
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurfaceHigh,
                border = BorderStroke(
                    1.dp,
                    if (secondarySubtitleState.activeTrack != null) SubtitleEmerald.copy(alpha = 0.6f)
                    else ObsidianOutline.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Translate,
                                contentDescription = null,
                                tint = SubtitleEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.dual_subtitles_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        if (secondarySubtitleState.activeTrack != null) {
                            TextButton(
                                onClick = onClearSecondarySubtitle,
                                modifier = Modifier.testTag("dual_subtitle_clear_button")
                            ) {
                                Text("Clear")
                            }
                        }
                    }

                    Text(
                        text = secondarySubtitleState.activeTrack?.let {
                            "Active Top Track: ${it.label} (${it.cues.size} cues)"
                        } ?: "Display a second .srt/.vtt language simultaneously at the top of the screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickSecondarySubtitleFile,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dual_subtitle_file_button")
                        ) {
                            Text("Secondary File", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showDualUrl = !showDualUrl },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dual_subtitle_url_toggle")
                        ) {
                            Text("Secondary URL", maxLines = 1)
                        }
                    }

                    AnimatedVisibility(visible = showDualUrl) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = dualUrl,
                                onValueChange = { dualUrl = it },
                                placeholder = { Text("https://… (.srt / .vtt)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dual_subtitle_url_input")
                            )
                            Button(
                                onClick = {
                                    onLoadSecondarySubtitleUrl(dualUrl)
                                    dualUrl = ""
                                    showDualUrl = false
                                },
                                enabled = dualUrl.isNotBlank(),
                                modifier = Modifier.testTag("dual_subtitle_url_apply")
                            ) {
                                Text("Load")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleTrackRow(
    label: String,
    detail: String,
    badge: String?,
    isSelected: Boolean,
    isOffRow: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.tertiaryContainer else ObsidianSurfaceHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f) else ObsidianOutline.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when {
                    isSelected -> Icons.Rounded.CheckCircle
                    isOffRow -> Icons.Rounded.ClosedCaptionDisabled
                    else -> Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (badge != null) {
                SubtitlePillBadge(
                    text = badge,
                    accent = if (badge == "EXT") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

