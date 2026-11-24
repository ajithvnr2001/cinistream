package com.example.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.remote.QrCodeMatrixGenerator
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OnCinemaAmber
import com.example.ui.theme.SlateInkSecondary
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.SubtitleEmerald
import com.example.viewmodel.WebRemoteUiState

@Composable
fun WebRemoteDialog(
    state: WebRemoteUiState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onExportSyncBackup: () -> Unit,
    onImportSyncBackup: () -> Unit,
    onDismiss: () -> Unit
) {
    DisposableEffect(Unit) {
        onStartServer()
        onDispose { onStopServer() }
    }

    val qrMatrix = remember(state.serverUrl) {
        if (state.serverUrl.isNotBlank()) QrCodeMatrixGenerator.generate(state.serverUrl) else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .testTag("web_remote_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianOutline)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = stringResource(R.string.cd_web_remote),
                            tint = CinemaAmber
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.web_remote_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.web_remote_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = SlateInkSecondary
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("web_remote_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close Web Remote"
                        )
                    }
                }

                if (state.errorMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = state.errorMessage,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else if (state.isRunning) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianSurfaceHigh, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (qrMatrix != null) {
                            QrCodeCanvas(
                                matrix = qrMatrix,
                                modifier = Modifier
                                    .size(144.dp)
                                    .testTag("web_remote_qr_canvas")
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "1. Open on Phone Browser",
                                style = MaterialTheme.typography.labelSmall,
                                color = SlateInkSecondary
                            )
                            Text(
                                text = state.serverUrl,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = StreamCyan,
                                modifier = Modifier.testTag("web_remote_url_text")
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "2. Enter 6-Digit Session PIN",
                                style = MaterialTheme.typography.labelSmall,
                                color = SlateInkSecondary
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CinemaAmber.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = state.pin,
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .testTag("web_remote_pin_text"),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = CinemaAmber
                                )
                            }
                        }
                    }

                    if (!state.lastReceivedAction.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SubtitleEmerald.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "Received: ${state.lastReceivedAction}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = SubtitleEmerald
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Starting local Wi-Fi server…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateInkSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(color = ObsidianOutline)

                Text(
                    text = stringResource(R.string.sync_backup_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.sync_backup_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateInkSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onExportSyncBackup,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sync_export_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CinemaAmber,
                            contentColor = OnCinemaAmber
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Export JSON")
                    }
                    OutlinedButton(
                        onClick = onImportSyncBackup,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sync_import_button"),
                        border = BorderStroke(1.dp, ObsidianOutline)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Import JSON")
                    }
                }
            }
        }
    }
}

@Composable
fun QrCodeCanvas(
    matrix: Array<BooleanArray>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val n = matrix.size
            if (n <= 0) return@Canvas
            val cellSize = minOf(size.width, size.height) / n.toFloat()
            val offsetX = (size.width - cellSize * n) / 2f
            val offsetY = (size.height - cellSize * n) / 2f
            for (y in 0 until n) {
                val row = matrix[y]
                for (x in 0 until n) {
                    if (row[x]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(offsetX + x * cellSize, offsetY + y * cellSize),
                            size = Size(cellSize + 0.4f, cellSize + 0.4f)
                        )
                    }
                }
            }
        }
    }
}
