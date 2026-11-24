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
import com.example.ui.theme.OnCinemaAmber
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
        shape = RoundedCornerShape(4.dp),
        color = ImdbGold
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!rating.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = OnImdbGold,
                    modifier = Modifier.size(10.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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
            tint = CinemaAmber.copy(alpha = 0.24f),
            modifier = Modifier.size(30.dp)
        )
    }
}

/**
 * Editorial 2:3 cinema poster card with clean artwork framing, restrained metadata pills,
 * and Android TV D-Pad focus highlight.
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
    cardWidth: Dp = 158.dp,
    posterHeight: Dp = 224.dp,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(cardWidth)
            .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.04f)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(posterHeight),
            shape = RoundedCornerShape(10.dp),
            color = ObsidianSurface,
            border = BorderStroke(
                width = if (isActive) 1.5.dp else 0.75.dp,
                color = if (isActive) CinemaAmber else ObsidianOutline.copy(alpha = 0.75f)
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PosterImageThumbnail(
                    imageUrl = imageUrl,
                    contentDescription = "$title poster",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                )

                // Subtle cinema vignette — keeps artwork clean while preserving badge contrast
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to ObsidianCanvas.copy(alpha = 0.50f),
                                0.28f to Color.Transparent,
                                0.72f to Color.Transparent,
                                1.0f to ObsidianCanvas.copy(alpha = 0.78f)
                            )
                        )
                )

                // Top Row: IMDb Rating + Format/Series Tag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(7.dp),
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
                            shape = RoundedCornerShape(4.dp),
                            color = CinemaAmber
                        ) {
                            Text(
                                text = seriesBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = OnCinemaAmber,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        FormatBadge(format = format)
                    }
                }

                // Bottom Overlay: Year / CC + subtle active cue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .padding(7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!releaseYear.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xB809090B)
                            ) {
                                Text(
                                    text = releaseYear,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                )
                            }
                        }
                        if (hasSubtitle) {
                            SubtitlePillBadge(text = "CC")
                        }
                    }

                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CinemaAmber
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Playing $title",
                                tint = OnCinemaAmber,
                                modifier = Modifier
                                    .padding(3.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isActive) CinemaAmber else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
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
