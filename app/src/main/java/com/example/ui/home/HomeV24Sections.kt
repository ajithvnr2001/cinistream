package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.FavoriteItemEntity
import com.example.model.GlobalSearchResult
import com.example.ui.components.PosterImageThumbnail
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianOutlineSubtle
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SignalRose
import com.example.ui.theme.SlateInkSecondary
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.SubtitleEmerald

@Composable
fun HomeQuickToolsBar(
    isTvDevice: Boolean,
    downloadsCount: Int,
    serversCount: Int,
    onOpenWebRemote: () -> Unit,
    onOpenLocalNetwork: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_quick_tools_bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        item {
            QuickToolPill(
                icon = Icons.Rounded.QrCode2,
                title = stringResource(R.string.tool_web_remote),
                meta = "Wi-Fi",
                tint = CinemaAmber,
                testTag = "tool_web_remote_button",
                onClick = onOpenWebRemote
            )
        }
        item {
            QuickToolPill(
                icon = Icons.Rounded.FolderShared,
                title = stringResource(R.string.tool_local_network),
                meta = if (serversCount > 0) "$serversCount" else null,
                tint = StreamCyan,
                testTag = "tool_local_network_button",
                onClick = onOpenLocalNetwork
            )
        }
        if (!isTvDevice) {
            item {
                QuickToolPill(
                    icon = Icons.Rounded.Download,
                    title = stringResource(R.string.tool_offline_downloads),
                    meta = if (downloadsCount > 0) "$downloadsCount" else null,
                    tint = SubtitleEmerald,
                    testTag = "tool_downloads_button",
                    onClick = onOpenDownloads
                )
            }
        }
    }
}

@Composable
private fun QuickToolPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    meta: String?,
    tint: androidx.compose.ui.graphics.Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(0.75.dp, ObsidianOutline.copy(alpha = 0.85f)),
        modifier = Modifier
            .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!meta.isNullOrBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateInkSecondary
                )
            }
        }
    }
}

@Composable
fun GlobalSearchBarAndResults(
    query: String,
    results: List<GlobalSearchResult>,
    onQueryChange: (String) -> Unit,
    onResultClick: (GlobalSearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.global_search_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlateInkSecondary
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.cd_global_search),
                    tint = SlateInkSecondary,
                    modifier = Modifier.size(19.dp)
                )
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = SlateInkSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ObsidianSurface,
                unfocusedContainerColor = ObsidianSurface,
                focusedBorderColor = CinemaAmber.copy(alpha = 0.7f),
                unfocusedBorderColor = ObsidianOutline.copy(alpha = 0.65f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("global_search_input")
        )

        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                Text(
                    text = "No matching titles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateInkSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(0.75.dp, ObsidianOutline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("global_search_results")
                ) {
                    Column {
                        results.take(8).forEachIndexed { index, result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onResultClick(result) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("global_search_result_${result.id.hashCode()}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = CinemaAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${result.category.label} · ${result.subtitle}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateInkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (index < results.take(8).lastIndex) {
                                HorizontalDivider(
                                    color = ObsidianOutlineSubtle,
                                    thickness = 0.75.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MyWatchlistSection(
    favorites: List<FavoriteItemEntity>,
    onPlayFavorite: (FavoriteItemEntity) -> Unit,
    onRemoveFavorite: (FavoriteItemEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (favorites.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("my_watchlist_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.my_watchlist_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${favorites.size}",
                style = MaterialTheme.typography.labelMedium,
                color = SlateInkSecondary
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(favorites, key = { it.mediaKey }) { fav ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(0.75.dp, ObsidianOutline.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .width(232.dp)
                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                        .clickable { onPlayFavorite(fav) }
                        .testTag("watchlist_card_${fav.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PosterImageThumbnail(
                            imageUrl = fav.posterUrl,
                            contentDescription = "${fav.title} poster",
                            decodeWidthPx = 120,
                            decodeHeightPx = 180,
                            modifier = Modifier
                                .width(46.dp)
                                .height(66.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = fav.itemType.replace('_', ' '),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaAmber,
                                    fontWeight = FontWeight.Medium
                                )
                                IconButton(
                                    onClick = { onRemoveFavorite(fav) },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .tvFocusHighlight(shape = CircleShape)
                                        .testTag("watchlist_remove_${fav.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Favorite,
                                        contentDescription = stringResource(R.string.cd_toggle_favorite),
                                        tint = SignalRose,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = fav.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val meta = listOfNotNull(
                                fav.releaseYear,
                                fav.imdbRating?.let { "★ $it" },
                                fav.groupTitle
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SlateInkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
