package com.example

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.rememberCoroutineScope
import com.example.player.PlayerPlaybackState
import com.example.ui.home.HomeScreen
import com.example.ui.player.DownloadsSheet
import com.example.ui.player.JsonPlaylistSheet
import com.example.ui.player.LocalNetworkSheet
import com.example.ui.player.M3uPlaylistSheet
import com.example.ui.player.NetworkPrivacySheet
import com.example.ui.player.PlayerScreen
import com.example.ui.player.SeriesDetailSheet
import com.example.ui.remote.WebRemoteDialog
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.CineNavigationEvent
import com.example.viewmodel.CineStreamViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ROUTE_HOME = "home"
const val ROUTE_PLAYER = "player"

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_PIP_CONTROL = "com.example.action.PIP_CONTROL"
        private const val EXTRA_PIP_COMMAND = "pip_command"
        private const val PIP_CMD_REWIND = "REWIND"
        private const val PIP_CMD_TOGGLE = "TOGGLE"
        private const val PIP_CMD_FORWARD = "FORWARD"
        private const val REQ_PIP_REWIND = 301
        private const val REQ_PIP_TOGGLE = 302
        private const val REQ_PIP_FORWARD = 303
    }

    private val viewModel: CineStreamViewModel by viewModels()

    private val isTelevisionDevice: Boolean by lazy {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as? UiModeManager
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private var isInPipModeState by mutableStateOf(false)
    private var currentRoute: String = ROUTE_HOME
    private var pipReceiverRegistered = false

    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            val stepMs = viewModel.seekIntervalSeconds.value.coerceIn(5, 30) * 1_000L
            when (intent.getStringExtra(EXTRA_PIP_COMMAND)) {
                PIP_CMD_REWIND -> viewModel.playerManager.seekBy(-stepMs)
                PIP_CMD_TOGGLE -> {
                    viewModel.playerManager.togglePlayPause()
                    updatePipParamsIfActive()
                }
                PIP_CMD_FORWARD -> viewModel.playerManager.seekBy(stepMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        if (!isTelevisionDevice) {
            ContextCompat.registerReceiver(
                this,
                pipControlReceiver,
                IntentFilter(ACTION_PIP_CONTROL),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            pipReceiverRegistered = true
        }

        handleIncomingIntent(intent)

        setContent {
            CineStreamTheme {
                CineStreamApp(
                    viewModel = viewModel,
                    isTvDevice = isTelevisionDevice,
                    isInPipMode = isInPipModeState,
                    onRouteChanged = { route -> currentRoute = route },
                    onEnterPip = { enterPlayerPip() },
                    onUpdatePipParams = { updatePipParamsIfActive() }
                )
            }
        }
    }

    override fun onDestroy() {
        if (pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipControlReceiver) }
            pipReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isTelevisionDevice &&
            currentRoute == ROUTE_PLAYER &&
            !viewModel.backgroundAudioEnabled.value &&
            viewModel.playbackState.value.isPlaying
        ) {
            enterPlayerPip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            updatePipParamsIfActive()
        }
    }

    private fun enterPlayerPip() {
        if (isTelevisionDevice || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (currentRoute != ROUTE_PLAYER || !viewModel.playerManager.hasLoadedMedia()) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(buildPipParams(viewModel.playbackState.value))
        }
    }

    private fun updatePipParamsIfActive() {
        if (isTelevisionDevice || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPipModeState) return
        runCatching {
            setPictureInPictureParams(buildPipParams(viewModel.playbackState.value))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(playbackState: PlayerPlaybackState): PictureInPictureParams {
        val aspectRatio = computeClampedPipRational(
            width = playbackState.videoWidth,
            height = playbackState.videoHeight
        )
        val rewindAction = createPipAction(
            iconRes = android.R.drawable.ic_media_rew,
            title = "Rewind 10s",
            command = PIP_CMD_REWIND,
            requestCode = REQ_PIP_REWIND
        )
        val toggleAction = createPipAction(
            iconRes = if (playbackState.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            title = if (playbackState.isPlaying) "Pause" else "Play",
            command = PIP_CMD_TOGGLE,
            requestCode = REQ_PIP_TOGGLE
        )
        val forwardAction = createPipAction(
            iconRes = android.R.drawable.ic_media_ff,
            title = "Forward 10s",
            command = PIP_CMD_FORWARD,
            requestCode = REQ_PIP_FORWARD
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(listOf(rewindAction, toggleAction, forwardAction))
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipAction(
        iconRes: Int,
        title: String,
        command: String,
        requestCode: Int
    ): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).apply {
            setPackage(packageName)
            putExtra(EXTRA_PIP_COMMAND, command)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent
        )
    }

    private fun computeClampedPipRational(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio < 0.45f -> Rational(9, 20)
            ratio > 2.35f -> Rational(235, 100)
            else -> Rational(width, height)
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
private fun CineStreamApp(
    viewModel: CineStreamViewModel,
    isTvDevice: Boolean,
    isInPipMode: Boolean,
    onRouteChanged: (String) -> Unit,
    onEnterPip: () -> Unit,
    onUpdatePipParams: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    var showHomeM3uSheet by remember { mutableStateOf(false) }
    var showHomeJsonSheet by remember { mutableStateOf(false) }
    var showPrivacySheet by remember { mutableStateOf(false) }
    var showWebRemoteDialog by remember { mutableStateOf(false) }
    var showDownloadsSheet by remember { mutableStateOf(false) }
    var showLocalNetworkSheet by remember { mutableStateOf(false) }
    val openPrivacySheet: () -> Unit = {
        viewModel.refreshSystemVpnStatus()
        showPrivacySheet = true
    }

    val syncExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val json = viewModel.exportDeviceSyncSnapshotJson()
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    }
                }
            }
        }
    }

    val syncImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (!raw.isNullOrBlank()) {
                    viewModel.importDeviceSyncSnapshotJson(raw)
                }
            }
        }
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(granted = result.resultCode == Activity.RESULT_OK)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Enable Background Audio gracefully whether notification permission was granted or denied
        viewModel.setBackgroundAudioEnabled(true)
    }

    val handleToggleBackgroundAudio: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            viewModel.setBackgroundAudioEnabled(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setBackgroundAudioEnabled(true)
        }
    }

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            onRouteChanged(entry.destination.route ?: ROUTE_HOME)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.vpnConsentEvents.collect { consentIntent ->
            vpnConsentLauncher.launch(consentIntent)
        }
    }

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
            val networkPrivacyState by viewModel.networkPrivacyState.collectAsStateWithLifecycle()
            val sampleStreams by viewModel.sampleStreams.collectAsStateWithLifecycle()
            val history by viewModel.history.collectAsStateWithLifecycle()
            val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
            val favorites by viewModel.favorites.collectAsStateWithLifecycle()
            val globalSearchQuery by viewModel.globalSearchQuery.collectAsStateWithLifecycle()
            val globalSearchResults by viewModel.globalSearchResults.collectAsStateWithLifecycle()
            val offlineDownloads by viewModel.offlineDownloads.collectAsStateWithLifecycle()
            val networkServers by viewModel.networkServers.collectAsStateWithLifecycle()

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
                onToastShown = viewModel::consumeToast,
                networkPrivacyState = networkPrivacyState,
                onOpenNetworkPrivacy = openPrivacySheet,
                continueWatching = continueWatching,
                onDismissContinueWatching = viewModel::dismissContinueWatchingItem,
                favorites = favorites,
                onPlayFavorite = viewModel::playFavoriteItem,
                onRemoveFavorite = { fav ->
                    viewModel.toggleFavorite(
                        mediaKey = fav.mediaKey,
                        title = fav.title,
                        sourceUri = fav.sourceUri,
                        itemType = fav.itemType
                    )
                },
                globalSearchQuery = globalSearchQuery,
                globalSearchResults = globalSearchResults,
                onGlobalSearchQueryChange = viewModel::updateGlobalSearchQuery,
                onGlobalSearchResultClick = viewModel::openGlobalSearchResult,
                downloadsCount = offlineDownloads.size,
                networkServersCount = networkServers.size,
                onOpenWebRemote = { showWebRemoteDialog = true },
                onOpenLocalNetwork = { showLocalNetworkSheet = true },
                onOpenDownloads = { showDownloadsSheet = true }
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
            val networkPrivacyState by viewModel.networkPrivacyState.collectAsStateWithLifecycle()
            val backgroundAudioEnabled by viewModel.backgroundAudioEnabled.collectAsStateWithLifecycle()
            val episodeProgressMap by viewModel.episodeProgressMap.collectAsStateWithLifecycle()
            val seekIntervalSeconds by viewModel.seekIntervalSeconds.collectAsStateWithLifecycle()
            val nightModeAudioEnabled by viewModel.nightModeAudioEnabled.collectAsStateWithLifecycle()
            val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
            val preferredAudioLanguage by viewModel.preferredAudioLanguage.collectAsStateWithLifecycle()
            val preferredSubtitleLanguage by viewModel.preferredSubtitleLanguage.collectAsStateWithLifecycle()
            val abLoopState by viewModel.abLoopState.collectAsStateWithLifecycle()
            val chapters by viewModel.chapters.collectAsStateWithLifecycle()
            val upNextCountdown by viewModel.upNextCountdown.collectAsStateWithLifecycle()
            val favorites by viewModel.favorites.collectAsStateWithLifecycle()
            val secondarySubtitleState by viewModel.secondarySubtitleState.collectAsStateWithLifecycle()
            val epgState by viewModel.epgState.collectAsStateWithLifecycle()
            val channelZapBanner by viewModel.channelZapBanner.collectAsStateWithLifecycle()

            LaunchedEffect(isInPipMode, playbackState.isPlaying, playbackState.videoWidth, playbackState.videoHeight) {
                if (isInPipMode) {
                    onUpdatePipParams()
                }
            }

            val favoriteMediaKeys = remember(favorites) { favorites.map { it.mediaKey }.toSet() }
            val activeSessionKey = uiState.activeSession?.let { it.seriesItemId ?: it.sourceUri }
            val isActiveFavorited = activeSessionKey != null && activeSessionKey in favoriteMediaKeys

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
                networkPrivacyBadge = networkPrivacyState.compactStatusBadge,
                isPrivacyActive = networkPrivacyState.isAnyPrivacyActive,
                onOpenNetworkPrivacy = openPrivacySheet,
                onRetryWithDecoderMode = viewModel::retryWithDecoderMode,
                onSkipBadSegment = viewModel::skipBadSegmentAndRetry,
                onRefreshJsonPlaylist = viewModel::refreshJsonPlaylist,
                isInPipMode = isInPipMode,
                backgroundAudioEnabled = backgroundAudioEnabled,
                episodeProgressMap = episodeProgressMap,
                onEnterPip = if (!isTvDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) onEnterPip else null,
                onToggleBackgroundAudio = handleToggleBackgroundAudio,
                onAdjustAudioDelay = viewModel::adjustAudioDelay,
                onResetAudioDelay = viewModel::resetAudioDelay,
                onPersistProgressOnly = viewModel::persistCurrentPosition,
                seekIntervalSeconds = seekIntervalSeconds,
                onSelectSeekInterval = viewModel::setSeekIntervalSeconds,
                nightModeAudioEnabled = nightModeAudioEnabled,
                onToggleNightModeAudio = viewModel::setNightModeAudioEnabled,
                sleepTimerState = sleepTimerState,
                onSelectSleepTimer = viewModel::setSleepTimer,
                preferredAudioLanguage = preferredAudioLanguage,
                onSelectPreferredAudioLanguage = viewModel::setPreferredAudioLanguage,
                preferredSubtitleLanguage = preferredSubtitleLanguage,
                onSelectPreferredSubtitleLanguage = viewModel::setPreferredSubtitleLanguage,
                abLoopState = abLoopState,
                onSetAbLoopPointA = viewModel::setAbLoopPointA,
                onSetAbLoopPointB = viewModel::setAbLoopPointB,
                onClearAbLoop = { viewModel.clearAbLoop() },
                chapters = chapters,
                onJumpToChapter = viewModel::jumpToChapter,
                upNextCountdown = upNextCountdown,
                onPlayUpNextNow = viewModel::playUpNextNow,
                onDismissUpNext = { viewModel.dismissUpNextCountdown() },
                onSetTemporaryFastForward = viewModel::setTemporaryFastForward,
                onSkipIntro = viewModel::skipIntro,
                isFavorited = isActiveFavorited,
                onToggleFavorite = viewModel::toggleFavoriteActiveSession,
                onDownloadStream = if (!isTvDevice) viewModel::startOfflineDownloadForActiveSession else null,
                secondarySubtitleState = secondarySubtitleState,
                onLoadSecondarySubtitleFile = viewModel::loadSecondarySubtitleFromUri,
                onLoadSecondarySubtitleUrl = viewModel::loadSecondarySubtitleFromUrl,
                onClearSecondarySubtitle = viewModel::clearSecondarySubtitle,
                epgState = epgState,
                onLoadEpgUrl = { url -> viewModel.loadEpgFromUrl(url) },
                resolveChannelEpg = { entry -> viewModel.resolveEpgForChannel(entry) },
                favoriteMediaKeys = favoriteMediaKeys,
                onToggleFavoriteM3uEntry = viewModel::toggleFavoriteM3uEntry,
                channelZapBanner = channelZapBanner,
                onZapChannel = viewModel::zapM3uChannel,
                onToastShown = viewModel::consumeToast
            )
        }
    }

    if (showHomeM3uSheet) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val epgState by viewModel.epgState.collectAsStateWithLifecycle()
        val favs by viewModel.favorites.collectAsStateWithLifecycle()
        val favKeys = remember(favs) { favs.map { it.mediaKey }.toSet() }
        if (uiState.m3uEntries.isNotEmpty()) {
            M3uPlaylistSheet(
                entries = uiState.m3uEntries,
                activeEntryId = uiState.activeM3uEntryId,
                onDismiss = { showHomeM3uSheet = false },
                onSelectEntry = viewModel::playM3uEntry,
                epgState = epgState,
                onLoadEpgUrl = { url -> viewModel.loadEpgFromUrl(url) },
                resolveChannelEpg = { entry -> viewModel.resolveEpgForChannel(entry) },
                favoriteMediaKeys = favKeys,
                onToggleFavoriteEntry = viewModel::toggleFavoriteM3uEntry
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
    val globalEpisodeProgress by viewModel.episodeProgressMap.collectAsStateWithLifecycle()
    val selectedSeries = globalJsonState.selectedSeriesForSheet
    if (selectedSeries != null) {
        SeriesDetailSheet(
            series = selectedSeries,
            activeEpisodeId = globalJsonState.activeEpisodeId,
            episodeProgressMap = globalEpisodeProgress,
            onDismiss = viewModel::dismissSeriesSheet,
            onSelectEpisode = viewModel::playSeriesEpisode
        )
    }

    if (showPrivacySheet) {
        val privacyState by viewModel.networkPrivacyState.collectAsStateWithLifecycle()
        NetworkPrivacySheet(
            state = privacyState,
            onSelectDnsMode = viewModel::setPrivateDnsMode,
            onLoginNordToken = viewModel::loginNordVpnWithToken,
            onLoginNordConfiguredSecret = viewModel::loginNordVpnWithConfiguredSecret,
            onToggleNordEnabled = viewModel::setNordVpnEnabled,
            onToggleSmartAutoVpnFailover = viewModel::setSmartAutoVpnFailoverEnabled,
            onSelectNordLocation = viewModel::selectNordVpnLocation,
            onLogoutNord = viewModel::logoutNordVpn,
            onDisableAll = viewModel::disableAllPrivacyRouting,
            onProbeRoute = viewModel::probeNetworkPrivacyRoute,
            onDismiss = { showPrivacySheet = false }
        )
    }

    if (showWebRemoteDialog) {
        val webRemoteState by viewModel.webRemoteState.collectAsStateWithLifecycle()
        WebRemoteDialog(
            state = webRemoteState,
            onStartServer = viewModel::startWebRemoteServer,
            onStopServer = viewModel::stopWebRemoteServer,
            onExportSyncBackup = {
                syncExportLauncher.launch("cinestream-sync-backup.json")
            },
            onImportSyncBackup = {
                syncImportLauncher.launch(arrayOf("application/json", "text/*"))
            },
            onDismiss = { showWebRemoteDialog = false }
        )
    }

    if (showDownloadsSheet) {
        val downloads by viewModel.offlineDownloads.collectAsStateWithLifecycle()
        DownloadsSheet(
            downloads = downloads,
            onPlayDownload = { entity ->
                showDownloadsSheet = false
                viewModel.playOfflineDownload(entity)
            },
            onCancelDownload = viewModel::cancelOfflineDownload,
            onDeleteDownload = viewModel::deleteOfflineDownload,
            onDismiss = { showDownloadsSheet = false }
        )
    }

    if (showLocalNetworkSheet) {
        val servers by viewModel.networkServers.collectAsStateWithLifecycle()
        val browserState by viewModel.localNetworkState.collectAsStateWithLifecycle()
        LocalNetworkSheet(
            servers = servers,
            browserState = browserState,
            onAddServer = viewModel::addNetworkServer,
            onDeleteServer = viewModel::deleteNetworkServer,
            onOpenServer = viewModel::openNetworkServer,
            onBrowseItem = viewModel::browseNetworkFolder,
            onNavigateUp = viewModel::navigateNetworkFolderUp,
            onDismiss = { showLocalNetworkSheet = false }
        )
    }
}
