package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.OfflineDownloadEntity
import com.example.player.OfflineDownloadManager
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SignalRose
import com.example.ui.theme.SlateInkSecondary
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.SubtitleEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(
    downloads: List<OfflineDownloadEntity>,
    onPlayDownload: (OfflineDownloadEntity) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onDeleteDownload: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianSurface,
        modifier = Modifier.testTag("downloads_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.cd_offline_downloads),
                        tint = CinemaAmber
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.offline_downloads_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${downloads.size} saved in private app storage",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateInkSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("downloads_close_button")
                ) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close Downloads")
                }
            }

            Spacer(Modifier.height(12.dp))

            if (downloads.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = ObsidianSurfaceHigh,
                    border = BorderStroke(1.dp, ObsidianOutline)
                ) {
                    Text(
                        text = stringResource(R.string.offline_downloads_empty),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateInkSecondary
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(downloads, key = { it.id }) { item ->
                        OfflineDownloadRow(
                            item = item,
                            onPlay = { onPlayDownload(item) },
                            onCancel = { onCancelDownload(item.id) },
                            onDelete = { onDeleteDownload(item.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OfflineDownloadRow(
    item: OfflineDownloadEntity,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val isDownloading = item.status == OfflineDownloadManager.STATUS_DOWNLOADING ||
        item.status == OfflineDownloadManager.STATUS_QUEUED
    val isCompleted = item.status == OfflineDownloadManager.STATUS_COMPLETED
    val statusColor = when (item.status) {
        OfflineDownloadManager.STATUS_COMPLETED -> SubtitleEmerald
        OfflineDownloadManager.STATUS_FAILED -> SignalRose
        OfflineDownloadManager.STATUS_DOWNLOADING -> StreamCyan
        else -> SlateInkSecondary
    }
    val sizeLabel = formatBytesCompact(item.bytesDownloaded.coerceAtLeast(item.totalBytes))

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurfaceHigh,
        border = BorderStroke(1.dp, ObsidianOutline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_item_${item.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(item.status)
                            if (isDownloading && item.progressPercent > 0) append(" · ${item.progressPercent}%")
                            if (sizeLabel.isNotBlank()) append(" · $sizeLabel")
                            if (!item.errorMessage.isNullOrBlank()) append(" · ${item.errorMessage}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isCompleted) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier.testTag("download_play_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play offline download",
                            tint = CinemaAmber
                        )
                    }
                }
                if (isDownloading) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("download_cancel_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel download",
                            tint = SignalRose
                        )
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("download_delete_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete offline download",
                        tint = SlateInkSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (isDownloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = StreamCyan
                )
            }
        }
    }
}

private fun formatBytesCompact(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(java.util.Locale.US, "%.1f MB", mb)
    }
}
