package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.player.DownloadQualityOption
import com.example.player.OfflineDownloadManager
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SlateInkSecondary
import com.example.ui.theme.StreamCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualitySheet(
    title: String,
    isLoading: Boolean,
    options: List<DownloadQualityOption>,
    onSelectQuality: (DownloadQualityOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val distinctAudioTracks = remember(options) {
        options.filter { it.hasExplicitAudioChoice }.map { it.audioName }.distinct()
    }
    val hasMultiAudio = distinctAudioTracks.size >= 2
    var selectedAudioFilter by remember(options) { mutableStateOf<String?>(null) }

    val filteredOptions = remember(options, selectedAudioFilter) {
        if (selectedAudioFilter == null) {
            options
        } else {
            options.filter { it.audioName == selectedAudioFilter }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianSurface,
        modifier = Modifier.testTag("download_quality_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HighQuality,
                        contentDescription = null,
                        tint = CinemaAmber
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasMultiAudio) "Choose Quality & Audio" else "Choose Download Quality",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateInkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("download_quality_close_button")
                ) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close quality picker")
                }
            }

            if (isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = CinemaAmber,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp
                    )
                    Text(
                        text = "Checking qualities & audio tracks…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateInkSecondary
                    )
                }
            } else {
                if (hasMultiAudio) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Headphones,
                                contentDescription = null,
                                tint = StreamCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Audio Track (${distinctAudioTracks.size} available)",
                                style = MaterialTheme.typography.labelMedium,
                                color = SlateInkSecondary
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedAudioFilter == null,
                                onClick = { selectedAudioFilter = null },
                                label = { Text("All (${options.size})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CinemaAmber.copy(alpha = 0.22f),
                                    selectedLabelColor = CinemaAmber
                                ),
                                modifier = Modifier.testTag("download_audio_filter_all")
                            )
                            distinctAudioTracks.forEachIndexed { idx, audioLabel ->
                                val isChosen = selectedAudioFilter == audioLabel
                                FilterChip(
                                    selected = isChosen,
                                    onClick = {
                                        selectedAudioFilter = if (isChosen) null else audioLabel
                                    },
                                    label = { Text(audioLabel) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StreamCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = StreamCyan
                                    ),
                                    modifier = Modifier.testTag("download_audio_filter_$idx")
                                )
                            }
                        }
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    itemsIndexed(filteredOptions, key = { _, opt -> opt.id }) { idx, option ->
                        val accent = if (option.isRecommended) CinemaAmber else StreamCyan
                        val sizeBadge = OfflineDownloadManager.formatEstimatedSize(option.estimatedBytes)
                        val primaryTitle = if (option.hasExplicitAudioChoice) {
                            option.label.substringBefore(" · ")
                        } else {
                            option.label
                        }
                        Surface(
                            onClick = { onSelectQuality(option) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (option.isRecommended) {
                                CinemaAmber.copy(alpha = 0.12f)
                            } else {
                                ObsidianSurfaceHigh
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (option.isRecommended) CinemaAmber.copy(alpha = 0.65f) else ObsidianOutline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("download_quality_option_$idx")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = primaryTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (option.hasExplicitAudioChoice) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = StreamCyan.copy(alpha = 0.2f),
                                                border = BorderStroke(0.75.dp, StreamCyan.copy(alpha = 0.6f))
                                            ) {
                                                Text(
                                                    text = option.audioName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StreamCyan,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (option.isRecommended) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = CinemaAmber
                                            ) {
                                                Text(
                                                    text = "Recommended",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF0A0A0D),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = option.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateInkSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (sizeBadge.isNotBlank()) {
                                    Text(
                                        text = sizeBadge,
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = accent
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Download ${option.label}",
                                    tint = accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
