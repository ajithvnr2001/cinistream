package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.example.model.CollectionMovie
import com.example.model.JsonPlaylistItem
import com.example.ui.components.FormatBadge
import com.example.ui.components.ImdbBadgePill
import com.example.ui.components.PosterImageThumbnail
import com.example.ui.components.SubtitlePillBadge
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.SubtitleEmerald
import kotlinx.coroutines.delay

/**
 * Movie Collection / Franchise detail sheet (e.g. Resident Evil, Final Destination, Evil Dead).
 * Displays each film in release/saga order with its own auto-fetched IMDb poster, rating, year,
 * watched/resume progress, and optional offline download—with zero TV S/E episode labels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailSheet(
    collection: JsonPlaylistItem,
    activeMovieId: String? = null,
    movieProgressMap: Map<String, Float> = emptyMap(),
    isEnrichingImdb: Boolean = false,
    onDismiss: () -> Unit,
    onSelectMovie: (JsonPlaylistItem, CollectionMovie) -> Unit,
    onDownloadMovie: ((JsonPlaylistItem, CollectionMovie) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val orderedMovies = remember(collection.collectionMovies) { collection.orderedCollectionMovies() }
    val watchedCount = remember(orderedMovies, movieProgressMap) {
        orderedMovies.count { movie ->
            val progress = movieProgressMap[movie.id] ?: movieProgressMap[movie.url] ?: 0f
            progress >= ContinueWatchingHelper.COMPLETED_FRACTION
        }
    }
    val nextMovieToPlay = remember(orderedMovies, activeMovieId, movieProgressMap) {
        orderedMovies.firstOrNull { it.id == activeMovieId }
            ?: orderedMovies.firstOrNull { movie ->
                val p = movieProgressMap[movie.id] ?: movieProgressMap[movie.url] ?: 0f
                p >= ContinueWatchingHelper.MIN_RESUME_FRACTION && p < ContinueWatchingHelper.COMPLETED_FRACTION
            }
            ?: orderedMovies.firstOrNull { movie ->
                val p = movieProgressMap[movie.id] ?: movieProgressMap[movie.url] ?: 0f
                p < ContinueWatchingHelper.COMPLETED_FRACTION
            }
            ?: orderedMovies.firstOrNull()
    }
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(collection.id) {
        delay(120L)
        runCatching { primaryFocusRequester.requestFocus() }
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
                .testTag("collection_detail_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Collection Hero Header with layered stacked poster frame
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(84.dp)
                        .height(114.dp)
                ) {
                    // Subtle stacked backplate cards evoke a multi-film anthology box set
                    Surface(
                        modifier = Modifier
                            .width(72.dp)
                            .height(104.dp)
                            .offset(x = 10.dp, y = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = ObsidianSurfaceHigh,
                        border = BorderStroke(0.75.dp, ObsidianOutline.copy(alpha = 0.55f))
                    ) {}
                    Surface(
                        modifier = Modifier
                            .width(74.dp)
                            .height(106.dp)
                            .offset(x = 5.dp, y = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = ObsidianSurfaceHigh.copy(alpha = 0.9f),
                        border = BorderStroke(0.75.dp, StreamCyan.copy(alpha = 0.35f))
                    ) {}
                    PosterImageThumbnail(
                        imageUrl = collection.imageUrl ?: orderedMovies.firstNotNullOfOrNull { it.imageUrl },
                        contentDescription = "${collection.title} collection poster",
                        modifier = Modifier
                            .width(74.dp)
                            .height(106.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

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
                            color = StreamCyan.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, StreamCyan.copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Movie,
                                    contentDescription = null,
                                    tint = StreamCyan,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (orderedMovies.size == 1) {
                                        "COLLECTION · 1 FILM"
                                    } else {
                                        "COLLECTION · ${orderedMovies.size} FILMS"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = StreamCyan
                                )
                            }
                        }
                        if (!collection.imdbRating.isNullOrBlank()) {
                            ImdbBadgePill(rating = collection.imdbRating, imdbId = collection.imdbId)
                        }
                    }

                    Text(
                        text = collection.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val meta = listOfNotNull(
                        collection.releaseYear?.takeIf { it.isNotBlank() },
                        collection.groupTitle.takeIf { it.isNotBlank() },
                        if (watchedCount > 0) "$watchedCount/${orderedMovies.size} watched" else null
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

                    if (!collection.description.isNullOrBlank()) {
                        Text(
                            text = collection.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                        text = "Fetching IMDb posters & ratings…",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaAmber
                    )
                }
            }

            if (nextMovieToPlay != null) {
                val nextProgress = movieProgressMap[nextMovieToPlay.id]
                    ?: movieProgressMap[nextMovieToPlay.url] ?: 0f
                val isResuming = nextProgress >= ContinueWatchingHelper.MIN_RESUME_FRACTION &&
                    nextProgress < ContinueWatchingHelper.COMPLETED_FRACTION
                val ctaLabel = when {
                    isResuming -> "Resume ${nextMovieToPlay.title}"
                    watchedCount in 1 until orderedMovies.size -> "Play Next · ${nextMovieToPlay.title}"
                    else -> "Play ${nextMovieToPlay.title}"
                }
                Button(
                    onClick = {
                        onSelectMovie(collection, nextMovieToPlay)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f)
                        .testTag("collection_play_next_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CinemaAmber,
                        contentColor = Color(0xFF0A0A0D)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = ctaLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.55f))

            val focusIdx = orderedMovies.indexOfFirst { it.id == nextMovieToPlay?.id }.takeIf { it >= 0 } ?: 0
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(orderedMovies, key = { _, movie -> movie.id }) { idx, movie ->
                    val isActive = movie.id == activeMovieId
                    val progressFraction = movieProgressMap[movie.id] ?: movieProgressMap[movie.url] ?: 0f
                    CollectionMovieCard(
                        movie = movie,
                        fallbackPosterUrl = collection.imageUrl,
                        isActive = isActive,
                        progressFraction = progressFraction,
                        onPlay = {
                            onSelectMovie(collection, movie)
                            onDismiss()
                        },
                        onDownload = onDownloadMovie?.let { handler ->
                            {
                                handler(collection, movie)
                            }
                        },
                        modifier = if (idx == focusIdx) {
                            Modifier.focusRequester(primaryFocusRequester)
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
private fun CollectionMovieCard(
    movie: CollectionMovie,
    fallbackPosterUrl: String?,
    isActive: Boolean,
    progressFraction: Float,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val isWatched = progressFraction >= ContinueWatchingHelper.COMPLETED_FRACTION
    val showProgressBar = progressFraction >= ContinueWatchingHelper.MIN_RESUME_FRACTION && !isWatched

    Surface(
        onClick = onPlay,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusHighlight(shape = RoundedCornerShape(14.dp), focusedScale = 1.02f)
            .testTag("collection_movie_${movie.id}"),
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
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Individual film poster with numeric sequence pill (#1, #2, ...) — zero E1/E2 labels
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(82.dp)
                ) {
                    PosterImageThumbnail(
                        imageUrl = movie.imageUrl ?: fallbackPosterUrl,
                        contentDescription = "${movie.title} poster",
                        modifier = Modifier
                            .width(56.dp)
                            .height(82.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isActive) CinemaAmber else Color(0xCC09090B))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "#${movie.order}",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isActive) Color(0xFF0A0A0D) else Color.White
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = movie.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isActive) CinemaAmber else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!movie.imdbRating.isNullOrBlank() || !movie.imdbId.isNullOrBlank()) {
                            ImdbBadgePill(rating = movie.imdbRating, imdbId = movie.imdbId)
                        }
                        if (!movie.releaseYear.isNullOrBlank()) {
                            Text(
                                text = movie.releaseYear,
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!movie.durationLabel.isNullOrBlank()) {
                            Text(
                                text = movie.durationLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FormatBadge(format = movie.format)
                        if (isWatched) {
                            SubtitlePillBadge(text = "Watched", accent = SubtitleEmerald)
                        }
                        if (!movie.subtitleUrl.isNullOrBlank()) {
                            SubtitlePillBadge(text = "CC")
                        }
                        if (movie.externalAudios.isNotEmpty()) {
                            SubtitlePillBadge(
                                text = if (movie.externalAudios.size == 1) "AUDIO" else "${movie.externalAudios.size} AUDIO",
                                accent = StreamCyan
                            )
                        }
                        if (movie.mirrors.isNotEmpty()) {
                            SubtitlePillBadge(
                                text = "${movie.mirrors.size + 1} CDN",
                                accent = SubtitleEmerald
                            )
                        }
                    }

                    if (!movie.description.isNullOrBlank()) {
                        Text(
                            text = movie.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                                contentDescription = "Play ${movie.title}",
                                tint = if (isActive) MaterialTheme.colorScheme.onPrimary else CinemaAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (onDownload != null) {
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("collection_movie_download_${movie.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Download ${movie.title}",
                                tint = StreamCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (showProgressBar) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .testTag("collection_movie_progress_${movie.id}"),
                    color = CinemaAmber,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
            }
        }
    }
}
