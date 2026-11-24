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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ContinueWatchingHelper
import com.example.model.JsonPlaylistItem
import com.example.model.SeriesEpisode
import com.example.ui.components.FormatBadge
import com.example.ui.components.ImdbBadgePill
import com.example.ui.components.PosterImageThumbnail
import com.example.ui.components.SubtitlePillBadge
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SubtitleEmerald
import kotlinx.coroutines.delay

/**
 * TV D-Pad & Mobile friendly Seasons + Episodes picker sheet for Series items inside JSON playlists.
 * Can be opened from HomeScreen, JsonPlaylistSheet, or directly inside PlayerScreen while watching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailSheet(
    series: JsonPlaylistItem,
    activeEpisodeId: String?,
    episodeProgressMap: Map<String, Float> = emptyMap(),
    onDismiss: () -> Unit,
    onSelectEpisode: (JsonPlaylistItem, SeriesEpisode) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sortedSeasons = remember(series.seasons) { series.seasons.sortedBy { it.seasonNumber } }
    val initialSeasonNum = remember(series.id, activeEpisodeId, sortedSeasons) {
        val activeSeason = sortedSeasons.firstOrNull { season ->
            season.episodes.any { it.id == activeEpisodeId }
        }
        activeSeason?.seasonNumber ?: sortedSeasons.firstOrNull()?.seasonNumber ?: 1
    }

    var selectedSeasonNumber by rememberSaveable(series.id, initialSeasonNum) {
        mutableIntStateOf(initialSeasonNum)
    }
    val selectedSeason = sortedSeasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
        ?: sortedSeasons.firstOrNull()
    val episodes = remember(selectedSeason) {
        selectedSeason?.episodes?.sortedBy { it.episodeNumber }.orEmpty()
    }

    val initialEpisodeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectedSeasonNumber) {
        delay(120L)
        runCatching { initialEpisodeFocusRequester.requestFocus() }
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
                .padding(bottom = 26.dp)
                .testTag("series_detail_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Series Hero Header: Poster + Title + IMDb + Season/Episode counts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                PosterImageThumbnail(
                    imageUrl = series.imageUrl,
                    contentDescription = "${series.title} poster",
                    modifier = Modifier
                        .width(74.dp)
                        .height(106.dp)
                        .clip(RoundedCornerShape(12.dp))
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CinemaAmber.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tv,
                                    contentDescription = null,
                                    tint = CinemaAmber,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "SERIES · ${sortedSeasons.size}S · ${series.totalEpisodes} EP",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = CinemaAmber
                                )
                            }
                        }
                        if (!series.imdbRating.isNullOrBlank() || !series.imdbId.isNullOrBlank()) {
                            ImdbBadgePill(rating = series.imdbRating, imdbId = series.imdbId)
                        }
                    }

                    Text(
                        text = series.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val meta = listOfNotNull(
                        series.releaseYear?.takeIf { it.isNotBlank() },
                        series.groupTitle.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!series.description.isNullOrBlank()) {
                        Text(
                            text = series.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Season Tabs Row
            if (sortedSeasons.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sortedSeasons.forEach { season ->
                        val isSelected = season.seasonNumber == selectedSeason?.seasonNumber
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedSeasonNumber = season.seasonNumber },
                            label = { Text("${season.title} (${season.episodes.size})") },
                            modifier = Modifier
                                .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.04f)
                                .testTag("series_season_chip_${season.seasonNumber}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CinemaAmber.copy(alpha = 0.22f),
                                selectedLabelColor = CinemaAmber
                            )
                        )
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.55f))

            // Episodes List
            val focusIdx = episodes.indexOfFirst { it.id == activeEpisodeId }.takeIf { it >= 0 } ?: 0
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(episodes, key = { _, ep -> ep.id }) { idx, ep ->
                    val isActive = ep.id == activeEpisodeId
                    val progressFraction = episodeProgressMap[ep.id] ?: episodeProgressMap[ep.url] ?: 0f
                    EpisodeSelectionCard(
                        episode = ep,
                        isActive = isActive,
                        progressFraction = progressFraction,
                        onClick = {
                            onSelectEpisode(series, ep)
                            onDismiss()
                        },
                        modifier = if (idx == focusIdx) {
                            Modifier.focusRequester(initialEpisodeFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeSelectionCard(
    episode: SeriesEpisode,
    isActive: Boolean,
    progressFraction: Float = 0f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isWatched = progressFraction >= ContinueWatchingHelper.COMPLETED_FRACTION
    val showProgressBar = progressFraction >= ContinueWatchingHelper.MIN_RESUME_FRACTION && !isWatched

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusHighlight(shape = RoundedCornerShape(14.dp), focusedScale = 1.02f)
            .testTag("series_episode_${episode.id}"),
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) CinemaAmber.copy(alpha = 0.14f) else ObsidianSurfaceHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) CinemaAmber.copy(alpha = 0.65f) else ObsidianOutline.copy(alpha = 0.65f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Episode code badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isActive) CinemaAmber else Color.White.copy(alpha = 0.08f)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = episode.codeLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isActive) Color.Black else CinemaAmber
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isActive) CinemaAmber else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FormatBadge(format = episode.format)
                        if (isWatched) {
                            SubtitlePillBadge(
                                text = "Watched",
                                accent = SubtitleEmerald
                            )
                        }
                        if (!episode.durationLabel.isNullOrBlank()) {
                            Text(
                                text = episode.durationLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!episode.subtitleUrl.isNullOrBlank()) {
                            SubtitlePillBadge(text = "CC")
                        }
                        if (episode.mirrors.isNotEmpty()) {
                            SubtitlePillBadge(
                                text = "${episode.mirrors.size + 1} CDN",
                                accent = SubtitleEmerald
                            )
                        }
                    }

                    if (!episode.description.isNullOrBlank()) {
                        Text(
                            text = episode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                            contentDescription = "Play ${episode.codeLabel} ${episode.title}",
                            tint = if (isActive) MaterialTheme.colorScheme.onPrimary else CinemaAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (showProgressBar) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .testTag("series_episode_progress_${episode.id}"),
                    color = CinemaAmber,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
            }
        }
    }
}
