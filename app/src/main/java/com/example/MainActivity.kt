package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.home.HomeScreen
import com.example.ui.player.JsonPlaylistSheet
import com.example.ui.player.M3uPlaylistSheet
import com.example.ui.player.PlayerScreen
import com.example.ui.player.SeriesDetailSheet
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.CineNavigationEvent
import com.example.viewmodel.CineStreamViewModel

const val ROUTE_HOME = "home"
const val ROUTE_PLAYER = "player"

class MainActivity : ComponentActivity() {

    private val viewModel: CineStreamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        handleIncomingIntent(intent)

        setContent {
            CineStreamTheme {
                CineStreamApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                viewModel.handleExternalIntentUri(uri)
            }
        }
    }
}

@Composable
private fun CineStreamApp(viewModel: CineStreamViewModel) {
    val navController = rememberNavController()
    var showHomeM3uSheet by remember { mutableStateOf(false) }
    var showHomeJsonSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                CineNavigationEvent.OpenPlayer -> {
                    if (navController.currentDestination?.route != ROUTE_PLAYER) {
                        navController.navigate(ROUTE_PLAYER) {
                            launchSingleTop = true
                        }
                    }
                }
                CineNavigationEvent.OpenM3uSheet -> {
                    showHomeM3uSheet = true
                }
                CineNavigationEvent.OpenJsonSheet -> {
                    showHomeJsonSheet = true
                }
            }
        }
    }

    // Disable crossfade/slide transitions so PlayerScreen & HomeScreen never coexist in memory
    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(
            route = ROUTE_HOME,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val jsonPlaylistState by viewModel.jsonPlaylistState.collectAsStateWithLifecycle()
            val sampleStreams by viewModel.sampleStreams.collectAsStateWithLifecycle()
            val history by viewModel.history.collectAsStateWithLifecycle()

            HomeScreen(
                uiState = uiState,
                jsonPlaylistState = jsonPlaylistState,
                sampleStreams = sampleStreams,
                history = history,
                onUrlChanged = viewModel::onUrlInputChanged,
                onClearUrl = viewModel::clearUrlInput,
                onFormatSelected = viewModel::onFormatSelected,
                onPlayUrl = viewModel::launchFromUrlInput,
                onPickLocalVideo = viewModel::openLocalVideo,
                onPickLocalM3u = viewModel::openLocalM3uPlaylist,
                onPickLocalJson = viewModel::loadJsonPlaylistFromUri,
                onLoadJsonUrl = { url -> viewModel.loadJsonPlaylistFromUrl(url) },
                onRefreshJsonPlaylist = viewModel::refreshJsonPlaylist,
                onClearJsonPlaylist = viewModel::clearJsonPlaylist,
                onOpenJsonSheet = { showHomeJsonSheet = true },
                onPlayJsonItem = viewModel::playJsonPlaylistItem,
                onSelectJsonPlaylist = viewModel::selectJsonPlaylist,
                onRemoveJsonPlaylist = viewModel::removeJsonPlaylist,
                onStageLocalSubtitle = viewModel::stageLocalSubtitle,
                onStageRemoteSubtitle = viewModel::stageRemoteSubtitle,
                onClearStagedSubtitle = viewModel::clearStagedSubtitle,
                onPlaySample = viewModel::playSampleStream,
                onPlayHistoryItem = viewModel::playHistoryItem,
                onTogglePin = viewModel::togglePinHistoryItem,
                onDeleteHistory = viewModel::deleteHistoryItem,
                onOpenM3uSheet = { showHomeM3uSheet = true },
                onResumeActiveSession = viewModel::resumeActiveSession,
                onToastShown = viewModel::consumeToast
            )
        }

        composable(
            route = ROUTE_PLAYER,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val jsonPlaylistState by viewModel.jsonPlaylistState.collectAsStateWithLifecycle()
            val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
            val subtitleAppearance by viewModel.subtitleAppearance.collectAsStateWithLifecycle()
            val aspectRatioMode by viewModel.aspectRatioMode.collectAsStateWithLifecycle()
            val statsForNerdsEnabled by viewModel.statsForNerdsEnabled.collectAsStateWithLifecycle()

            val activeSeries = remember(jsonPlaylistState.playlists, jsonPlaylistState.items, uiState.activeSession?.seriesItemId) {
                val seriesId = uiState.activeSession?.seriesItemId ?: return@remember null
                jsonPlaylistState.allItems.firstOrNull { it.id == seriesId }
            }
            val hasNextEpisode = remember(activeSeries, uiState.activeSession?.episodeId) {
                activeSeries?.findNextEpisode(uiState.activeSession?.episodeId) != null
            }

            PlayerScreen(
                player = viewModel.playerManager.player,
                session = uiState.activeSession,
                playbackState = playbackState,
                progressStateFlow = viewModel.playbackProgressState,
                networkStatsFlow = viewModel.networkStats,
                statsForNerdsEnabled = statsForNerdsEnabled,
                subtitleAppearance = subtitleAppearance,
                aspectRatioMode = aspectRatioMode,
                m3uEntries = uiState.m3uEntries,
                activeM3uEntryId = uiState.activeM3uEntryId,
                jsonPlaylistLabel = jsonPlaylistState.sourceLabel,
                jsonPlaylistItems = jsonPlaylistState.items,
                jsonPlaylists = jsonPlaylistState.playlists,
                selectedJsonPlaylistId = jsonPlaylistState.selectedPlaylistId,
                activeJsonItemId = jsonPlaylistState.activeItemId,
                activeSeries = activeSeries,
                activeEpisodeId = uiState.activeSession?.episodeId,
                hasNextEpisode = hasNextEpisode,
                isEnrichingImdb = jsonPlaylistState.isEnrichingImdb,
                canRefreshJson = jsonPlaylistState.canRefreshRemote,
                toastMessage = uiState.toastMessage,
                onBack = {
                    // Stop playback and trim ExoPlayer buffers BEFORE popping back to HomeScreen
                    viewModel.exitPlayerAndReleaseBuffers()
                    navController.popBackStack()
                },
                onPauseAndSave = viewModel::pauseAndPersistProgress,
                onTogglePlayPause = viewModel.playerManager::togglePlayPause,
                onSeekTo = viewModel.playerManager::seekTo,
                onSeekBy = viewModel.playerManager::seekBy,
                onSetSpeed = viewModel.playerManager::setPlaybackSpeed,
                onSetVolumePercent = viewModel::setVolumePercent,
                onToggleStatsForNerds = viewModel::toggleStatsForNerds,
                onCycleAspectRatio = viewModel::cycleAspectRatio,
                onLoadSubtitleFile = viewModel::loadExternalSubtitleFileIntoPlayer,
                onLoadSubtitleUrl = viewModel::loadExternalSubtitleUrlIntoPlayer,
                onSelectSubtitleTrack = viewModel::selectSubtitleTrack,
                onDisableSubtitles = viewModel::disableSubtitles,
                onAdjustSubtitleOffset = viewModel::adjustSubtitleOffset,
                onResetSubtitleOffset = viewModel::resetSubtitleOffset,
                onUpdateSubtitleSize = viewModel::updateSubtitleTextSize,
                onUpdateSubtitleColor = viewModel::updateSubtitleColor,
                onUpdateSubtitleBg = viewModel::updateSubtitleBg,
                onSelectVideoTrack = viewModel::selectVideoTrack,
                onSelectAudioTrack = viewModel::selectAudioTrack,
                onRetryFormat = viewModel::retryWithFormat,
                onSelectM3uEntry = viewModel::playM3uEntry,
                onSelectJsonItem = viewModel::playJsonPlaylistItem,
                onSelectJsonPlaylist = viewModel::selectJsonPlaylist,
                onRemoveJsonPlaylist = viewModel::removeJsonPlaylist,
                onAddJsonPlaylistUrl = { url -> viewModel.loadJsonPlaylistFromUrl(url, openSheetOnSuccess = false) },
                onSelectSeriesEpisode = viewModel::playSeriesEpisode,
                onPlayNextEpisode = viewModel::playNextEpisode,
                onSwitchCdnMirror = viewModel::switchToNextCdnMirror,
                onClearEdgeCache = viewModel::clearMediaEdgeCache,
                onSelectDecoderMode = viewModel::setVideoDecoderMode,
                onSelectTransportMode = viewModel::setNetworkTransportMode,
                onRetryWithDecoderMode = viewModel::retryWithDecoderMode,
                onSkipBadSegment = viewModel::skipBadSegmentAndRetry,
                onRefreshJsonPlaylist = viewModel::refreshJsonPlaylist,
                onToastShown = viewModel::consumeToast
            )
        }
    }

    if (showHomeM3uSheet) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        if (uiState.m3uEntries.isNotEmpty()) {
            M3uPlaylistSheet(
                entries = uiState.m3uEntries,
                activeEntryId = uiState.activeM3uEntryId,
                onDismiss = { showHomeM3uSheet = false },
                onSelectEntry = viewModel::playM3uEntry
            )
        }
    }

    if (showHomeJsonSheet) {
        val jsonPlaylistState by viewModel.jsonPlaylistState.collectAsStateWithLifecycle()
        if (jsonPlaylistState.allItems.isNotEmpty()) {
            JsonPlaylistSheet(
                sourceLabel = jsonPlaylistState.sourceLabel,
                items = jsonPlaylistState.items,
                activeItemId = jsonPlaylistState.activeItemId,
                isEnrichingImdb = jsonPlaylistState.isEnrichingImdb,
                canRefreshUrl = jsonPlaylistState.canRefreshRemote,
                onRefresh = viewModel::refreshJsonPlaylist,
                onDismiss = { showHomeJsonSheet = false },
                onSelectItem = viewModel::playJsonPlaylistItem,
                playlists = jsonPlaylistState.playlists,
                selectedPlaylistId = jsonPlaylistState.selectedPlaylistId,
                onSelectPlaylist = viewModel::selectJsonPlaylist,
                onRemovePlaylist = viewModel::removeJsonPlaylist,
                onAddPlaylistUrl = { url -> viewModel.loadJsonPlaylistFromUrl(url, openSheetOnSuccess = false) }
            )
        } else {
            showHomeJsonSheet = false
        }
    }

    val globalJsonState by viewModel.jsonPlaylistState.collectAsStateWithLifecycle()
    val selectedSeries = globalJsonState.selectedSeriesForSheet
    if (selectedSeries != null) {
        SeriesDetailSheet(
            series = selectedSeries,
            activeEpisodeId = globalJsonState.activeEpisodeId,
            onDismiss = viewModel::dismissSeriesSheet,
            onSelectEpisode = viewModel::playSeriesEpisode
        )
    }
}
