package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.StreamFormat
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ImdbGold
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OnImdbGold

@Composable
fun ImdbBadgePill(
    rating: String?,
    imdbId: String? = null,
    modifier: Modifier = Modifier
) {
    val label = when {
        !rating.isNullOrBlank() -> "IMDb $rating"
        !imdbId.isNullOrBlank() -> "IMDb"
        else -> return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = ImdbGold
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (!rating.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = OnImdbGold,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = OnImdbGold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun PosterImageThumbnail(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    decodeWidthPx: Int = 320,
    decodeHeightPx: Int = 480
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(ObsidianSurfaceHigh, ObsidianCanvas)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        PosterFallbackArt(modifier = Modifier.fillMaxSize())
        if (!imageUrl.isNullOrBlank()) {
            val imageRequest = remember(context, imageUrl, decodeWidthPx, decodeHeightPx) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(decodeWidthPx, decodeHeightPx)
                    .allowRgb565(true)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PosterFallbackArt(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
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
            imageVector = Icons.Rounded.Movie,
            contentDescription = null,
            tint = CinemaAmber.copy(alpha = 0.45f),
            modifier = Modifier.size(36.dp)
        )
    }
}

/**
 * Clickable movie / stream poster card displaying an IMDb or JSON-provided poster image,
 * IMDb badge, release year, format badge, and click-to-play affordance.
 * Supports Android TV D-Pad navigation via tvFocusHighlight.
 */
@Composable
fun PosterStreamCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    format: StreamFormat,
    imdbId: String? = null,
    imdbRating: String? = null,
    releaseYear: String? = null,
    seriesBadge: String? = null,
    hasSubtitle: Boolean = false,
    isActive: Boolean = false,
    cardWidth: Dp = 172.dp,
    posterHeight: Dp = 216.dp,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(cardWidth)
            .tvFocusHighlight(shape = RoundedCornerShape(16.dp), focusedScale = 1.05f)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = ObsidianSurface,
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) CinemaAmber else ObsidianOutline
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(posterHeight)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                PosterImageThumbnail(
                    imageUrl = imageUrl,
                    contentDescription = "$title poster",
                    modifier = Modifier.fillMaxSize()
                )

                // Scrim for crisp badge & play button legibility over bright movie posters
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    ObsidianCanvas.copy(alpha = 0.55f),
                                    Color.Transparent,
                                    ObsidianCanvas.copy(alpha = 0.85f)
                                )
                            )
                        )
                )

                // Top Row: IMDb Badge + Format Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!imdbRating.isNullOrBlank() || !imdbId.isNullOrBlank()) {
                        ImdbBadgePill(rating = imdbRating, imdbId = imdbId)
                    } else {
                        Box(modifier = Modifier.size(1.dp))
                    }
                    if (!seriesBadge.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CinemaAmber.copy(alpha = 0.92f)
                        ) {
                            Text(
                                text = seriesBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color.Black,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        FormatBadge(format = format)
                    }
                }

                // Bottom Overlay inside Poster: Year / CC + Play Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!releaseYear.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = Color.Black.copy(alpha = 0.65f)
                            ) {
                                Text(
                                    text = releaseYear,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (hasSubtitle) {
                            SubtitlePillBadge(text = "CC")
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = CinemaAmber.copy(alpha = 0.92f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play $title",
                            tint = Color.Black,
                            modifier = Modifier
                                .padding(5.dp)
                                .size(18.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isActive) CinemaAmber else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
