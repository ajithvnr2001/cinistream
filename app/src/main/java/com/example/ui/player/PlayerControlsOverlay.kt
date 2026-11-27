package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SkipPrevious
import com.example.R
import com.example.data.OfflineDownloadEntity
import com.example.model.AbLoopState
import com.example.model.AspectRatioMode
import com.example.model.ChapterMarker
import com.example.model.StreamFormat
import com.example.player.AudioBoostMath
import com.example.player.OfflineDownloadManager
import com.example.player.PlaybackProgressState
import com.example.player.VideoDecoderMode
import com.example.ui.components.FormatBadge
import com.example.ui.components.formatDuration
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaAmberBright
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SignalRose
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.SubtitleEmerald
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun PlayerLockedOverlay(
    safeInsets: WindowInsets,
    tvOverscanHorizontal: Dp,
    tvOverscanVertical: Dp,
    onUnlock: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(safeInsets)
            .padding(horizontal = tvOverscanHorizontal, vertical = tvOverscanVertical)
    ) {
        Surface(
            onClick = onUnlock,
            shape = RoundedCornerShape(10.dp),
            color = Color(0xCC0E0E11),
            border = BorderStroke(0.75.dp, CinemaAmber.copy(alpha = 0.6f)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .minimumInteractiveComponentSize()
                .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                .testTag("player_unlock_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.LockOpen,
                    contentDescription = stringResource(R.string.cd_unlock_controls),
                    tint = CinemaAmber,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = "Unlock",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
internal fun PlayerTopBar(
    title: String,
    format: StreamFormat,
    videoHeight: Int,
    hasActiveSubtitles: Boolean,
    statsForNerdsEnabled: Boolean,
    hasSeriesEpisodes: Boolean,
    episodeCodeBadge: String?,
    hasJsonPlaylist: Boolean,
    hasM3uPlaylist: Boolean,
    isTv: Boolean,
    backgroundAudioEnabled: Boolean = false,
    tvOverscanHorizontal: Dp,
    tvOverscanVertical: Dp,
    subButtonFocusRequester: FocusRequester,
    statsButtonFocusRequester: FocusRequester,
    episodesButtonFocusRequester: FocusRequester,
    jsonButtonFocusRequester: FocusRequester,
    m3uButtonFocusRequester: FocusRequester,
    tracksButtonFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onToggleStatsForNerds: () -> Unit,
    onOpenSeriesEpisodes: () -> Unit,
    onOpenJsonCatalog: () -> Unit,
    onOpenM3uQueue: () -> Unit,
    onOpenTracks: () -> Unit,
    onToggleBackgroundAudio: (() -> Unit)? = null,
    onEnterPip: (() -> Unit)? = null,
    onLockControls: () -> Unit,
    isFavorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    canDownload: Boolean = false,
    activeDownload: OfflineDownloadEntity? = null,
    onDownloadStream: (() -> Unit)? = null,
    onOpenDownloads: (() -> Unit)? = null,
    sleepTimerBadge: String? = null,
    hasM3uZapping: Boolean = false,
    onChannelPrev: (() -> Unit)? = null,
    onChannelNext: (() -> Unit)? = null,
    brightnessPercent: Int = PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT,
    showBrightnessPanel: Boolean = false,
    brightnessButtonFocusRequester: FocusRequester = remember { FocusRequester() },
    onToggleBrightnessPanel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.86f),
                    0.55f to Color.Black.copy(alpha = 0.42f),
                    1.0f to Color.Transparent
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(horizontal = tvOverscanHorizontal, vertical = tvOverscanVertical)
    ) {
        if (!isTv) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1 (Mobile): Back + full-width Title & stream badges + Tracks + Lock
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FormatBadge(format = format)
                            if (!episodeCodeBadge.isNullOrBlank()) {
                                Text(
                                    text = "· $episodeCodeBadge",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaAmber
                                )
                            }
                            if (videoHeight > 0) {
                                Text(
                                    text = "${videoHeight}p",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.78f)
                                )
                            }
                            if (hasActiveSubtitles) {
                                Text(
                                    text = "· CC ON",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SubtitleEmerald
                                )
                            }
                            if (statsForNerdsEnabled) {
                                Text(
                                    text = "· NET HUD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaAmber
                                )
                            }
                            if (!sleepTimerBadge.isNullOrBlank()) {
                                Text(
                                    text = "· $sleepTimerBadge",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StreamCyan
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onOpenTracks,
                        modifier = Modifier
                            .focusRequester(tracksButtonFocusRequester)
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_tracks_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.cd_tracks),
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = onLockControls,
                        modifier = Modifier
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_lock_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.cd_lock_controls),
                            tint = Color.White
                        )
                    }
                }

                // Row 2 (Mobile): Horizontally scrollable quick-action strip so Sub, Download,
                // Stats, Episodes, Favorite, BG Audio, and PiP never clip off-screen on portrait phones.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("player_mobile_action_strip"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val subTint = if (hasActiveSubtitles) SubtitleEmerald else Color.White
                    Surface(
                        onClick = onOpenSubtitles,
                        shape = RoundedCornerShape(12.dp),
                        color = if (hasActiveSubtitles) SubtitleEmerald.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(
                            1.dp,
                            if (hasActiveSubtitles) SubtitleEmerald else Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .focusRequester(subButtonFocusRequester)
                            .testTag("player_subtitles_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Subtitles,
                                contentDescription = stringResource(R.string.cd_subtitles),
                                tint = subTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Sub",
                                style = MaterialTheme.typography.labelMedium,
                                color = subTint
                            )
                        }
                    }

                    if (canDownload && onDownloadStream != null) {
                        val dlStatus = activeDownload?.status
                        val isDownloading = dlStatus == OfflineDownloadManager.STATUS_DOWNLOADING ||
                            dlStatus == OfflineDownloadManager.STATUS_QUEUED
                        val isPaused = dlStatus == OfflineDownloadManager.STATUS_PAUSED
                        val isSaved = dlStatus == OfflineDownloadManager.STATUS_COMPLETED
                        val isFailed = dlStatus == OfflineDownloadManager.STATUS_FAILED
                        val dlTint = when {
                            isSaved -> SubtitleEmerald
                            isDownloading -> StreamCyan
                            isPaused -> CinemaAmber
                            isFailed -> SignalRose
                            else -> Color.White
                        }
                        val dlLabel = when {
                            isSaved -> "Saved"
                            dlStatus == OfflineDownloadManager.STATUS_DOWNLOADING -> "${activeDownload.progressPercent}%"
                            dlStatus == OfflineDownloadManager.STATUS_QUEUED -> "Queued"
                            isPaused -> if (activeDownload.progressPercent > 0) "Paused ${activeDownload.progressPercent}%" else "Paused"
                            isFailed -> "Resume"
                            else -> "Download"
                        }
                        val dlClick: () -> Unit = {
                            if ((isSaved || isDownloading) && onOpenDownloads != null) {
                                onOpenDownloads()
                            } else {
                                onDownloadStream()
                            }
                        }
                        val highlighted = isSaved || isDownloading || isPaused || isFailed
                        Surface(
                            onClick = dlClick,
                            shape = RoundedCornerShape(12.dp),
                            color = if (highlighted) {
                                dlTint.copy(alpha = 0.24f)
                            } else {
                                Color.White.copy(alpha = 0.14f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (highlighted) dlTint else Color.White.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_download_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        isSaved -> Icons.Rounded.CheckCircle
                                        isPaused -> Icons.Rounded.PlayArrow
                                        else -> Icons.Rounded.Download
                                    },
                                    contentDescription = stringResource(R.string.cd_download_stream),
                                    tint = dlTint,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = dlLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = dlTint
                                )
                            }
                        }
                    }

                    val statsTint = if (statsForNerdsEnabled) CinemaAmber else Color.White
                    Surface(
                        onClick = onToggleStatsForNerds,
                        shape = RoundedCornerShape(12.dp),
                        color = if (statsForNerdsEnabled) CinemaAmber.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(
                            1.dp,
                            if (statsForNerdsEnabled) CinemaAmber else Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .focusRequester(statsButtonFocusRequester)
                            .testTag("player_stats_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NetworkCheck,
                                contentDescription = stringResource(R.string.cd_stats_for_nerds),
                                tint = statsTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Stats",
                                style = MaterialTheme.typography.labelMedium,
                                color = statsTint
                            )
                        }
                    }

                    if (hasSeriesEpisodes) {
                        Surface(
                            onClick = onOpenSeriesEpisodes,
                            shape = RoundedCornerShape(12.dp),
                            color = CinemaAmber.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .focusRequester(episodesButtonFocusRequester)
                                .testTag("player_episodes_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tv,
                                    contentDescription = stringResource(R.string.cd_episodes),
                                    tint = CinemaAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Episodes",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CinemaAmber
                                )
                            }
                        }
                    }

                    if (onToggleFavorite != null) {
                        val favTint = if (isFavorited) SignalRose else Color.White
                        Surface(
                            onClick = onToggleFavorite,
                            shape = RoundedCornerShape(12.dp),
                            color = if (isFavorited) SignalRose.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, if (isFavorited) SignalRose else Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_favorite_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = stringResource(R.string.cd_favorite_toggle),
                                    tint = favTint,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (isFavorited) "Saved" else "Watchlist",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = favTint
                                )
                            }
                        }
                    }

                    if (onToggleBackgroundAudio != null) {
                        val bgTint = if (backgroundAudioEnabled) SubtitleEmerald else Color.White
                        Surface(
                            onClick = onToggleBackgroundAudio,
                            shape = RoundedCornerShape(12.dp),
                            color = if (backgroundAudioEnabled) SubtitleEmerald.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, if (backgroundAudioEnabled) SubtitleEmerald else Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_bg_audio_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Headphones,
                                    contentDescription = stringResource(R.string.cd_background_audio),
                                    tint = bgTint,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "BG Audio",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = bgTint
                                )
                            }
                        }
                    }

                    if (onEnterPip != null) {
                        Surface(
                            onClick = onEnterPip,
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_pip_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PictureInPictureAlt,
                                    contentDescription = stringResource(R.string.cd_enter_pip),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "PiP",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (hasJsonPlaylist) {
                        IconButton(
                            onClick = onOpenJsonCatalog,
                            modifier = Modifier
                                .focusRequester(jsonButtonFocusRequester)
                                .testTag("player_json_catalog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VideoLibrary,
                                contentDescription = stringResource(R.string.cd_json_playlist),
                                tint = CinemaAmber
                            )
                        }
                    }

                    if (hasM3uPlaylist) {
                        IconButton(
                            onClick = onOpenM3uQueue,
                            modifier = Modifier
                                .focusRequester(m3uButtonFocusRequester)
                                .testTag("player_m3u_queue_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistPlay,
                                contentDescription = stringResource(R.string.cd_playlist),
                                tint = Color.White
                            )
                        }
                    }

                    if (hasM3uZapping && onChannelPrev != null && onChannelNext != null) {
                        IconButton(
                            onClick = onChannelPrev,
                            modifier = Modifier.testTag("player_channel_prev_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_channel_prev),
                                tint = StreamCyan
                            )
                        }
                        IconButton(
                            onClick = onChannelNext,
                            modifier = Modifier.testTag("player_channel_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = stringResource(R.string.cd_channel_next),
                                tint = StreamCyan
                            )
                        }
                    }
                }
            }
        } else {
            // Android TV 10-foot single-row D-Pad header (preserves Sub -> Brightness -> Stats -> ... -> Tracks order)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Color.White
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FormatBadge(format = format)
                        if (!episodeCodeBadge.isNullOrBlank()) {
                            Text(
                                text = "· $episodeCodeBadge",
                                style = MaterialTheme.typography.labelSmall,
                                color = CinemaAmber
                            )
                        }
                        if (videoHeight > 0) {
                            Text(
                                text = "${videoHeight}p",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
                        if (hasActiveSubtitles) {
                            Text(
                                text = "· CC ON",
                                style = MaterialTheme.typography.labelSmall,
                                color = SubtitleEmerald
                            )
                        }
                        if (statsForNerdsEnabled) {
                            Text(
                                text = "· NET HUD",
                                style = MaterialTheme.typography.labelSmall,
                                color = CinemaAmber
                            )
                        }
                        if (!sleepTimerBadge.isNullOrBlank()) {
                            Text(
                                text = "· $sleepTimerBadge",
                                style = MaterialTheme.typography.labelSmall,
                                color = StreamCyan
                            )
                        }
                    }
                }

                val subTint = if (hasActiveSubtitles) SubtitleEmerald else Color.White
                Surface(
                    onClick = onOpenSubtitles,
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasActiveSubtitles) SubtitleEmerald.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(
                        1.dp,
                        if (hasActiveSubtitles) SubtitleEmerald else Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .focusRequester(subButtonFocusRequester)
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                        .testTag("player_subtitles_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Subtitles,
                            contentDescription = stringResource(R.string.cd_subtitles),
                            tint = subTint,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Sub",
                            style = MaterialTheme.typography.labelMedium,
                            color = subTint
                        )
                    }
                }

                if (onToggleBrightnessPanel != null) {
                    val isAdjusted = brightnessPercent != PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT
                    val isBrightBoosted = brightnessPercent > PlayerGestureMath.TV_DEFAULT_BRIGHTNESS_PERCENT
                    val brightTint = when {
                        isBrightBoosted -> CinemaAmberBright
                        isAdjusted || showBrightnessPanel -> StreamCyan
                        else -> Color.White
                    }
                    val brightActive = isAdjusted || showBrightnessPanel
                    Surface(
                        onClick = onToggleBrightnessPanel,
                        shape = RoundedCornerShape(12.dp),
                        color = if (brightActive) brightTint.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(
                            1.dp,
                            if (brightActive) brightTint else Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .focusRequester(brightnessButtonFocusRequester)
                            .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                            .testTag("player_brightness_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Brightness6,
                                contentDescription = stringResource(R.string.cd_brightness),
                                tint = brightTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${brightnessPercent}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = brightTint
                            )
                        }
                    }
                }

                val statsTint = if (statsForNerdsEnabled) CinemaAmber else Color.White
                Surface(
                    onClick = onToggleStatsForNerds,
                    shape = RoundedCornerShape(12.dp),
                    color = if (statsForNerdsEnabled) CinemaAmber.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(
                        1.dp,
                        if (statsForNerdsEnabled) CinemaAmber else Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .focusRequester(statsButtonFocusRequester)
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                        .testTag("player_stats_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NetworkCheck,
                            contentDescription = stringResource(R.string.cd_stats_for_nerds),
                            tint = statsTint,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Stats",
                            style = MaterialTheme.typography.labelMedium,
                            color = statsTint
                        )
                    }
                }

                if (hasSeriesEpisodes) {
                    Surface(
                        onClick = onOpenSeriesEpisodes,
                        shape = RoundedCornerShape(12.dp),
                        color = CinemaAmber.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .focusRequester(episodesButtonFocusRequester)
                            .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                            .testTag("player_episodes_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tv,
                                contentDescription = stringResource(R.string.cd_episodes),
                                tint = CinemaAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Episodes",
                                style = MaterialTheme.typography.labelMedium,
                                color = CinemaAmber
                            )
                        }
                    }
                }

                if (hasJsonPlaylist) {
                    IconButton(
                        onClick = onOpenJsonCatalog,
                        modifier = Modifier
                            .focusRequester(jsonButtonFocusRequester)
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_json_catalog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VideoLibrary,
                            contentDescription = stringResource(R.string.cd_json_playlist),
                            tint = CinemaAmber
                        )
                    }
                }

                if (hasM3uPlaylist) {
                    IconButton(
                        onClick = onOpenM3uQueue,
                        modifier = Modifier
                            .focusRequester(m3uButtonFocusRequester)
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_m3u_queue_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistPlay,
                            contentDescription = stringResource(R.string.cd_playlist),
                            tint = Color.White
                        )
                    }
                }

                if (hasM3uZapping && onChannelPrev != null && onChannelNext != null) {
                    IconButton(
                        onClick = onChannelPrev,
                        modifier = Modifier
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_channel_prev_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.cd_channel_prev),
                            tint = StreamCyan
                        )
                    }
                    IconButton(
                        onClick = onChannelNext,
                        modifier = Modifier
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_channel_next_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.cd_channel_next),
                            tint = StreamCyan
                        )
                    }
                }

                if (onToggleFavorite != null) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("player_favorite_button")
                    ) {
                        Icon(
                            imageVector = if (isFavorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = stringResource(R.string.cd_favorite_toggle),
                            tint = if (isFavorited) SignalRose else Color.White
                        )
                    }
                }

                IconButton(
                    onClick = onOpenTracks,
                    modifier = Modifier
                        .focusRequester(tracksButtonFocusRequester)
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("player_tracks_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = stringResource(R.string.cd_tracks),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerCenterTransport(
    isPlaying: Boolean,
    playPauseFocusRequester: FocusRequester,
    onRewind10s: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward10s: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Surface(
            onClick = onRewind10s,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .size(56.dp)
                .tvFocusHighlight(shape = CircleShape, focusedScale = 1.1f)
                .testTag("player_rewind_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Replay10,
                    contentDescription = stringResource(R.string.cd_rewind),
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Surface(
            onClick = onTogglePlayPause,
            shape = CircleShape,
            color = CinemaAmber,
            modifier = Modifier
                .size(74.dp)
                .focusRequester(playPauseFocusRequester)
                .tvFocusHighlight(
                    shape = CircleShape,
                    borderColor = Color.White,
                    borderWidth = 3.dp,
                    focusedScale = 1.1f
                )
                .testTag("player_play_pause_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play_pause),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Surface(
            onClick = onForward10s,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .size(56.dp)
                .tvFocusHighlight(shape = CircleShape, focusedScale = 1.1f)
                .testTag("player_forward_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Forward10,
                    contentDescription = stringResource(R.string.cd_forward),
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

/**
 * Bottom controls bar isolating rapid 500ms [progressStateFlow] updates to ONLY the timestamp
 * label and [SelectableCinemaSeekBar] so [PlayerScreen] never recomposes on playback ticks.
 */
@Composable
internal fun PlayerBottomBar(
    progressStateFlow: StateFlow<PlaybackProgressState>,
    volumePercent: Int,
    playbackSpeed: Float,
    aspectRatioMode: AspectRatioMode,
    hasNextEpisode: Boolean,
    isTv: Boolean,
    showSpeedMenu: Boolean,
    volumeButtonFocusRequester: FocusRequester,
    speedButtonFocusRequester: FocusRequester,
    seekBarFocusRequester: FocusRequester,
    onPlayNextEpisode: () -> Unit,
    onOpenVolumeBoost: () -> Unit,
    onOpenSpeedMenu: () -> Unit,
    onDismissSpeedMenu: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleOrientation: () -> Unit,
    onSeekCommit: (Long) -> Unit,
    onScrubbingStateChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    chapters: List<ChapterMarker> = emptyList(),
    abLoop: AbLoopState = AbLoopState(),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
            .padding(horizontal = if (isTv) 32.dp else 16.dp, vertical = if (isTv) 18.dp else 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Isolated timestamp text subscriber
                PlayerTimestampBadge(progressStateFlow = progressStateFlow)

                // Quick Bottom Controls: Next Ep (Series), Sound Boost (200%), Speed, Aspect Ratio, (Rotate on Mobile)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasNextEpisode) {
                        Surface(
                            onClick = onPlayNextEpisode,
                            shape = RoundedCornerShape(10.dp),
                            color = CinemaAmber.copy(alpha = 0.24f),
                            border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                .testTag("player_next_episode_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = stringResource(R.string.cd_next_episode),
                                    tint = CinemaAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Next Ep",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CinemaAmber
                                )
                            }
                        }
                    }

                    val isBoosted = volumePercent > AudioBoostMath.NORMAL_MAX_PERCENT
                    val volAccent = when {
                        volumePercent >= 160 -> SignalRose
                        isBoosted -> CinemaAmberBright
                        else -> CinemaAmber
                    }
                    Surface(
                        onClick = onOpenVolumeBoost,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isBoosted) volAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.14f),
                        border = if (isBoosted) BorderStroke(1.dp, volAccent.copy(alpha = 0.65f)) else null,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .focusRequester(volumeButtonFocusRequester)
                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                            .testTag("player_volume_boost_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    volumePercent == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
                                    isBoosted -> Icons.Rounded.GraphicEq
                                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = stringResource(R.string.cd_volume_boost),
                                tint = volAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = AudioBoostMath.formatShortLabel(volumePercent),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isBoosted) volAccent else Color.White
                            )
                        }
                    }

                    PlayerSpeedMenuControl(
                        playbackSpeed = playbackSpeed,
                        expanded = showSpeedMenu,
                        focusRequester = speedButtonFocusRequester,
                        onOpen = onOpenSpeedMenu,
                        onDismiss = onDismissSpeedMenu,
                        onSelectSpeed = onSelectSpeed
                    )

                    Surface(
                        onClick = onCycleAspectRatio,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                            .testTag("player_aspect_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AspectRatio,
                                contentDescription = stringResource(R.string.cd_aspect_ratio),
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = aspectRatioMode.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }

                    if (!isTv) {
                        IconButton(
                            onClick = onToggleOrientation,
                            modifier = Modifier
                                .tvFocusHighlight(shape = CircleShape)
                                .testTag("player_rotate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ScreenRotation,
                                contentDescription = stringResource(R.string.cd_rotate),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Isolated seekbar subscriber
            PlayerIsolatedSeekBar(
                progressStateFlow = progressStateFlow,
                isTv = isTv,
                focusRequester = seekBarFocusRequester,
                onSeekCommit = onSeekCommit,
                onScrubbingStateChanged = onScrubbingStateChanged,
                onInteraction = onInteraction,
                chapters = chapters,
                abLoop = abLoop
            )
        }
    }
}

@Composable
private fun PlayerTimestampBadge(progressStateFlow: StateFlow<PlaybackProgressState>) {
    val progress by progressStateFlow.collectAsStateWithLifecycle()
    if (progress.isLiveStream && progress.durationMs <= 0L) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = SignalRose.copy(alpha = 0.22f),
            border = BorderStroke(1.dp, SignalRose)
        ) {
            Text(
                text = "● LIVE HLS",
                style = MaterialTheme.typography.labelSmall,
                color = SignalRose,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    } else {
        Text(
            text = "${formatDuration(progress.currentPositionMs)} / ${formatDuration(progress.durationMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
private fun PlayerIsolatedSeekBar(
    progressStateFlow: StateFlow<PlaybackProgressState>,
    isTv: Boolean,
    focusRequester: FocusRequester,
    onSeekCommit: (Long) -> Unit,
    onScrubbingStateChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    chapters: List<ChapterMarker> = emptyList(),
    abLoop: AbLoopState = AbLoopState()
) {
    val progress by progressStateFlow.collectAsStateWithLifecycle()
    if (!progress.isLiveStream || progress.durationMs > 0L) {
        SelectableCinemaSeekBar(
            currentPositionMs = progress.currentPositionMs,
            bufferedPositionMs = progress.bufferedPositionMs,
            durationMs = progress.durationMs,
            isTv = isTv,
            focusRequester = focusRequester,
            onSeekCommit = onSeekCommit,
            onScrubbingStateChanged = onScrubbingStateChanged,
            onInteraction = onInteraction,
            chapters = chapters,
            abLoop = abLoop
        )
    }
}

@Composable
private fun PlayerSpeedMenuControl(
    playbackSpeed: Float,
    expanded: Boolean,
    focusRequester: FocusRequester,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelectSpeed: (Float) -> Unit
) {
    Box {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(10.dp),
            color = Color.White.copy(alpha = 0.14f),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .focusRequester(focusRequester)
                .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                .testTag("player_speed_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = CinemaAmber,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${playbackSpeed}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            containerColor = ObsidianSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.5.dp, CinemaAmber.copy(alpha = 0.65f))
        ) {
            val speedOptions = remember { listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }
            val activeSpeedFocusRequester = remember { FocusRequester() }
            LaunchedEffect(expanded) {
                if (expanded) {
                    delay(80L)
                    runCatching { activeSpeedFocusRequester.requestFocus() }
                }
            }
            speedOptions.forEach { speed ->
                val isCurrent = speed == playbackSpeed
                var isRowFocused by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .then(
                            if (isCurrent) Modifier.focusRequester(activeSpeedFocusRequester) else Modifier
                        )
                        .tvFocusHighlight(
                            shape = RoundedCornerShape(10.dp),
                            focusedScale = 1.03f,
                            onFocusStateChanged = { isRowFocused = it }
                        )
                        .background(
                            color = when {
                                isRowFocused -> CinemaAmber.copy(alpha = 0.24f)
                                isCurrent -> CinemaAmber.copy(alpha = 0.10f)
                                else -> Color.Transparent
                            },
                            shape = RoundedCornerShape(10.dp)
                        )
                        .testTag("speed_option_${speed}x"),
                    leadingIcon = {
                        when {
                            isRowFocused -> Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            isCurrent -> Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = CinemaAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            else -> Spacer(Modifier.size(18.dp))
                        }
                    },
                    text = {
                        Text(
                            text = "${speed}x",
                            style = MaterialTheme.typography.titleSmall,
                            color = when {
                                isRowFocused -> Color.White
                                isCurrent -> CinemaAmber
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = { onSelectSpeed(speed) }
                )
            }
        }
    }
}

@Composable
internal fun PlayerErrorCard(
    message: String,
    currentFormat: StreamFormat,
    isDecoderError: Boolean = false,
    effectiveDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    onRetry: () -> Unit,
    onSwitchDecoderMode: (VideoDecoderMode) -> Unit = {},
    onSkipBadSegment: () -> Unit = {},
    onForceHls: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 440.dp),
        shape = RoundedCornerShape(20.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, SignalRose.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = SignalRose,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = if (isDecoderError) "Video Decoder Error" else "Playback Interrupted",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isDecoderError) {
                val targetMode = if (effectiveDecoderMode == VideoDecoderMode.SOFTWARE) {
                    VideoDecoderMode.HARDWARE
                } else {
                    VideoDecoderMode.SOFTWARE
                }
                val buttonLabel = if (targetMode == VideoDecoderMode.SOFTWARE) {
                    stringResource(R.string.try_software_decoder)
                } else {
                    stringResource(R.string.try_hardware_decoder)
                }
                Button(
                    onClick = { onSwitchDecoderMode(targetMode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp), borderColor = Color.White)
                        .testTag("player_error_switch_decoder_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SubtitleEmerald,
                        contentColor = Color(0xFF061611)
                    )
                ) {
                    Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(buttonLabel)
                }
                OutlinedButton(
                    onClick = onSkipBadSegment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                        .testTag("player_error_skip_segment_button"),
                    border = BorderStroke(1.dp, SubtitleEmerald.copy(alpha = 0.55f))
                ) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = SubtitleEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.skip_bad_segment), color = SubtitleEmerald)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp)),
                    border = BorderStroke(1.dp, ObsidianOutline)
                ) {
                    Text("Back")
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp), borderColor = Color.White)
                        .testTag("player_error_retry_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
            if (currentFormat != StreamFormat.HLS && currentFormat != StreamFormat.M3U8) {
                OutlinedButton(
                    onClick = onForceHls,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                        .testTag("player_force_hls_button"),
                    border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.6f))
                ) {
                    Text("Try as HLS / M3U8 Stream", color = CinemaAmber)
                }
            }
        }
    }
}
