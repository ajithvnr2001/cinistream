package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.JsonPlaylistItem
import com.example.model.MediaContentType
import com.example.ui.components.FormatBadge
import com.example.ui.components.ImdbBadgePill
import com.example.ui.components.PosterImageThumbnail
import com.example.ui.components.SubtitlePillBadge
import com.example.ui.components.tvFocusHighlight
import com.example.ui.home.JsonPlaylistSwitcherBar
import com.example.ui.home.JsonPlaylistUrlInputDialog
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.SubtitleEmerald
import com.example.viewmodel.LoadedJsonPlaylist
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsonPlaylistSheet(
    sourceLabel: String?,
    items: List<JsonPlaylistItem>,
    activeItemId: String?,
    isEnrichingImdb: Boolean,
    canRefreshUrl: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelectItem: (JsonPlaylistItem) -> Unit,
    playlists: List<LoadedJsonPlaylist> = emptyList(),
    selectedPlaylistId: String? = null,
    onSelectPlaylist: (String?) -> Unit = {},
    onRemovePlaylist: ((String) -> Unit)? = null,
    onAddPlaylistUrl: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstItemFocusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var selectedTypeFilter by rememberSaveable { mutableStateOf("ALL") }
    var showAddUrlDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedPlaylistId) {
        selectedCategory = "All"
        selectedTypeFilter = "ALL"
    }

    val itemPlaylistLabelMap = remember(playlists, selectedPlaylistId) {
        if (playlists.size > 1 && selectedPlaylistId == null) {
            buildMap {
                playlists.forEach { pl ->
                    pl.items.forEach { put(it.id, pl.label) }
                }
            }
        } else {
            emptyMap()
        }
    }

    val hasMixedTypes = remember(items) {
        val typeCount = listOf(
            items.any { !it.isSeries && !it.isCollection },
            items.any { it.isSeries },
            items.any { it.isCollection }
        ).count { it }
        typeCount >= 2
    }

    LaunchedEffect(Unit) {
        delay(120L)
        runCatching { firstItemFocusRequester.requestFocus() }
    }

    val categories by remember(items) {
        derivedStateOf {
            val groups = items.map { it.groupTitle.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (groups.size > 1) listOf("All") + groups else emptyList()
        }
    }

    val filtered by remember(items, query, selectedCategory, selectedTypeFilter) {
        derivedStateOf {
            items.filter { item ->
                val matchesType = when (selectedTypeFilter) {
                    "MOVIES" -> item.contentType == MediaContentType.MOVIE
                    "COLLECTIONS" -> item.isCollection
                    "SERIES" -> item.isSeries
                    else -> true
                }
                val matchesCat = selectedCategory == "All" ||
                    item.groupTitle.equals(selectedCategory, ignoreCase = true)
                val matchesQuery = if (query.isBlank()) {
                    true
                } else {
                    val q = query.trim().lowercase()
                    item.title.lowercase().contains(q) ||
                        item.groupTitle.lowercase().contains(q) ||
                        (item.imdbId?.lowercase()?.contains(q) == true) ||
                        (item.releaseYear?.contains(q) == true) ||
                        (item.isCollection && item.collectionMovies.any { m ->
                            m.title.lowercase().contains(q) ||
                                (m.imdbId?.lowercase()?.contains(q) == true) ||
                                (m.releaseYear?.contains(q) == true)
                        })
                }
                matchesType && matchesCat && matchesQuery
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sourceLabel?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.section_json_playlist),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${filtered.size} titles · Click any poster to play",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (onAddPlaylistUrl != null) {
                        IconButton(
                            onClick = { showAddUrlDialog = true },
                            modifier = Modifier
                                .tvFocusHighlight(shape = CircleShape)
                                .testTag("json_sheet_add_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Add another JSON playlist URL",
                                tint = CinemaAmber
                            )
                        }
                    }
                    if (canRefreshUrl) {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .tvFocusHighlight(shape = CircleShape)
                                .testTag("json_sheet_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh JSON playlist",
                                tint = CinemaAmber
                            )
                        }
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                JsonPlaylistSwitcherBar(
                    playlists = playlists,
                    selectedPlaylistId = selectedPlaylistId,
                    onSelectPlaylist = onSelectPlaylist,
                    onRemovePlaylist = onRemovePlaylist,
                    testTagPrefix = "json_sheet_playlist_chip"
                )
            }

            if (isEnrichingImdb) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp),
                        color = CinemaAmber
                    )
                    Text(
                        text = "Fetching IMDb posters…",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaAmber
                    )
                }
            }

            if (items.size > 4) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search movies, series, IMDb IDs, genres…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("json_search_input"),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (hasMixedTypes) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val movieCount = remember(items) { items.count { !it.isSeries && !it.isCollection } }
                    val collectionCount = remember(items) { items.count { it.isCollection } }
                    val seriesCount = remember(items) { items.count { it.isSeries } }
                    val tabs = buildList {
                        add("ALL" to "All (${items.size})")
                        if (movieCount > 0) add("MOVIES" to "Movies ($movieCount)")
                        if (collectionCount > 0) add("COLLECTIONS" to "Collections ($collectionCount)")
                        if (seriesCount > 0) add("SERIES" to "Series ($seriesCount)")
                    }
                    tabs.forEach { (key, label) ->
                        FilterChip(
                            selected = selectedTypeFilter == key,
                            onClick = { selectedTypeFilter = key },
                            label = { Text(label) },
                            modifier = Modifier
                                .tvFocusHighlight(shape = RoundedCornerShape(8.dp))
                                .testTag("json_type_filter_${key.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (key == "COLLECTIONS") {
                                    StreamCyan.copy(alpha = 0.24f)
                                } else {
                                    CinemaAmber.copy(alpha = 0.24f)
                                },
                                selectedLabelColor = if (key == "COLLECTIONS") StreamCyan else CinemaAmber
                            )
                        )
                    }
                }
            }

            if (categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = selectedCategory == cat
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) },
                            modifier = Modifier.tvFocusHighlight(shape = RoundedCornerShape(8.dp)),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CinemaAmber.copy(alpha = 0.2f),
                                selectedLabelColor = CinemaAmber
                            )
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(filtered, key = { _, item -> item.id }) { idx, item ->
                    val isActive = item.id == activeItemId
                    val sourcePlaylistBadge = itemPlaylistLabelMap[item.id]
                    Surface(
                        onClick = {
                            onSelectItem(item)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (idx == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                            )
                            .tvFocusHighlight(shape = RoundedCornerShape(14.dp), focusedScale = 1.02f)
                            .testTag("json_entry_${item.id}"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isActive) CinemaAmber.copy(alpha = 0.14f) else ObsidianSurfaceHigh,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isActive) CinemaAmber.copy(alpha = 0.6f) else ObsidianOutline.copy(alpha = 0.65f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PosterImageThumbnail(
                                imageUrl = item.imageUrl,
                                contentDescription = "${item.title} poster",
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(92.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isActive) CinemaAmber else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (!item.imdbRating.isNullOrBlank() || !item.imdbId.isNullOrBlank()) {
                                        ImdbBadgePill(rating = item.imdbRating, imdbId = item.imdbId)
                                    }
                                    if (!item.releaseYear.isNullOrBlank()) {
                                        Text(
                                            text = item.releaseYear,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (item.isSeries) {
                                        SubtitlePillBadge(
                                            text = "SERIES · ${item.seasons.size}S · ${item.totalEpisodes} EP",
                                            accent = CinemaAmber
                                        )
                                    } else if (item.isCollection) {
                                        SubtitlePillBadge(
                                            text = if (item.totalCollectionMovies == 1) {
                                                "COLLECTION · 1 FILM"
                                            } else {
                                                "COLLECTION · ${item.totalCollectionMovies} FILMS"
                                            },
                                            accent = StreamCyan
                                        )
                                    } else {
                                        FormatBadge(format = item.format)
                                        if (item.mirrors.isNotEmpty()) {
                                            SubtitlePillBadge(
                                                text = "${item.mirrors.size + 1} CDN",
                                                accent = SubtitleEmerald
                                            )
                                        }
                                    }
                                    if (!item.subtitleUrl.isNullOrBlank()) {
                                        SubtitlePillBadge(text = "CC")
                                    }
                                    if (item.externalAudios.isNotEmpty() && !item.isCollection && !item.isSeries) {
                                        SubtitlePillBadge(
                                            text = if (item.externalAudios.size == 1) "AUDIO" else "${item.externalAudios.size} AUDIO",
                                            accent = StreamCyan
                                        )
                                    }
                                    if (!sourcePlaylistBadge.isNullOrBlank()) {
                                        SubtitlePillBadge(
                                            text = sourcePlaylistBadge.take(14),
                                            accent = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }

                                val summary = item.description?.takeIf { it.isNotBlank() } ?: item.groupTitle
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(Modifier.width(2.dp))
                            Surface(
                                shape = CircleShape,
                                color = if (isActive) CinemaAmber else CinemaAmber.copy(alpha = 0.16f)
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Play ${item.title}",
                                        tint = if (isActive) MaterialTheme.colorScheme.onPrimary else CinemaAmber,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddUrlDialog && onAddPlaylistUrl != null) {
        JsonPlaylistUrlInputDialog(
            initialUrl = "",
            existingPlaylistCount = playlists.size,
            onDismiss = { showAddUrlDialog = false },
            onConfirm = { url ->
                onAddPlaylistUrl(url)
                showAddUrlDialog = false
            }
        )
    }
}
