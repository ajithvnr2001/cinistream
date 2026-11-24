package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.UpNextCountdownState
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaAmberBright
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.OnCinemaAmber
import com.example.ui.theme.SlateInkSecondary
import com.example.ui.theme.StreamCyan
import com.example.viewmodel.ChannelZapBannerState

@Composable
fun UpNextCountdownCard(
    state: UpNextCountdownState,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(min = 260.dp, max = 330.dp)
            .testTag("up_next_countdown_card"),
        shape = RoundedCornerShape(12.dp),
        color = ObsidianSurface.copy(alpha = 0.94f),
        border = BorderStroke(0.75.dp, CinemaAmber.copy(alpha = 0.55f)),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "UP NEXT · ${state.secondsRemaining}s",
                style = MaterialTheme.typography.labelSmall,
                color = CinemaAmber,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${state.nextEpisode.codeLabel} · ${state.nextEpisode.title}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = {
                    val total = state.totalSeconds.coerceAtLeast(1)
                    ((total - state.secondsRemaining).toFloat() / total.toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = CinemaAmber,
                trackColor = Color.White.copy(alpha = 0.14f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayNow,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("up_next_play_now_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CinemaAmber,
                        contentColor = OnCinemaAmber
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.up_next_play_now))
                }
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("up_next_cancel_button"),
                    border = BorderStroke(0.75.dp, ObsidianOutline)
                ) {
                    Text(stringResource(R.string.up_next_cancel))
                }
            }
        }
    }
}

@Composable
fun SkipIntroButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag("skip_intro_button"),
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface.copy(alpha = 0.90f),
        border = BorderStroke(0.75.dp, CinemaAmber.copy(alpha = 0.65f)),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.cd_skip_intro),
                tint = CinemaAmber,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.skip_intro_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun Hold2xSpeedPill(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag("hold_2x_speed_pill"),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xCC0E0E11),
        border = BorderStroke(0.75.dp, CinemaAmber.copy(alpha = 0.70f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FastForward,
                contentDescription = null,
                tint = CinemaAmber,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.hold_2x_badge),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

@Composable
fun DualSubtitleOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .background(Color(0xBF09090B), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .testTag("dual_subtitle_overlay")
    ) {
        Text(
            text = text,
            color = CinemaAmberBright,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
fun ChannelZapEpgBanner(
    banner: ChannelZapBannerState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(min = 260.dp, max = 400.dp)
            .testTag("channel_zap_epg_banner"),
        shape = RoundedCornerShape(12.dp),
        color = ObsidianSurface.copy(alpha = 0.94f),
        border = BorderStroke(0.75.dp, StreamCyan.copy(alpha = 0.55f)),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.LiveTv,
                    contentDescription = null,
                    tint = StreamCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "CH ${banner.channelNumber}/${banner.totalChannels} · ${banner.groupTitle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StreamCyan
                )
            }
            Text(
                text = banner.channelTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val nowProg = banner.epgNowNext?.now
            val nextProg = banner.epgNowNext?.next
            if (nowProg != null) {
                Text(
                    text = "NOW: ${nowProg.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { nowProg.progressFraction(System.currentTimeMillis()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = CinemaAmber,
                    trackColor = Color.White.copy(alpha = 0.14f)
                )
            }
            if (nextProg != null) {
                Text(
                    text = "NEXT: ${nextProg.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateInkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
