package com.example.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.MediaHistoryEntity
import com.example.model.JsonPlaylistItem
import com.example.model.SampleStream
import com.example.model.StreamFormat
import com.example.ui.components.FormatBadge
import com.example.ui.components.ImdbBadgePill
import com.example.ui.components.LocalFilePickKind
import com.example.ui.components.PosterStreamCard
import com.example.ui.components.SubtitlePillBadge
import com.example.ui.components.formatDuration
import com.example.ui.components.rememberIsAndroidTv
import com.example.ui.components.rememberLocalFilePicker
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.viewmodel.CineStreamUiState
import com.example.viewmodel.JsonPlaylistUiState
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    uiState: CineStreamUiState,
    jsonPlaylistState: JsonPlaylistUiState,
    sampleStreams: List<SampleStream>,
    history: List<MediaHistoryEntity>,
    onUrlChanged: (String) -> Unit,
    onClearUrl: () -> Unit,
    onFormatSelected: (StreamFormat) -> Unit,
    onPlayUrl: () -> Unit,
    onPickLocalVideo: (Uri) -> Unit,
    onPickLocalM3u: (Uri) -> Unit,
    onPickLocalJson: (Uri) -> Unit,
    onLoadJsonUrl: (String) -> Unit,
    onRefreshJsonPlaylist: () -> Unit,
    onClearJsonPlaylist: () -> Unit,
    onOpenJsonSheet: () -> Unit,
    onPlayJsonItem: (JsonPlaylistItem) -> Unit,
    onSelectJsonPlaylist: (String?) -> Unit = {},
    onRemoveJsonPlaylist: (String) -> Unit = {},
    onStageLocalSubtitle: (Uri) -> Unit,
    onStageRemoteSubtitle: (String) -> Unit,
    onClearStagedSubtitle: () -> Unit,
    onPlaySample: (SampleStream) -> Unit,
    onPlayHistoryItem: (MediaHistoryEntity) -> Unit,
    onTogglePin: (MediaHistoryEntity) -> Unit,
    onDeleteHistory: (MediaHistoryEntity) -> Unit,
    onOpenM3uSheet: () -> Unit,
    onResumeActiveSession: () -> Unit,
    onToastShown: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val isTv = rememberIsAndroidTv()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showSubtitleUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showJsonUrlDialog by rememberSaveable { mutableStateOf(false) }

    val filePicker = rememberLocalFilePicker(
        onVideoPicked = onPickLocalVideo,
        onM3uPicked = onPickLocalM3u,
        onJsonPicked = onPickLocalJson,
        onSubtitlePicked = onStageLocalSubtitle,
        onPickerUnavailable = { kind ->
            when (kind) {
                LocalFilePickKind.SUBTITLE -> {
                    showSubtitleUrlDialog = true
                }
                LocalFilePickKind.JSON_PLAYLIST -> {
                    showJsonUrlDialog = true
                }
                LocalFilePickKind.VIDEO -> {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            "No file picker app found on this device. Install a file manager or paste a video link above."
                        )
                    }
                }
                LocalFilePickKind.M3U_PLAYLIST -> {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            "No file picker app found on this device. Paste your .m3u/.m3u8 playlist URL above."
                        )
                    }
                }
            }
        }
    )

    val launchSafeVideoPicker: () -> Unit = { filePicker.pickVideo() }
    val launchSafeM3uPicker: () -> Unit = { filePicker.pickM3uPlaylist() }
    val launchSafeJsonPicker: () -> Unit = { filePicker.pickJsonPlaylist() }
    val launchSafeSubtitlePicker: () -> Unit = { filePicker.pickSubtitle() }

    val pasteFromClipboard: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!clipText.isNullOrBlank()) {
            onUrlChanged(clipText.trim())
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        val msg = uiState.toastMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onToastShown()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val useWidescreenTvLayout = isTv || maxWidth >= 840.dp

            if (useWidescreenTvLayout) {
                // Widescreen / Android TV 2-Column 10-Foot Dashboard with overscan-safe margins
                val outerHorizontalPad = if (isTv) 36.dp else 24.dp
                val outerVerticalPad = if (isTv) 24.dp else 16.dp

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = outerHorizontalPad, vertical = outerVerticalPad),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Left Column: Brand Hero + Stream Launcher
                    Column(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TvCompactHeroHeader(
                            isTv = isTv,
                            hasActiveSession = uiState.activeSession != null,
                            activeTitle = uiState.activeSession?.title,
                            onResumeActiveSession = onResumeActiveSession
                        )
                        StreamLauncherCard(
                            uiState = uiState,
                            jsonPlaylistState = jsonPlaylistState,
                            onUrlChanged = onUrlChanged,
                            onClearUrl = onClearUrl,
                            onPasteClipboard = pasteFromClipboard,
                            onFormatSelected = onFormatSelected,
                            onPlayUrl = {
                                focusManager.clearFocus()
                                onPlayUrl()
                            },
                            onPickLocalVideo = launchSafeVideoPicker,
                            onPickLocalM3u = launchSafeM3uPicker,
                            onPickLocalJson = launchSafeJsonPicker,
                            onOpenJsonUrlDialog = { showJsonUrlDialog = true },
                            onOpenJsonSheet = onOpenJsonSheet,
                            onRefreshJsonPlaylist = onRefreshJsonPlaylist,
                            onClearJsonPlaylist = onClearJsonPlaylist,
                            onPickLocalSubtitle = launchSafeSubtitlePicker,
                            onOpenSubtitleUrlDialog = { showSubtitleUrlDialog = true },
                            onClearStagedSubtitle = onClearStagedSubtitle,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (uiState.m3uEntries.isNotEmpty()) {
                            M3uLoadedBanner(
                                entryCount = uiState.m3uEntries.size,
                                onClick = onOpenM3uSheet,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Right Column: JSON IMDb Catalog + Quick Test Streams + Recent Playback
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (jsonPlaylistState.allItems.isNotEmpty()) {
                            item(key = "tv_json_header") {
                                JsonCatalogSectionHeader(
                                    title = jsonPlaylistState.sourceLabel
                                        ?: stringResource(R.string.section_json_playlist),
                                    count = jsonPlaylistState.items.size,
                                    isEnrichingImdb = jsonPlaylistState.isEnrichingImdb,
                                    onBrowseAll = onOpenJsonSheet
                                )
                            }
                            if (jsonPlaylistState.playlists.size > 1) {
                                item(key = "tv_json_playlist_switcher") {
                                    JsonPlaylistSwitcherBar(
                                        playlists = jsonPlaylistState.playlists,
                                        selectedPlaylistId = jsonPlaylistState.selectedPlaylistId,
                                        onSelectPlaylist = onSelectJsonPlaylist,
                                        onRemovePlaylist = onRemoveJsonPlaylist,
                                        testTagPrefix = "home_playlist_chip"
                                    )
                                }
                            }
                            item(key = "tv_json_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(jsonPlaylistState.items, key = { it.id }) { item ->
                                        PosterStreamCard(
                                            title = item.title,
                                            subtitle = item.description?.takeIf { it.isNotBlank() } ?: item.groupTitle,
                                            imageUrl = item.imageUrl,
                                            format = item.format,
                                            imdbId = item.imdbId,
                                            imdbRating = item.imdbRating,
                                            releaseYear = item.releaseYear,
                                            seriesBadge = item.seriesBadgeLabel,
                                            hasSubtitle = !item.subtitleUrl.isNullOrBlank(),
                                            isActive = item.id == jsonPlaylistState.activeItemId,
                                            testTag = "json_poster_${item.id}",
                                            onClick = { onPlayJsonItem(item) }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "tv_samples_header") {
                            SectionHeader(
                                title = stringResource(R.string.section_quick_samples),
                                badge = "IMDb · HLS · MP4",
                                modifier = if (jsonPlaylistState.items.isNotEmpty()) Modifier.padding(top = 10.dp) else Modifier
                            )
                        }
                        item(key = "tv_samples_row") {
                            LazyRow(
                                contentPadding = PaddingValues(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(sampleStreams, key = { it.url }) { sample ->
                                    SampleStreamCard(
                                        sample = sample,
                                        onClick = { onPlaySample(sample) }
                                    )
                                }
                            }
                        }

                        item(key = "tv_recent_header") {
                            SectionHeader(
                                title = stringResource(R.string.section_recent_streams),
                                badge = if (history.isNotEmpty()) "${history.size}" else null,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        if (history.isEmpty()) {
                            item(key = "tv_empty_recent") {
                                EmptyHistoryCard()
                            }
                        } else {
                            items(history, key = { it.id }) { item ->
                                HistoryStreamCard(
                                    item = item,
                                    onClick = { onPlayHistoryItem(item) },
                                    onTogglePin = { onTogglePin(item) },
                                    onDelete = { onDeleteHistory(item) }
                                )
                            }
                        }
                    }
                }
            } else {
                // Handheld Mobile Vertical Cinema Layout
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 720.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item(key = "hero") {
                            CinemaHeroHeader(
                                hasActiveSession = uiState.activeSession != null,
                                activeTitle = uiState.activeSession?.title,
                                onResumeActiveSession = onResumeActiveSession
                            )
                        }

                        item(key = "launcher") {
                            StreamLauncherCard(
                                uiState = uiState,
                                jsonPlaylistState = jsonPlaylistState,
                                onUrlChanged = onUrlChanged,
                                onClearUrl = onClearUrl,
                                onPasteClipboard = pasteFromClipboard,
                                onFormatSelected = onFormatSelected,
                                onPlayUrl = {
                                    focusManager.clearFocus()
                                    onPlayUrl()
                                },
                                onPickLocalVideo = launchSafeVideoPicker,
                                onPickLocalM3u = launchSafeM3uPicker,
                                onPickLocalJson = launchSafeJsonPicker,
                                onOpenJsonUrlDialog = { showJsonUrlDialog = true },
                                onOpenJsonSheet = onOpenJsonSheet,
                                onRefreshJsonPlaylist = onRefreshJsonPlaylist,
                                onClearJsonPlaylist = onClearJsonPlaylist,
                                onPickLocalSubtitle = launchSafeSubtitlePicker,
                                onOpenSubtitleUrlDialog = { showSubtitleUrlDialog = true },
                                onClearStagedSubtitle = onClearStagedSubtitle,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        if (uiState.m3uEntries.isNotEmpty()) {
                            item(key = "m3u_banner") {
                                M3uLoadedBanner(
                                    entryCount = uiState.m3uEntries.size,
                                    onClick = onOpenM3uSheet,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        if (jsonPlaylistState.allItems.isNotEmpty()) {
                            item(key = "json_header") {
                                JsonCatalogSectionHeader(
                                    title = jsonPlaylistState.sourceLabel
                                        ?: stringResource(R.string.section_json_playlist),
                                    count = jsonPlaylistState.items.size,
                                    isEnrichingImdb = jsonPlaylistState.isEnrichingImdb,
                                    onBrowseAll = onOpenJsonSheet,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                                )
                            }
                            if (jsonPlaylistState.playlists.size > 1) {
                                item(key = "json_playlist_switcher") {
                                    JsonPlaylistSwitcherBar(
                                        playlists = jsonPlaylistState.playlists,
                                        selectedPlaylistId = jsonPlaylistState.selectedPlaylistId,
                                        onSelectPlaylist = onSelectJsonPlaylist,
                                        onRemovePlaylist = onRemoveJsonPlaylist,
                                        testTagPrefix = "home_playlist_chip",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            item(key = "json_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(jsonPlaylistState.items, key = { it.id }) { item ->
                                        PosterStreamCard(
                                            title = item.title,
                                            subtitle = item.description?.takeIf { it.isNotBlank() } ?: item.groupTitle,
                                            imageUrl = item.imageUrl,
                                            format = item.format,
                                            imdbId = item.imdbId,
                                            imdbRating = item.imdbRating,
                                            releaseYear = item.releaseYear,
                                            seriesBadge = item.seriesBadgeLabel,
                                            hasSubtitle = !item.subtitleUrl.isNullOrBlank(),
                                            isActive = item.id == jsonPlaylistState.activeItemId,
                                            testTag = "json_poster_${item.id}",
                                            onClick = { onPlayJsonItem(item) }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "samples_header") {
                            SectionHeader(
                                title = stringResource(R.string.section_quick_samples),
                                badge = "IMDb · HLS · MP4",
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp)
                            )
                        }
                        item(key = "samples_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(sampleStreams, key = { it.url }) { sample ->
                                    SampleStreamCard(
                                        sample = sample,
                                        onClick = { onPlaySample(sample) }
                                    )
                                }
                            }
                        }

                        item(key = "recent_header") {
                            SectionHeader(
                                title = stringResource(R.string.section_recent_streams),
                                badge = if (history.isNotEmpty()) "${history.size}" else null,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp)
                            )
                        }

                        if (history.isEmpty()) {
                            item(key = "empty_recent") {
                                EmptyHistoryCard(
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        } else {
                            items(history, key = { it.id }) { item ->
                                HistoryStreamCard(
                                    item = item,
                                    onClick = { onPlayHistoryItem(item) },
                                    onTogglePin = { onTogglePin(item) },
                                    onDelete = { onDeleteHistory(item) },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                                )
                            }
                        }

                        item(key = "bottom_inset_spacer") {
                            Spacer(
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .height(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSubtitleUrlDialog) {
        SubtitleUrlInputDialog(
            onDismiss = { showSubtitleUrlDialog = false },
            onConfirm = { url ->
                onStageRemoteSubtitle(url)
                showSubtitleUrlDialog = false
            }
        )
    }

    if (showJsonUrlDialog) {
        JsonPlaylistUrlInputDialog(
            initialUrl = "",
            existingPlaylistCount = jsonPlaylistState.playlists.size,
            onDismiss = { showJsonUrlDialog = false },
            onConfirm = { url ->
                onLoadJsonUrl(url)
                showJsonUrlDialog = false
            }
        )
    }
}

@Composable
private fun TvCompactHeroHeader(
    isTv: Boolean,
    hasActiveSession: Boolean,
    activeTitle: String?,
    onResumeActiveSession: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp),
        shape = MaterialTheme.shapes.large,
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val heroContext = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(heroContext)
                    .data(R.drawable.img_cinema_hero_1795091025530)
                    .size(640, 360)
                    .allowRgb565(true)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                ObsidianCanvas.copy(alpha = 0.92f),
                                ObsidianCanvas.copy(alpha = 0.65f),
                                ObsidianCanvas.copy(alpha = 0.35f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CinemaAmber.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = if (isTv) Icons.Rounded.Tv else Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = CinemaAmber,
                                modifier = Modifier.padding(7.dp).size(20.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    if (hasActiveSession && !activeTitle.isNullOrBlank()) {
                        Surface(
                            onClick = onResumeActiveSession,
                            shape = RoundedCornerShape(20.dp),
                            color = CinemaAmber.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .tvFocusHighlight(shape = RoundedCornerShape(20.dp))
                                .testTag("resume_active_player_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = CinemaAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Now Playing",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CinemaAmber
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.labelMedium,
                        color = CinemaAmber
                    )
                    Text(
                        text = stringResource(R.string.hero_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun CinemaHeroHeader(
    hasActiveSession: Boolean,
    activeTitle: String?,
    onResumeActiveSession: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp)
    ) {
        val heroContext = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(heroContext)
                .data(R.drawable.img_cinema_hero_1795091025530)
                .size(640, 360)
                .allowRgb565(true)
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ObsidianCanvas.copy(alpha = 0.45f),
                            ObsidianCanvas.copy(alpha = 0.72f),
                            ObsidianCanvas
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = CinemaAmber.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = CinemaAmber,
                            modifier = Modifier.padding(7.dp).size(20.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (hasActiveSession && !activeTitle.isNullOrBlank()) {
                    Surface(
                        onClick = onResumeActiveSession,
                        shape = RoundedCornerShape(20.dp),
                        color = CinemaAmber.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .tvFocusHighlight(shape = RoundedCornerShape(20.dp))
                            .testTag("resume_active_player_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = CinemaAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Now Playing",
                                style = MaterialTheme.typography.labelMedium,
                                color = CinemaAmber
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                    color = CinemaAmber
                )
                Text(
                    text = stringResource(R.string.hero_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun StreamLauncherCard(
    uiState: CineStreamUiState,
    jsonPlaylistState: JsonPlaylistUiState,
    onUrlChanged: (String) -> Unit,
    onClearUrl: () -> Unit,
    onPasteClipboard: () -> Unit,
    onFormatSelected: (StreamFormat) -> Unit,
    onPlayUrl: () -> Unit,
    onPickLocalVideo: () -> Unit,
    onPickLocalM3u: () -> Unit,
    onPickLocalJson: () -> Unit,
    onOpenJsonUrlDialog: () -> Unit,
    onOpenJsonSheet: () -> Unit,
    onRefreshJsonPlaylist: () -> Unit,
    onClearJsonPlaylist: () -> Unit,
    onPickLocalSubtitle: () -> Unit,
    onOpenSubtitleUrlDialog: () -> Unit,
    onClearStagedSubtitle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianOutline),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = uiState.urlInput,
                onValueChange = onUrlChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusHighlight(
                        shape = RoundedCornerShape(14.dp),
                        focusedScale = 1.01f,
                        glowAlpha = 0.08f,
                        includeChildFocus = true
                    )
                    .testTag("stream_url_input"),
                label = { Text(stringResource(R.string.stream_url_label)) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.stream_url_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = CinemaAmber
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.urlInput.isNotEmpty()) {
                            IconButton(
                                onClick = onClearUrl,
                                modifier = Modifier
                                    .tvFocusHighlight(shape = CircleShape)
                                    .testTag("clear_url_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_clear)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onPasteClipboard,
                                modifier = Modifier
                                    .tvFocusHighlight(shape = CircleShape)
                                    .testTag("paste_url_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = stringResource(R.string.action_paste)
                                )
                            }
                        }
                    }
                },
                isError = uiState.inputError != null,
                supportingText = uiState.inputError?.let { err ->
                    { Text(err, color = MaterialTheme.colorScheme.error) }
                } ?: if (uiState.urlInput.isNotBlank() && uiState.selectedFormat == StreamFormat.AUTO && uiState.detectedFormat != StreamFormat.AUTO) {
                    {
                        Text(
                            text = "Detected: ${uiState.detectedFormat.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { onPlayUrl() }),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CinemaAmber,
                    unfocusedBorderColor = ObsidianOutline,
                    focusedContainerColor = ObsidianCanvas.copy(alpha = 0.7f),
                    unfocusedContainerColor = ObsidianCanvas.copy(alpha = 0.5f)
                )
            )

            // Format Selector Strip
            val formatChoices = listOf(
                StreamFormat.AUTO,
                StreamFormat.HLS,
                StreamFormat.MKV,
                StreamFormat.MP4,
                StreamFormat.M3U
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                formatChoices.forEach { format ->
                    val selected = uiState.selectedFormat == format
                    FilterChip(
                        selected = selected,
                        onClick = { onFormatSelected(format) },
                        label = {
                            Text(
                                text = when (format) {
                                    StreamFormat.AUTO -> stringResource(R.string.format_auto)
                                    StreamFormat.HLS -> stringResource(R.string.format_hls)
                                    StreamFormat.MKV -> stringResource(R.string.format_mkv)
                                    StreamFormat.MP4 -> stringResource(R.string.format_mp4)
                                    StreamFormat.M3U -> stringResource(R.string.format_m3u)
                                    else -> format.badge
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier
                            .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.04f)
                            .testTag("format_chip_${format.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CinemaAmber.copy(alpha = 0.18f),
                            selectedLabelColor = CinemaAmber
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = ObsidianOutline,
                            selectedBorderColor = CinemaAmber.copy(alpha = 0.65f)
                        )
                    )
                }
            }

            // Subtitle Pre-Load Attachment Row
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurfaceHigh,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (uiState.stagedSubtitle != null) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
                    } else {
                        ObsidianOutline.copy(alpha = 0.65f)
                    }
                )
            ) {
                val staged = uiState.stagedSubtitle
                if (staged != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Subtitles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = staged.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "External ${staged.formatBadge} · Will load on any video format",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        IconButton(
                            onClick = onClearStagedSubtitle,
                            modifier = Modifier
                                .tvFocusHighlight(shape = CircleShape)
                                .testTag("remove_staged_subtitle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Subtitles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Optional Subtitle",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = onPickLocalSubtitle,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                    .testTag("attach_local_subtitle_button")
                            ) {
                                Text("File", color = MaterialTheme.colorScheme.tertiary)
                            }
                            TextButton(
                                onClick = onOpenSubtitleUrlDialog,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                    .testTag("attach_url_subtitle_button")
                            ) {
                                Text("URL", color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }

            // JSON Playlist (IMDb Poster Catalog) URL / File Row
            JsonPlaylistLauncherRow(
                state = jsonPlaylistState,
                onPickJsonFile = onPickLocalJson,
                onOpenJsonUrlDialog = onOpenJsonUrlDialog,
                onOpenJsonSheet = onOpenJsonSheet,
                onRefreshJsonPlaylist = onRefreshJsonPlaylist,
                onClearJsonPlaylist = onClearJsonPlaylist
            )

            // Primary Play Stream Button
            Button(
                onClick = onPlayUrl,
                enabled = !uiState.isParsingPlaylist,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .tvFocusHighlight(
                        shape = RoundedCornerShape(14.dp),
                        borderColor = MaterialTheme.colorScheme.onBackground,
                        focusedScale = 1.02f
                    )
                    .testTag("play_stream_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CinemaAmber,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (uiState.isParsingPlaylist) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Inspecting Playlist…", style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_play_stream),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Secondary Local Video & M3U File Pickers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPickLocalVideo,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .tvFocusHighlight(shape = RoundedCornerShape(14.dp))
                        .testTag("open_local_video_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ObsidianOutline)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideoFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_open_local_video),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onPickLocalM3u,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .tvFocusHighlight(shape = RoundedCornerShape(14.dp))
                        .testTag("open_local_m3u_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ObsidianOutline)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_open_m3u_file),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun M3uLoadedBanner(
    entryCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusHighlight(shape = RoundedCornerShape(14.dp))
            .testTag("open_loaded_m3u_banner"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "M3U Playlist Loaded",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "$entryCount playable channels · Select to browse & switch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            FormatBadge(format = StreamFormat.M3U)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    badge: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JsonCatalogSectionHeader(
    title: String,
    count: Int,
    isEnrichingImdb: Boolean,
    onBrowseAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            ImdbBadgePill(rating = null, imdbId = "tt0000001")
            if (isEnrichingImdb) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = CinemaAmber
                )
            }
        }
        TextButton(
            onClick = onBrowseAll,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier
                .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                .testTag("browse_json_catalog_button")
        ) {
            Text(
                text = "All ($count)",
                color = CinemaAmber,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun SampleStreamCard(
    sample: SampleStream,
    onClick: () -> Unit
) {
    PosterStreamCard(
        title = sample.title,
        subtitle = sample.subtitleHint,
        imageUrl = sample.posterUrl,
        format = sample.format,
        imdbId = sample.imdbId,
        imdbRating = sample.imdbRating,
        releaseYear = sample.releaseYear,
        testTag = "sample_stream_${sample.format.name.lowercase()}",
        onClick = onClick
    )
}

@Composable
private fun HistoryStreamCard(
    item: MediaHistoryEntity,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val format = runCatching { StreamFormat.valueOf(item.formatName) }.getOrDefault(StreamFormat.AUTO)
    val progress = if (item.durationMs > 0L) {
        (item.lastPositionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusHighlight(shape = RoundedCornerShape(16.dp), focusedScale = 1.02f)
            .testTag("history_item_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        color = ObsidianSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (item.isPinned) CinemaAmber.copy(alpha = 0.4f) else ObsidianOutline
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ObsidianSurfaceHigh,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = CinemaAmber,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (item.isLocalFile) "Local Device File" else item.sourceUri,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("pin_history_${item.id}")
                ) {
                    Icon(
                        imageVector = if (item.isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.cd_pin_item),
                        tint = if (item.isPinned) CinemaAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .tvFocusHighlight(shape = CircleShape)
                        .testTag("delete_history_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.cd_delete_item),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormatBadge(format = format)
                if (!item.subtitleName.isNullOrBlank()) {
                    SubtitlePillBadge(text = "CC · ${item.subtitleName.take(18)}")
                }
                Spacer(Modifier.weight(1f))
                if (item.durationMs > 0L) {
                    Text(
                        text = "${formatDuration(item.lastPositionMs)} / ${formatDuration(item.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (progress > 0.01f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = CinemaAmber,
                    trackColor = ObsidianSurfaceHigh
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = CinemaAmber.copy(alpha = 0.12f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = CinemaAmber,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.empty_recent_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.empty_recent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun SubtitleUrlInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        title = {
            Text(
                text = stringResource(R.string.load_subtitle_url),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Supports .srt, .vtt, .ass, .ssa, and .ttml subtitle links on MKV, MP4, M3U8, and HLS streams.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.subtitle_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_subtitle_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
                    .testTag("dialog_subtitle_url_confirm")
            ) {
                Text("Attach")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusHighlight(shape = RoundedCornerShape(12.dp))
            ) {
                Text("Cancel")
            }
        }
    )
}
