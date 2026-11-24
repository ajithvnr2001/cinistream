package com.example.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.viewmodel.JsonPlaylistUiState
import com.example.viewmodel.LoadedJsonPlaylist

@Composable
fun JsonPlaylistLauncherRow(
    state: JsonPlaylistUiState,
    onPickJsonFile: () -> Unit,
    onOpenJsonUrlDialog: () -> Unit,
    onOpenJsonSheet: () -> Unit,
    onRefreshJsonPlaylist: () -> Unit,
    onClearJsonPlaylist: () -> Unit
) {
    val allItems = state.allItems
    val hasItems = allItems.isNotEmpty()
    val playlistCount = state.playlists.size.coerceAtLeast(if (hasItems) 1 else 0)

    if (state.isLoading && !hasItems) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = CinemaAmber
            )
            Text(
                text = "Loading catalog…",
                style = MaterialTheme.typography.bodyMedium,
                color = CinemaAmber
            )
        }
    } else if (hasItems) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = CinemaAmber
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                    tint = CinemaAmber,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (playlistCount > 1) {
                            "$playlistCount Catalogs"
                        } else {
                            state.sourceLabel ?: "JSON Catalog"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    ImdbBadgePill(rating = null, imdbId = "tt0000001")
                }
                Text(
                    text = "${allItems.size} titles",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onOpenJsonUrlDialog,
                modifier = Modifier
                    .size(34.dp)
                    .tvFocusHighlight(shape = CircleShape)
                    .testTag("add_json_url_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add another JSON playlist URL",
                    tint = CinemaAmber,
                    modifier = Modifier.size(18.dp)
                )
            }
            TextButton(
                onClick = onOpenJsonSheet,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .tvFocusHighlight(shape = RoundedCornerShape(8.dp))
                    .testTag("open_json_sheet_button")
            ) {
                Text("Browse", color = CinemaAmber)
            }
            if (state.canRefreshRemote) {
                IconButton(
                    onClick = onRefreshJsonPlaylist,
                    modifier = Modifier
                        .size(34.dp)
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("refresh_json_playlist_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reload JSON playlists",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            IconButton(
                onClick = onClearJsonPlaylist,
                modifier = Modifier
                    .size(34.dp)
                    .tvFocusHighlight(shape = CircleShape)
                    .testTag("clear_json_playlist_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = "JSON Catalog",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onPickJsonFile,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .tvFocusHighlight(shape = RoundedCornerShape(8.dp))
                        .testTag("load_json_file_button")
                ) {
                    Text("File", color = MaterialTheme.colorScheme.tertiary)
                }
                TextButton(
                    onClick = onOpenJsonUrlDialog,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .tvFocusHighlight(shape = RoundedCornerShape(8.dp))
                        .testTag("load_json_url_button")
                ) {
                    Text("URL", color = CinemaAmber)
                }
            }
        }
    }
}

@Composable
fun JsonPlaylistSwitcherBar(
    playlists: List<LoadedJsonPlaylist>,
    selectedPlaylistId: String?,
    onSelectPlaylist: (String?) -> Unit,
    onRemovePlaylist: ((String) -> Unit)? = null,
    testTagPrefix: String = "playlist_chip",
    modifier: Modifier = Modifier
) {
    if (playlists.isEmpty()) return
    val totalTitles = playlists.sumOf { it.items.size }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (playlists.size > 1) {
            val allSelected = selectedPlaylistId == null
            FilterChip(
                selected = allSelected,
                onClick = { onSelectPlaylist(null) },
                label = { Text("All ($totalTitles)", style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .tvFocusHighlight(shape = RoundedCornerShape(8.dp))
                    .testTag("${testTagPrefix}_all"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CinemaAmber.copy(alpha = 0.18f),
                    selectedLabelColor = CinemaAmber
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = allSelected,
                    borderColor = ObsidianOutline,
                    selectedBorderColor = CinemaAmber.copy(alpha = 0.65f)
                )
            )
        }
        playlists.forEach { playlist ->
            val isSelected = selectedPlaylistId == playlist.id
            FilterChip(
                selected = isSelected,
                onClick = { onSelectPlaylist(playlist.id) },
                label = {
                    Text(
                        text = "${playlist.label} (${playlist.items.size})",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .tvFocusHighlight(shape = RoundedCornerShape(8.dp))
                    .testTag("${testTagPrefix}_${playlist.id}"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CinemaAmber.copy(alpha = 0.18f),
                    selectedLabelColor = CinemaAmber
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = ObsidianOutline,
                    selectedBorderColor = CinemaAmber.copy(alpha = 0.65f)
                )
            )
        }
        if (selectedPlaylistId != null && onRemovePlaylist != null) {
            IconButton(
                onClick = { onRemovePlaylist(selectedPlaylistId) },
                modifier = Modifier
                    .tvFocusHighlight(shape = CircleShape)
                    .testTag("${testTagPrefix}_remove_selected")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Remove selected JSON playlist",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun JsonPlaylistUrlInputDialog(
    initialUrl: String = "",
    existingPlaylistCount: Int = 0,
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
                    text = if (existingPlaylistCount > 0) {
                        "Add JSON Catalog"
                    } else {
                        stringResource(R.string.load_json_playlist_url)
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
                ImdbBadgePill(rating = null, imdbId = "tt0000001")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (existingPlaylistCount > 0) {
                        "Add another JSON catalog URL. Saved lists reload on launch."
                    } else {
                        "Paste a JSON catalog URL for movies or series. IMDb metadata and mirrors load automatically."
                    },
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
                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                    .testTag("dialog_json_url_confirm")
            ) {
                Text(if (existingPlaylistCount > 0) "Add" else "Load")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusHighlight(shape = RoundedCornerShape(10.dp))
            ) {
                Text("Cancel")
            }
        }
    )
}
