package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.ContinueWatchingHelper
import com.example.data.MediaHistoryEntity
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OnCinemaAmber
import com.example.ui.theme.StreamCyan

/**
 * Horizontal "Continue Watching" rail with 16:9 widescreen cinema stills,
 * edge-to-edge flush bottom progress rail, 1-tap resume, and 1-tap dismiss.
 */
@Composable
fun ContinueWatchingSection(
    items: List<MediaHistoryEntity>,
    onResumeItem: (MediaHistoryEntity) -> Unit,
    onDismissItem: (MediaHistoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("continue_watching_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Continue Watching",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${items.size} in progress",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyRow(
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { "cw_${it.id}_${it.sourceUri}" }) { item ->
                ContinueWatchingCard(
                    item = item,
                    onClick = { onResumeItem(item) },
                    onDismiss = { onDismissItem(item) }
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: MediaHistoryEntity,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val progress = ContinueWatchingHelper.progressFraction(item.lastPositionMs, item.durationMs)
    val episodeBadge = ContinueWatchingHelper.formatEpisodeCode(item.seasonNumber, item.episodeNumber)
    val remainingLabel = ContinueWatchingHelper.formatRemainingTime(item.lastPositionMs, item.durationMs)
    val displayTitle = item.seriesTitle?.takeIf { it.isNotBlank() } ?: item.title
    val subtitleLine = if (!episodeBadge.isNullOrBlank()) {
        "$episodeBadge · $remainingLabel"
    } else {
        remainingLabel
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(228.dp)
            .height(132.dp)
            .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
            .testTag("continue_watching_card_${item.id}"),
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurfaceHigh,
        border = BorderStroke(0.75.dp, ObsidianOutline.copy(alpha = 0.8f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.posterUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "$displayTitle artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    ObsidianSurfaceHigh,
                                    ObsidianCanvas
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (episodeBadge != null) Icons.Rounded.Tv else Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = CinemaAmber.copy(alpha = 0.22f),
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            // Smooth optical vignette scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.32f),
                            0.45f to Color.Black.copy(alpha = 0.16f),
                            1.0f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
            )

            // Top-left time/episode metadata + top-right subtle dismiss
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xBF09090B)
                ) {
                    Text(
                        text = subtitleLine,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (episodeBadge != null) CinemaAmber else StreamCyan,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0x9909090B))
                        .tvFocusHighlight(shape = CircleShape, focusedScale = 1.08f)
                        .testTag("continue_watching_dismiss_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove $displayTitle from Continue Watching",
                        tint = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Bottom title row + flush bottom progress rail
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = CinemaAmber
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Resume $displayTitle",
                            tint = OnCinemaAmber,
                            modifier = Modifier
                                .padding(3.dp)
                                .size(13.dp)
                        )
                    }
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = CinemaAmber,
                    trackColor = Color.White.copy(alpha = 0.16f)
                )
            }
        }
    }
}
