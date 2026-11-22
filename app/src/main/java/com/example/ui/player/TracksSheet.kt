package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.TrackOption
import com.example.player.DecoderFallbackHelper
import com.example.player.NetworkTransportMode
import com.example.player.PlayerStatsFormatter
import com.example.player.VideoDecoderMode
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SubtitleEmerald
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksSheet(
    videoTracks: List<TrackOption>,
    audioTracks: List<TrackOption>,
    volumePercent: Int = 100,
    onSetVolumePercent: (Int) -> Unit = {},
    statsForNerdsEnabled: Boolean = false,
    onToggleStatsForNerds: () -> Unit = {},
    activeCdnIndex: Int = 1,
    totalCdnNodes: Int = 1,
    activeCdnHost: String = "",
    onSwitchCdnMirror: () -> Unit = {},
    diskCacheUsedBytes: Long = 0L,
    diskCacheMaxBytes: Long = 0L,
    onClearEdgeCache: () -> Unit = {},
    preferredDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    effectiveDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    activeVideoDecoderName: String = "",
    onSelectDecoderMode: (VideoDecoderMode) -> Unit = {},
    preferredTransportMode: NetworkTransportMode = NetworkTransportMode.AUTO,
    activeTransportLabel: String = "",
    onSelectTransportMode: (NetworkTransportMode) -> Unit = {},
    onDismiss: () -> Unit,
    onSelectVideoTrack: (TrackOption?) -> Unit,
    onSelectAudioTrack: (TrackOption) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstTrackFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(120L)
        runCatching { firstTrackFocusRequester.requestFocus() }
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
            Text(
                text = stringResource(R.string.tracks_sheet_title),
                style = MaterialTheme.typography.headlineSmall
            )

            VolumeBoostControlSection(
                volumePercent = volumePercent,
                onSetVolumePercent = onSetVolumePercent
            )

            // YouTube-style Stats for Nerds Toggle
            Surface(
                onClick = onToggleStatsForNerds,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstTrackFocusRequester)
                    .tvFocusHighlight(shape = RoundedCornerShape(14.dp), focusedScale = 1.02f)
                    .testTag("tracks_stats_for_nerds_toggle"),
                shape = RoundedCornerShape(14.dp),
                color = if (statsForNerdsEnabled) CinemaAmber.copy(alpha = 0.14f) else ObsidianSurfaceHigh,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (statsForNerdsEnabled) CinemaAmber.copy(alpha = 0.65f) else ObsidianOutline.copy(alpha = 0.6f)
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
                        imageVector = Icons.Rounded.NetworkCheck,
                        contentDescription = null,
                        tint = if (statsForNerdsEnabled) CinemaAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.stats_for_nerds_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.stats_for_nerds_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (statsForNerdsEnabled) CinemaAmber else ObsidianOutline.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text = if (statsForNerdsEnabled) "ON" else "OFF",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (statsForNerdsEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Multi-CDN Mirror Switcher (shown whenever backup mirrors exist for the playing stream)
            if (totalCdnNodes > 1) {
                Surface(
                    onClick = onSwitchCdnMirror,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(shape = RoundedCornerShape(14.dp), focusedScale = 1.02f)
                        .testTag("tracks_switch_cdn_button"),
                    shape = RoundedCornerShape(14.dp),
                    color = ObsidianSurfaceHigh,
                    border = BorderStroke(1.dp, SubtitleEmerald.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = SubtitleEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${stringResource(R.string.cdn_mirror_title)} ($activeCdnIndex/$totalCdnNodes)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = activeCdnHost.ifBlank { "Active mirror" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SubtitleEmerald.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Switch",
                                style = MaterialTheme.typography.labelMedium,
                                color = SubtitleEmerald,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Local Edge Disk Cache status & optional clear action
            if (diskCacheMaxBytes > 0L) {
                Surface(
                    onClick = onClearEdgeCache,
                    enabled = diskCacheUsedBytes > 0L,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(shape = RoundedCornerShape(14.dp), focusedScale = 1.02f)
                        .testTag("tracks_clear_edge_cache_button"),
                    shape = RoundedCornerShape(14.dp),
                    color = ObsidianSurfaceHigh,
                    border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.edge_cache_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${PlayerStatsFormatter.formatBytes(diskCacheUsedBytes)} / ${PlayerStatsFormatter.formatBytes(diskCacheMaxBytes)} cached on device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (diskCacheUsedBytes > 0L) {
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelMedium,
                                color = CinemaAmber
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.6f))

            // Video Quality / HLS Renditions
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HighQuality,
                        contentDescription = null,
                        tint = CinemaAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Video Quality",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TrackSelectionRow(
                    label = "Auto (Adaptive)",
                    detail = "Automatically selects optimal bitrate for HLS / DASH",
                    isSelected = videoTracks.none { it.isSelected } || videoTracks.count { it.isSelected } > 1,
                    leadingAutoIcon = true,
                    onClick = { onSelectVideoTrack(null) },
                    testTag = "video_track_auto"
                )

                videoTracks.forEachIndexed { idx, track ->
                    TrackSelectionRow(
                        label = track.label,
                        detail = track.detail,
                        isSelected = track.isSelected && videoTracks.count { it.isSelected } == 1,
                        onClick = { onSelectVideoTrack(track) },
                        testTag = "video_track_$idx"
                    )
                }
            }

            // Video Decoder Engine selector (Auto HW+SW / Hardware / Software)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurfaceHigh,
                border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = CinemaAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.decoder_engine_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val activeTag = DecoderFallbackHelper.formatDecoderTag(activeVideoDecoderName)
                            val subtitleText = if (activeTag != "—") {
                                "Active: $activeTag"
                            } else {
                                effectiveDecoderMode.description
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoDecoderMode.entries.forEach { mode ->
                            val isSelected = preferredDecoderMode == mode
                            val accent = if (mode == VideoDecoderMode.SOFTWARE) SubtitleEmerald else CinemaAmber
                            Surface(
                                onClick = { onSelectDecoderMode(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                                    .testTag("tracks_decoder_mode_${mode.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) accent.copy(alpha = 0.22f) else ObsidianSurface,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) accent else ObsidianOutline.copy(alpha = 0.65f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = mode.shortLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when (mode) {
                                            VideoDecoderMode.AUTO -> "HW→SW"
                                            VideoDecoderMode.HARDWARE -> "GPU/DSP"
                                            VideoDecoderMode.SOFTWARE -> "CPU Safe"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Network Engine & Parallel Prefetch selector (Auto HTTP/3->HTTP/2, HTTP/2 TCP, HTTP/3 QUIC)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurfaceHigh,
                border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NetworkCheck,
                            contentDescription = null,
                            tint = SubtitleEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.network_engine_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val subtitleText = if (activeTransportLabel.isNotBlank()) {
                                "Active: $activeTransportLabel · Parallel Prefetch ON"
                            } else {
                                preferredTransportMode.description
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkTransportMode.entries.forEach { mode ->
                            val isSelected = preferredTransportMode == mode
                            val accent = if (mode == NetworkTransportMode.HTTP2_TCP) CinemaAmber else SubtitleEmerald
                            Surface(
                                onClick = { onSelectTransportMode(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                                    .testTag("tracks_transport_mode_${mode.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) accent.copy(alpha = 0.22f) else ObsidianSurface,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) accent else ObsidianOutline.copy(alpha = 0.65f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = mode.shortLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when (mode) {
                                            NetworkTransportMode.AUTO -> "H3→H2"
                                            NetworkTransportMode.HTTP2_TCP -> "TCP Safe"
                                            NetworkTransportMode.HTTP3_QUIC -> "QUIC"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.6f))

            // Audio Tracks
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Audio Tracks (${audioTracks.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (audioTracks.isEmpty()) {
                    Text(
                        text = "Default audio stream active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    audioTracks.forEachIndexed { idx, track ->
                        TrackSelectionRow(
                            label = track.label,
                            detail = track.detail,
                            isSelected = track.isSelected,
                            onClick = { onSelectAudioTrack(track) },
                            testTag = "audio_track_$idx"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackSelectionRow(
    label: String,
    detail: String,
    isSelected: Boolean,
    leadingAutoIcon: Boolean = false,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusHighlight(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) CinemaAmber.copy(alpha = 0.14f) else ObsidianSurfaceHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) CinemaAmber.copy(alpha = 0.55f) else ObsidianOutline.copy(alpha = 0.6f)
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
                    leadingAutoIcon -> Icons.Rounded.AutoAwesome
                    else -> Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (isSelected) CinemaAmber else MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
    }
}
