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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksSheet(
    videoTracks: List<TrackOption>,
    audioTracks: List<TrackOption>,
    volumePercent: Int = 100,
    onSetVolumePercent: (Int) -> Unit = {},
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
                    testTag = "video_track_auto",
                    modifier = Modifier.focusRequester(firstTrackFocusRequester)
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
