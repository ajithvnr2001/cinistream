package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.ImdbBadgePill
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.viewmodel.JsonPlaylistUiState

@Composable
fun JsonPlaylistLauncherRow(
    state: JsonPlaylistUiState,
    onPickJsonFile: () -> Unit,
    onOpenJsonUrlDialog: () -> Unit,
    onOpenJsonSheet: () -> Unit,
    onRefreshJsonPlaylist: () -> Unit,
    onClearJsonPlaylist: () -> Unit
) {
    val hasItems = state.items.isNotEmpty()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurfaceHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (hasItems) CinemaAmber.copy(alpha = 0.55f) else ObsidianOutline.copy(alpha = 0.65f)
        )
    ) {
        if (state.isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = CinemaAmber
                )
                Text(
                    text = "Loading JSON playlist & IMDb info…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaAmber
                )
            }
        } else if (hasItems) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                    tint = CinemaAmber,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = state.sourceLabel ?: "JSON Catalog",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        ImdbBadgePill(rating = null, imdbId = "tt0000001")
                    }
                    Text(
                        text = "${state.items.size} titles loaded · Tap any poster below",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaAmber
                    )
                }
                TextButton(
                    onClick = onOpenJsonSheet,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                        .testTag("open_json_sheet_button")
                ) {
                    Text("Browse", color = CinemaAmber)
                }
                if (state.sourceUrl?.startsWith("http") == true) {
                    IconButton(
                        onClick = onRefreshJsonPlaylist,
                        modifier = Modifier
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("refresh_json_playlist_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reload JSON playlist URL",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onClearJsonPlaylist,
                    modifier = Modifier
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("clear_json_playlist_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideoLibrary,
                        contentDescription = null,
                        tint = CinemaAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "JSON Playlist (IMDb)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = onPickJsonFile,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                            .testTag("load_json_file_button")
                    ) {
                        Text("File", color = MaterialTheme.colorScheme.tertiary)
                    }
                    TextButton(
                        onClick = onOpenJsonUrlDialog,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                            .testTag("load_json_url_button")
                    ) {
                        Text("URL", color = CinemaAmber)
                    }
                }
            }
        }
    }
}

@Composable
fun JsonPlaylistUrlInputDialog(
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.load_json_playlist_url),
                    style = MaterialTheme.typography.titleLarge
                )
                ImdbBadgePill(rating = null, imdbId = "tt0000001")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Load any JSON playlist URL containing video links. Supports imdbId/imdbUrl, image/poster, subtitleUrl, year, rating, and category fields—missing posters & ratings are auto-fetched from IMDb.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.json_playlist_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_json_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CinemaAmber,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                    .testTag("dialog_json_url_confirm")
            ) {
                Text("Load Catalog")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusHighlight(shape = RoundedCornerShape(12.dp))
            ) {
                Text("Cancel")
            }
        }
    )
}
