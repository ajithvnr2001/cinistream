package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ChannelEpgNowNext
import com.example.model.M3uEntry
import com.example.ui.components.FormatBadge
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SignalRose
import com.example.ui.theme.StreamCyan
import com.example.viewmodel.EpgUiState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3uPlaylistSheet(
    entries: List<M3uEntry>,
    activeEntryId: String?,
    onDismiss: () -> Unit,
    onSelectEntry: (M3uEntry) -> Unit,
    epgState: EpgUiState = EpgUiState(),
    onLoadEpgUrl: (String) -> Unit = {},
    resolveChannelEpg: (M3uEntry) -> ChannelEpgNowNext? = { null },
    favoriteMediaKeys: Set<String> = emptySet(),
    onToggleFavoriteEntry: (M3uEntry) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstEntryFocusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var showEpgInput by rememberSaveable { mutableStateOf(false) }
    var epgUrlInput by rememberSaveable(epgState.epgUrl) { mutableStateOf(epgState.epgUrl) }

    LaunchedEffect(Unit) {
        delay(120L)
        runCatching { firstEntryFocusRequester.requestFocus() }
    }

    val filtered by remember(entries, query) {
        derivedStateOf {
            if (query.isBlank()) entries
            else {
                val q = query.trim().lowercase()
                entries.filter {
                    it.title.lowercase().contains(q) || it.groupTitle.lowercase().contains(q)
                }
            }
        }
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.section_m3u_playlist),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = buildString {
                            append("${filtered.size} streams")
                            if (epgState.loadedProgramCount > 0) {
                                append(" · EPG: ${epgState.loadedProgramCount} programs")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = { showEpgInput = !showEpgInput },
                    modifier = Modifier.testTag("m3u_epg_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LiveTv,
                        contentDescription = null,
                        tint = StreamCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = " EPG Guide",
                        color = StreamCyan
                    )
                }
            }

            if (showEpgInput) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = epgUrlInput,
                        onValueChange = { epgUrlInput = it },
                        placeholder = { Text("https://…/epg.xml or .xml.gz") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("m3u_epg_url_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = { onLoadEpgUrl(epgUrlInput) },
                        enabled = epgUrlInput.isNotBlank() && !epgState.isLoading,
                        modifier = Modifier.testTag("m3u_epg_load_button")
                    ) {
                        if (epgState.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Load")
                        }
                    }
                }
                epgState.errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (entries.size > 5) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search channels or groups…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("m3u_search_input"),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filtered, key = { _, item -> item.id }) { idx, entry ->
                    val isActive = entry.id == activeEntryId
                    val epgNowNext = resolveChannelEpg(entry)
                    val isFav = entry.url in favoriteMediaKeys
                    Surface(
                        onClick = {
                            onSelectEntry(entry)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (idx == 0) Modifier.focusRequester(firstEntryFocusRequester) else Modifier
                            )
                            .tvFocusHighlight(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f)
                            .testTag("m3u_entry_${entry.id}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isActive) CinemaAmber.copy(alpha = 0.14f) else ObsidianSurfaceHigh,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isActive) CinemaAmber.copy(alpha = 0.55f) else ObsidianOutline.copy(alpha = 0.6f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isActive) CinemaAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = entry.groupTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onToggleFavoriteEntry(entry) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = stringResource(R.string.cd_favorite_toggle),
                                        tint = if (isFav) SignalRose else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                FormatBadge(format = entry.format)
                            }
                            if (epgNowNext?.now != null) {
                                Text(
                                    text = buildString {
                                        append("NOW: ${epgNowNext.now.title}")
                                        epgNowNext.next?.let { append("  ·  NEXT: ${it.title}") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StreamCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                LinearProgressIndicator(
                                    progress = { epgNowNext.now.progressFraction(System.currentTimeMillis()) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = CinemaAmber
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
