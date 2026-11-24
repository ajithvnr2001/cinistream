package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ContinueWatchingHelper
import com.example.data.MediaHistoryEntity
import com.example.data.MediaHistoryRepository
import com.example.data.SubtitlePrefsRepository
import com.example.model.AspectRatioMode
import com.example.model.ExternalSubtitle
import com.example.model.JsonPlaylistItem
import com.example.model.M3uEntry
import com.example.model.SampleStream
import com.example.model.SampleStreamsCatalog
import com.example.model.SeriesEpisode
import com.example.model.StreamFormat
import com.example.model.SubtitleAppearance
import com.example.model.SubtitleBgPreset
import com.example.model.SubtitleColorPreset
import com.example.model.SubtitleTextSize
import com.example.model.TrackOption
import com.example.BuildConfig
import com.example.player.AudioDelayMath
import com.example.player.CinePlayerManager
import com.example.player.DynamicSocksProxyManager
import com.example.player.ImdbMetadataClient
import com.example.player.JsonPlaylistLoadResult
import com.example.player.JsonPlaylistParser
import com.example.player.M3uInspectionResult
import com.example.player.M3uPlaylistParser
import com.example.player.MediaEdgeCache
import com.example.player.MediaFormatDetector
import com.example.player.MediaPlaybackService
import com.example.player.NetworkTransportMode
import com.example.player.NordLynxTunnelController
import com.example.player.NordVpnApiClient
import com.example.player.NordVpnCatalog
import com.example.player.NordVpnLocation
import com.example.player.NordVpnLocationCatalog
import com.example.player.NordVpnLoginResult
import com.example.player.NordVpnServer
import com.example.player.PlaybackProgressState
import com.example.player.PlayerNetworkStats
import com.example.player.PlayerPlaybackState
import com.example.player.PrivateDnsMode
import com.example.player.SmartVpnFailoverCoordinator
import com.example.player.StreamingNetworkEngine
import com.example.player.SubtitleHelper
import com.example.player.SystemVpnMonitor
import com.example.player.VideoDecoderMode
import com.example.data.DeviceSyncCodec
import com.example.data.DeviceSyncSnapshot
import com.example.data.FavoriteItemEntity
import com.example.data.FavoriteRepository
import com.example.data.GlobalSearchEngine
import com.example.data.NetworkServerEntity
import com.example.data.OfflineDownloadEntity
import com.example.model.AbLoopState
import com.example.model.ChapterMarker
import com.example.model.ChannelEpgNowNext
import com.example.model.GlobalSearchResult
import com.example.model.OnlineSubtitleCandidate
import com.example.model.SleepTimerMode
import com.example.model.SleepTimerUiState
import com.example.model.UpNextCountdownState
import com.example.player.CredentialVault
import com.example.player.LocalNetworkClient
import com.example.player.NetworkBrowserItem
import com.example.player.OfflineDownloadManager
import com.example.player.OpenSubtitlesClient
import com.example.player.OpenSubtitlesLoginResult
import com.example.player.OnlineSubtitleSearchResult
import com.example.player.SecondarySubtitleEngine
import com.example.player.SecondarySubtitleTrack
import com.example.player.XmltvEpgParser
import com.example.remote.LocalWebRemoteServer
import com.example.remote.RemotePushAction
import com.example.remote.RemotePushCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

data class ActiveMediaSession(
    val title: String,
    val sourceUri: String,
    val format: StreamFormat,
    val isLocalFile: Boolean = false,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val subtitleOffsetMs: Long = 0L,
    val mirrors: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val seriesItemId: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeId: String? = null,
    val episodeCodeLabel: String? = null,
    val posterUrl: String? = null,
    val playlistId: String? = null
)

data class CineStreamUiState(
    val urlInput: String = "",
    val selectedFormat: StreamFormat = StreamFormat.AUTO,
    val detectedFormat: StreamFormat = StreamFormat.AUTO,
    val stagedSubtitle: ExternalSubtitle? = null,
    val inputError: String? = null,
    val isParsingPlaylist: Boolean = false,
    val m3uEntries: List<M3uEntry> = emptyList(),
    val activeM3uEntryId: String? = null,
    val activeSession: ActiveMediaSession? = null,
    val toastMessage: String? = null
)

sealed class CineNavigationEvent {
    data object OpenPlayer : CineNavigationEvent()
    data object OpenM3uSheet : CineNavigationEvent()
    data object OpenJsonSheet : CineNavigationEvent()
}

class CineStreamViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val historyRepo = MediaHistoryRepository(database.mediaHistoryDao())
    private val favoriteRepo = FavoriteRepository(database.favoriteDao())
    private val offlineDownloadDao = database.offlineDownloadDao()
    private val networkServerDao = database.networkServerDao()
    private val prefsRepo = SubtitlePrefsRepository(application)

    val playerManager = CinePlayerManager(application)
    private val subtitleHelper = SubtitleHelper(application, playerManager.okHttpClient)
    private val m3uParser = M3uPlaylistParser(application, playerManager.okHttpClient)
    private val jsonParser = JsonPlaylistParser(application, playerManager.okHttpClient)
    private val imdbClient = ImdbMetadataClient(playerManager.okHttpClient)
    private val nordVpnClient = NordVpnApiClient()
    private val nordLynxController = NordLynxTunnelController(application)
    private val smartVpnFailover = SmartVpnFailoverCoordinator()
    private val offlineDownloadManager = OfflineDownloadManager(application, offlineDownloadDao, playerManager.okHttpClient)
    private val localNetworkClient = LocalNetworkClient(playerManager.okHttpClient)
    private val openSubtitlesClient = OpenSubtitlesClient(application, playerManager.okHttpClient)
    private val xmltvEpgParser = XmltvEpgParser(playerManager.okHttpClient)
    private val webRemoteServer = LocalWebRemoteServer(
        onCommandReceived = { cmd -> handleRemotePushCommand(cmd) },
        onExportSyncJson = { exportDeviceSyncSnapshotJsonBlocking() }
    )
    private var activeNordUsername: String = ""
    private var activeNordPassword: String = ""
    private var activeNordLynxKey: String = ""
    private var pendingNordLynxLocation: NordVpnLocation? = null
    private var pendingNordLynxDnsMode: PrivateDnsMode? = null

    private val _vpnConsentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val vpnConsentEvents: SharedFlow<Intent> = _vpnConsentEvents.asSharedFlow()

    private val _uiState = MutableStateFlow(CineStreamUiState())
    val uiState: StateFlow<CineStreamUiState> = _uiState.asStateFlow()

    private val _jsonPlaylistState = MutableStateFlow(JsonPlaylistUiState())
    val jsonPlaylistState: StateFlow<JsonPlaylistUiState> = _jsonPlaylistState.asStateFlow()

    private fun configuredBuildToken(): String {
        val raw = BuildConfig.NORDVPN_ACCESS_TOKEN.trim()
        return if (raw.isBlank() || raw.equals("YOUR_NORDVPN_ACCESS_TOKEN", ignoreCase = true)) {
            ""
        } else {
            raw
        }
    }

    private val _networkPrivacyState = MutableStateFlow(
        NetworkPrivacyUiState(
            hasConfiguredBuildToken = configuredBuildToken().isNotBlank()
        )
    )
    val networkPrivacyState: StateFlow<NetworkPrivacyUiState> = _networkPrivacyState.asStateFlow()

    private val _sampleStreams = MutableStateFlow(SampleStreamsCatalog.items)
    val sampleStreams: StateFlow<List<SampleStream>> = _sampleStreams.asStateFlow()

    val playbackState: StateFlow<PlayerPlaybackState> = playerManager.state
    val playbackProgressState: StateFlow<PlaybackProgressState> = playerManager.progressState
    val networkStats: StateFlow<PlayerNetworkStats> = playerManager.networkStats

    val history: StateFlow<List<MediaHistoryEntity>> = historyRepo.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val continueWatching: StateFlow<List<MediaHistoryEntity>> = history
        .map { ContinueWatchingHelper.buildContinueWatchingList(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val episodeProgressMap: StateFlow<Map<String, Float>> = history
        .map { ContinueWatchingHelper.buildEpisodeProgressMap(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val subtitleAppearance: StateFlow<SubtitleAppearance> = prefsRepo.appearance.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SubtitleAppearance()
    )

    val aspectRatioMode: StateFlow<AspectRatioMode> = prefsRepo.aspectRatioMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AspectRatioMode.FIT
    )

    val statsForNerdsEnabled: StateFlow<Boolean> = prefsRepo.statsForNerdsEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val backgroundAudioEnabled: StateFlow<Boolean> = prefsRepo.backgroundAudioEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val nightModeAudioEnabled: StateFlow<Boolean> = prefsRepo.nightModeAudioEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val preferredAudioLanguage: StateFlow<String> = prefsRepo.preferredAudioLanguage.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val preferredSubtitleLanguage: StateFlow<String> = prefsRepo.preferredSubtitleLanguage.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val seekIntervalSeconds: StateFlow<Int> = prefsRepo.seekIntervalSeconds.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 10
    )

    val autoPlayUpNextEnabled: StateFlow<Boolean> = prefsRepo.autoPlayUpNextEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val favorites: StateFlow<List<FavoriteItemEntity>> = favoriteRepo.favorites.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val offlineDownloads: StateFlow<List<OfflineDownloadEntity>> = offlineDownloadManager.observeDownloads().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val networkServers: StateFlow<List<NetworkServerEntity>> = networkServerDao.observeServers().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _globalSearchQuery = MutableStateFlow("")
    val globalSearchQuery: StateFlow<String> = _globalSearchQuery.asStateFlow()

    val globalSearchResults: StateFlow<List<GlobalSearchResult>> = combine(
        _globalSearchQuery,
        favorites,
        _jsonPlaylistState,
        _uiState,
        offlineDownloads,
        history
    ) { values ->
        val query = values[0] as String
        @Suppress("UNCHECKED_CAST")
        val favs = values[1] as List<FavoriteItemEntity>
        val jsonState = values[2] as JsonPlaylistUiState
        val ui = values[3] as CineStreamUiState
        @Suppress("UNCHECKED_CAST")
        val downloads = values[4] as List<OfflineDownloadEntity>
        @Suppress("UNCHECKED_CAST")
        val hist = values[5] as List<MediaHistoryEntity>
        GlobalSearchEngine.search(
            query = query,
            favorites = favs,
            jsonItems = jsonState.allItems,
            m3uEntries = ui.m3uEntries,
            downloads = downloads,
            history = hist
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _sleepTimerState = MutableStateFlow(SleepTimerUiState())
    val sleepTimerState: StateFlow<SleepTimerUiState> = _sleepTimerState.asStateFlow()

    private val _upNextCountdown = MutableStateFlow<UpNextCountdownState?>(null)
    val upNextCountdown: StateFlow<UpNextCountdownState?> = _upNextCountdown.asStateFlow()

    private val _abLoopState = MutableStateFlow(AbLoopState())
    val abLoopState: StateFlow<AbLoopState> = _abLoopState.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterMarker>>(emptyList())
    val chapters: StateFlow<List<ChapterMarker>> = _chapters.asStateFlow()

    private val _secondarySubtitleState = MutableStateFlow(SecondarySubtitleUiState())
    val secondarySubtitleState: StateFlow<SecondarySubtitleUiState> = _secondarySubtitleState.asStateFlow()

    private val _epgState = MutableStateFlow(EpgUiState())
    val epgState: StateFlow<EpgUiState> = _epgState.asStateFlow()

    private val _channelZapBanner = MutableStateFlow<ChannelZapBannerState?>(null)
    val channelZapBanner: StateFlow<ChannelZapBannerState?> = _channelZapBanner.asStateFlow()

    private val _onlineSubtitleState = MutableStateFlow(
        OnlineSubtitleUiState(isConfigured = openSubtitlesClient.hasApiKey)
    )
    val onlineSubtitleState: StateFlow<OnlineSubtitleUiState> = _onlineSubtitleState.asStateFlow()

    private val _localNetworkState = MutableStateFlow(LocalNetworkUiState())
    val localNetworkState: StateFlow<LocalNetworkUiState> = _localNetworkState.asStateFlow()

    private val _webRemoteState = MutableStateFlow(WebRemoteUiState())
    val webRemoteState: StateFlow<WebRemoteUiState> = _webRemoteState.asStateFlow()

    private val _navEvents = MutableSharedFlow<CineNavigationEvent>(extraBufferCapacity = 4)
    val navEvents: SharedFlow<CineNavigationEvent> = _navEvents.asSharedFlow()

    private var progressPollingJob: Job? = null
    private var jsonEnrichJob: Job? = null
    private var volumePersistJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var upNextCountdownJob: Job? = null
    private var channelZapBannerJob: Job? = null
    private var dismissedUpNextEpisodeId: String? = null
    private var lastSavedProgressUri: String? = null
    private var lastSavedProgressMs: Long = -1L

    init {
        playerManager.onCdnFailover = { host, nodeIndex, totalNodes ->
            _uiState.update {
                it.copy(toastMessage = "CDN failover → $host ($nodeIndex/$totalNodes)")
            }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        }
        playerManager.onDecoderFallback = { failedCodec, switchedMode ->
            val failedLabel = failedCodec?.let { " ($it)" }.orEmpty()
            _uiState.update {
                it.copy(
                    toastMessage = "Decoder failed$failedLabel → Switched to ${switchedMode.label}"
                )
            }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        }
        playerManager.onTrackFallback = { trackLabel ->
            _uiState.update {
                it.copy(
                    toastMessage = "HEVC decode failed → Switched to compatible track ($trackLabel)"
                )
            }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        }
        playerManager.onTransportAutoFallback = { reason ->
            _uiState.update { it.copy(toastMessage = reason) }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        }
        playerManager.onMediaEnded = {
            if (_sleepTimerState.value.mode == SleepTimerMode.END_OF_EPISODE) {
                cancelSleepTimer(silent = true)
                playerManager.pause()
                _uiState.update { it.copy(toastMessage = "Sleep timer paused playback at end of episode") }
            } else if (_upNextCountdown.value == null && autoPlayUpNextEnabled.value) {
                val started = maybeStartUpNextCountdown(forceImmediateOnEnd = true)
                if (!started) {
                    playNextEpisodeIfAvailable(autoTriggered = true)
                }
            }
        }
        playerManager.onSmartVpnStallCandidate = { estimatedBps ->
            handleSmartVpnStallCandidate(estimatedBps)
        }
        playerManager.onSmartVpnFatalNetworkError = { reason ->
            handleSmartVpnFatalNetworkError(reason)
        }
        MediaPlaybackService.onNotificationTogglePlayPause = {
            playerManager.togglePlayPause()
            syncBackgroundAudioNotification()
        }
        MediaPlaybackService.onNotificationStop = {
            pauseAndPersistProgress()
            setBackgroundAudioEnabled(false)
        }
        viewModelScope.launch {
            val savedVolume = prefsRepo.volumeBoostPercent.first()
            playerManager.setVolumePercent(savedVolume)
        }
        viewModelScope.launch {
            val savedNight = prefsRepo.nightModeAudioEnabled.first()
            playerManager.setNightModeAudio(savedNight)
        }
        viewModelScope.launch {
            val audioLang = prefsRepo.preferredAudioLanguage.first()
            val subLang = prefsRepo.preferredSubtitleLanguage.first()
            if (audioLang.isNotBlank() || subLang.isNotBlank()) {
                playerManager.applyPreferredLanguages(audioLang, subLang)
            }
        }
        viewModelScope.launch {
            val savedEpgUrl = prefsRepo.epgUrl.first()
            if (savedEpgUrl.isNotBlank()) {
                _epgState.update { it.copy(epgUrl = savedEpgUrl) }
            }
        }
        viewModelScope.launch {
            val savedDecoderMode = prefsRepo.videoDecoderMode.first()
            playerManager.setPreferredDecoderMode(savedDecoderMode, reloadActiveStream = false)
        }
        viewModelScope.launch {
            val savedTransportMode = prefsRepo.networkTransportMode.first()
            playerManager.setPreferredTransportMode(savedTransportMode, reloadActiveStream = false)
            if (openSubtitlesClient.hasApiKey) {
                prefsRepo.loadOpenSubtitlesCredentials()?.let { (osUser, osPass) ->
                    when (val res = openSubtitlesClient.login(osUser, osPass)) {
                        is OpenSubtitlesLoginResult.Success -> _onlineSubtitleState.update {
                            it.copy(
                                loggedInUsername = res.username,
                                remainingDownloads = res.allowedDownloads
                            )
                        }
                        is OpenSubtitlesLoginResult.Error -> Unit
                    }
                }
            }
        }
        viewModelScope.launch {
            val savedPrivacy = prefsRepo.networkPrivacyPrefs.first()
            activeNordUsername = savedPrivacy.nordVpnUsername
            activeNordPassword = savedPrivacy.nordVpnPassword
            activeNordLynxKey = savedPrivacy.nordLynxPrivateKey
            val savedLocation = savedPrivacy.selectedLocation

            // Apply saved Private DNS immediately; SOCKS5 stays off when NordLynx key is present
            val useSocksFallback = savedPrivacy.nordVpnEnabled &&
                activeNordLynxKey.isBlank() &&
                activeNordUsername.isNotBlank() &&
                activeNordPassword.isNotBlank()
            val socksConfig = if (useSocksFallback) {
                DynamicSocksProxyManager.ActiveConfig(
                    enabled = true,
                    host = savedPrivacy.nordVpnServerHost,
                    username = activeNordUsername,
                    password = activeNordPassword
                )
            } else {
                DynamicSocksProxyManager.ActiveConfig()
            }
            playerManager.applyNetworkPrivacyConfig(
                dnsMode = savedPrivacy.privateDnsMode,
                nordConfig = socksConfig,
                reloadActiveStream = false
            )
            val appCtx = getApplication<Application>()
            val sysVpn = SystemVpnMonitor.isSystemVpnActive(appCtx)
            _networkPrivacyState.update {
                it.copy(
                    privateDnsMode = savedPrivacy.privateDnsMode,
                    nordVpnLoggedIn = savedPrivacy.hasNordCredentials,
                    nordVpnEnabled = savedPrivacy.nordVpnEnabled && savedPrivacy.hasNordCredentials,
                    smartAutoVpnFailoverEnabled = savedPrivacy.smartAutoVpnFailoverEnabled && savedPrivacy.hasNordCredentials,
                    selectedLocation = savedLocation,
                    selectedNordServerHost = savedPrivacy.nordVpnServerHost,
                    selectedNordServerLabel = savedLocation.displayLabel,
                    systemVpnActive = sysVpn
                )
            }

            // Refresh all 118+ NordVPN countries & cities silently in background
            launch {
                val liveLocations = nordVpnClient.fetchAllLocations()
                if (liveLocations.isNotEmpty()) {
                    _networkPrivacyState.update { state ->
                        val resolvedLoc = NordVpnLocationCatalog.findByCountryAndCity(
                            countryId = state.selectedLocation.countryId,
                            cityId = state.selectedLocation.cityId,
                            available = liveLocations
                        )
                        state.copy(
                            allLocations = liveLocations,
                            selectedLocation = resolvedLoc,
                            selectedNordServerLabel = resolvedLoc.displayLabel
                        )
                    }
                }
            }

            // Auto-reconnect embedded NordLynx tunnel if VPN consent was previously granted
            if (savedPrivacy.nordVpnEnabled && activeNordLynxKey.isNotBlank() &&
                nordLynxController.prepareVpnPermissionIntent() == null
            ) {
                connectNordLynxInternal(
                    location = savedLocation,
                    dnsMode = savedPrivacy.privateDnsMode,
                    reloadActiveStream = false,
                    autoProbe = false
                )
            }
        }
        viewModelScope.launch {
            enrichSamplesWithImdb()
        }
        viewModelScope.launch {
            val savedJsonUrls = prefsRepo.loadJsonPlaylistUrls()
            if (savedJsonUrls.isNotEmpty()) {
                loadMultipleJsonPlaylistsInternal(
                    urls = savedJsonUrls,
                    silentOnError = true,
                    showSummaryToast = false
                )
            }
        }
        startProgressPolling()
    }

    fun onUrlInputChanged(value: String) {
        val detected = MediaFormatDetector.detectFormat(value)
        _uiState.update {
            it.copy(
                urlInput = value,
                detectedFormat = detected,
                inputError = null
            )
        }
    }

    fun onFormatSelected(format: StreamFormat) {
        _uiState.update { it.copy(selectedFormat = format, inputError = null) }
    }

    fun clearUrlInput() {
        _uiState.update {
            it.copy(
                urlInput = "",
                detectedFormat = StreamFormat.AUTO,
                inputError = null
            )
        }
    }

    fun stageLocalSubtitle(uri: Uri) {
        viewModelScope.launch {
            val sub = subtitleHelper.buildFromLocalUri(uri)
            _uiState.update {
                it.copy(
                    stagedSubtitle = sub,
                    toastMessage = "Subtitle ready: ${sub.label} (${sub.formatBadge})"
                )
            }
        }
    }

    fun stageRemoteSubtitle(url: String) {
        val trimmed = url.trim()
        if (!MediaFormatDetector.isValidMediaLink(trimmed)) {
            _uiState.update { it.copy(toastMessage = "Enter a valid http:// or https:// subtitle URL") }
            return
        }
        viewModelScope.launch {
            val sub = subtitleHelper.buildFromRemoteUrl(trimmed)
            _uiState.update {
                it.copy(
                    stagedSubtitle = sub,
                    toastMessage = "Subtitle ready: ${sub.label} (${sub.formatBadge})"
                )
            }
        }
    }

    fun clearStagedSubtitle() {
        _uiState.update { it.copy(stagedSubtitle = null) }
    }

    fun launchFromUrlInput() {
        val raw = _uiState.value.urlInput.trim()
        if (raw.isBlank()) {
            _uiState.update { it.copy(inputError = "Please enter a valid video or stream URL.") }
            return
        }
        if (!MediaFormatDetector.isValidMediaLink(raw)) {
            _uiState.update { it.copy(inputError = "Link must start with http://, https://, or content://") }
            return
        }

        val chosenFormat = _uiState.value.selectedFormat
        val effectiveFormat = if (chosenFormat == StreamFormat.AUTO) {
            MediaFormatDetector.detectFormat(raw)
        } else {
            chosenFormat
        }
        val subtitle = _uiState.value.stagedSubtitle

        viewModelScope.launch {
            if (chosenFormat == StreamFormat.AUTO && MediaFormatDetector.shouldInspectAsJson(raw)) {
                loadJsonPlaylistFromUrl(raw, openSheetOnSuccess = true, silentOnError = false)
                return@launch
            }
            if (MediaFormatDetector.shouldInspectAsM3u(raw, chosenFormat)) {
                _uiState.update { it.copy(isParsingPlaylist = true) }
                val inspection = m3uParser.inspect(raw, isLocalFile = false)
                _uiState.update { it.copy(isParsingPlaylist = false) }
                when (inspection) {
                    is M3uInspectionResult.ChannelPlaylist -> {
                        _uiState.update {
                            it.copy(
                                m3uEntries = inspection.entries,
                                toastMessage = "Loaded ${inspection.entries.size} streams from M3U playlist"
                            )
                        }
                        val autoEpg = inspection.epgUrl ?: _epgState.value.epgUrl.takeIf { it.isNotBlank() }
                        if (!autoEpg.isNullOrBlank()) {
                            loadEpgFromUrl(autoEpg, silent = true)
                        }
                        if (inspection.entries.size == 1) {
                            playM3uEntry(inspection.entries.first())
                        } else {
                            _navEvents.tryEmit(CineNavigationEvent.OpenM3uSheet)
                        }
                        return@launch
                    }
                    M3uInspectionResult.DirectHlsStream -> {
                        startPlayback(
                            title = MediaFormatDetector.inferTitle(raw),
                            sourceUri = raw,
                            format = StreamFormat.HLS,
                            isLocalFile = false,
                            initialSubtitles = listOfNotNull(subtitle)
                        )
                        return@launch
                    }
                    M3uInspectionResult.FallbackDirectPlay -> {
                        // Fall through to standard playback
                    }
                }
            }

            startPlayback(
                title = MediaFormatDetector.inferTitle(raw),
                sourceUri = raw,
                format = effectiveFormat,
                isLocalFile = raw.startsWith("content://") || raw.startsWith("file://"),
                initialSubtitles = listOfNotNull(subtitle)
            )
        }
    }

    fun openLocalVideo(uri: Uri) {
        viewModelScope.launch {
            subtitleHelper.takePersistableReadPermission(uri)
            val displayName = subtitleHelper.resolveDisplayName(uri)
            val detected = MediaFormatDetector.detectFormat(uri.toString(), displayName)
            val title = MediaFormatDetector.inferTitle(uri.toString(), displayName)

            if (detected == StreamFormat.M3U) {
                openLocalM3uPlaylist(uri)
                return@launch
            }

            startPlayback(
                title = title,
                sourceUri = uri.toString(),
                format = detected,
                isLocalFile = true,
                initialSubtitles = listOfNotNull(_uiState.value.stagedSubtitle)
            )
        }
    }

    fun openLocalM3uPlaylist(uri: Uri) {
        viewModelScope.launch {
            subtitleHelper.takePersistableReadPermission(uri)
            _uiState.update { it.copy(isParsingPlaylist = true) }
            val result = m3uParser.inspect(uri.toString(), isLocalFile = true)
            _uiState.update { it.copy(isParsingPlaylist = false) }
            when (result) {
                is M3uInspectionResult.ChannelPlaylist -> {
                    _uiState.update {
                        it.copy(
                            m3uEntries = result.entries,
                            toastMessage = "Loaded ${result.entries.size} entries from M3U"
                        )
                    }
                    val autoEpg = result.epgUrl ?: _epgState.value.epgUrl.takeIf { it.isNotBlank() }
                    if (!autoEpg.isNullOrBlank()) {
                        loadEpgFromUrl(autoEpg, silent = true)
                    }
                    _navEvents.tryEmit(CineNavigationEvent.OpenM3uSheet)
                }
                M3uInspectionResult.DirectHlsStream,
                M3uInspectionResult.FallbackDirectPlay -> {
                    val displayName = subtitleHelper.resolveDisplayName(uri)
                    startPlayback(
                        title = MediaFormatDetector.inferTitle(uri.toString(), displayName),
                        sourceUri = uri.toString(),
                        format = StreamFormat.HLS,
                        isLocalFile = true,
                        initialSubtitles = listOfNotNull(_uiState.value.stagedSubtitle)
                    )
                }
            }
        }
    }

    fun playM3uEntry(entry: M3uEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeM3uEntryId = entry.id) }
            startPlayback(
                title = entry.title,
                sourceUri = entry.url,
                format = entry.format,
                isLocalFile = false,
                initialSubtitles = listOfNotNull(_uiState.value.stagedSubtitle)
            )
        }
    }

    fun playSampleStream(sample: SampleStream) {
        viewModelScope.launch {
            val subs = mutableListOf<ExternalSubtitle>()
            _uiState.value.stagedSubtitle?.let { subs.add(it) }
            if (subs.isEmpty() && !sample.sampleSubtitleUrl.isNullOrBlank()) {
                subs.add(subtitleHelper.buildFromRemoteUrl(sample.sampleSubtitleUrl))
            }
            startPlayback(
                title = sample.title,
                sourceUri = sample.url,
                format = sample.format,
                isLocalFile = false,
                initialSubtitles = subs,
                posterUrl = sample.posterUrl
            )
        }
    }

    // ==================== JSON PLAYLIST & IMDB INTEGRATION ====================

    fun loadJsonPlaylistFromUrl(
        url: String,
        openSheetOnSuccess: Boolean = true,
        silentOnError: Boolean = false,
        selectLoadedPlaylist: Boolean = true
    ) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            _uiState.update { it.copy(toastMessage = "Enter a valid http:// or https:// JSON playlist URL") }
            return
        }

        viewModelScope.launch {
            _jsonPlaylistState.update {
                it.copy(isLoading = true, errorMessage = null)
            }
            when (val result = jsonParser.loadFromUrl(trimmed)) {
                is JsonPlaylistLoadResult.Success -> {
                    val playlistId = MultiJsonPlaylistReducer.stablePlaylistId(trimmed, isLocal = false)
                    val label = result.sourceTitle?.takeIf { it.isNotBlank() }
                        ?: MediaFormatDetector.inferTitle(trimmed)
                    val loaded = LoadedJsonPlaylist(
                        id = playlistId,
                        label = label,
                        sourceUrl = trimmed,
                        isLocal = false,
                        items = result.items
                    )
                    var updatedState = _jsonPlaylistState.value
                    _jsonPlaylistState.update { current ->
                        MultiJsonPlaylistReducer.upsertPlaylist(
                            state = current,
                            playlist = loaded,
                            selectPlaylist = selectLoadedPlaylist
                        ).also { updatedState = it }
                    }
                    // Preserve any already-saved remote URLs that might have failed to boot earlier
                    val mergedRemoteUrls = SubtitlePrefsRepository.normalizeUrlList(
                        prefsRepo.loadJsonPlaylistUrls() + updatedState.remotePlaylistUrls + trimmed
                    )
                    prefsRepo.saveJsonPlaylistUrls(mergedRemoteUrls)

                    if (!silentOnError) {
                        val playlistCountSuffix = if (updatedState.playlists.size > 1) {
                            " (${updatedState.playlists.size} playlists active)"
                        } else {
                            ""
                        }
                        _uiState.update {
                            it.copy(
                                toastMessage = "Loaded ${result.items.size} items from $label$playlistCountSuffix"
                            )
                        }
                    }
                    if (openSheetOnSuccess) {
                        _navEvents.tryEmit(CineNavigationEvent.OpenJsonSheet)
                    }
                    prewarmFirstItemOrigin(updatedState.items.ifEmpty { updatedState.allItems })
                    startJsonImdbEnrichment(updatedState.allItems)
                }
                is JsonPlaylistLoadResult.Error -> {
                    _jsonPlaylistState.update {
                        it.copy(isLoading = false, errorMessage = if (silentOnError) null else result.message)
                    }
                    if (!silentOnError) {
                        _uiState.update { it.copy(toastMessage = result.message) }
                    }
                }
            }
        }
    }

    private suspend fun loadMultipleJsonPlaylistsInternal(
        urls: List<String>,
        silentOnError: Boolean,
        showSummaryToast: Boolean
    ) {
        val cleanedUrls = SubtitlePrefsRepository.normalizeUrlList(urls).filter {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        if (cleanedUrls.isEmpty()) return

        _jsonPlaylistState.update { it.copy(isLoading = true, errorMessage = null) }
        val semaphore = Semaphore(3)
        val fetched = coroutineScope {
            cleanedUrls.map { url ->
                async {
                    semaphore.withPermit {
                        url to jsonParser.loadFromUrl(url)
                    }
                }
            }.awaitAll()
        }

        var successCount = 0
        var firstError: String? = null
        var latestState = _jsonPlaylistState.value
        for ((url, result) in fetched) {
            when (result) {
                is JsonPlaylistLoadResult.Success -> {
                    successCount++
                    val playlistId = MultiJsonPlaylistReducer.stablePlaylistId(url, isLocal = false)
                    val label = result.sourceTitle?.takeIf { it.isNotBlank() }
                        ?: MediaFormatDetector.inferTitle(url)
                    val loaded = LoadedJsonPlaylist(
                        id = playlistId,
                        label = label,
                        sourceUrl = url,
                        isLocal = false,
                        items = result.items
                    )
                    _jsonPlaylistState.update { current ->
                        MultiJsonPlaylistReducer.upsertPlaylist(
                            state = current,
                            playlist = loaded,
                            selectPlaylist = false
                        ).also { latestState = it }
                    }
                }
                is JsonPlaylistLoadResult.Error -> {
                    if (firstError == null) firstError = result.message
                }
            }
        }

        _jsonPlaylistState.update {
            it.copy(
                isLoading = false,
                errorMessage = if (successCount == 0 && !silentOnError) firstError else null
            )
        }

        if (successCount > 0) {
            if (showSummaryToast) {
                val totalItems = latestState.allItems.size
                val failedSuffix = if (successCount < cleanedUrls.size) {
                    " (${cleanedUrls.size - successCount} unreachable)"
                } else {
                    ""
                }
                _uiState.update {
                    it.copy(
                        toastMessage = "Refreshed $successCount JSON playlist${if (successCount == 1) "" else "s"} · $totalItems titles$failedSuffix"
                    )
                }
            }
            prewarmFirstItemOrigin(latestState.allItems)
            startJsonImdbEnrichment(latestState.allItems)
        } else if (!silentOnError && !firstError.isNullOrBlank()) {
            _uiState.update { it.copy(toastMessage = firstError) }
        }
    }

    fun loadJsonPlaylistFromUri(uri: Uri) {
        viewModelScope.launch {
            subtitleHelper.takePersistableReadPermission(uri)
            val displayName = subtitleHelper.resolveDisplayName(uri)
            val uriStr = uri.toString()
            _jsonPlaylistState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = jsonParser.loadFromLocalUri(uri)) {
                is JsonPlaylistLoadResult.Success -> {
                    val playlistId = MultiJsonPlaylistReducer.stablePlaylistId(uriStr, isLocal = true)
                    val label = result.sourceTitle?.takeIf { it.isNotBlank() } ?: displayName
                    val loaded = LoadedJsonPlaylist(
                        id = playlistId,
                        label = label,
                        sourceUrl = uriStr,
                        isLocal = true,
                        items = result.items
                    )
                    var updatedState = _jsonPlaylistState.value
                    _jsonPlaylistState.update { current ->
                        MultiJsonPlaylistReducer.upsertPlaylist(
                            state = current,
                            playlist = loaded,
                            selectPlaylist = true
                        ).also { updatedState = it }
                    }
                    val playlistCountSuffix = if (updatedState.playlists.size > 1) {
                        " (${updatedState.playlists.size} playlists active)"
                    } else {
                        ""
                    }
                    _uiState.update {
                        it.copy(toastMessage = "Loaded ${result.items.size} items from $label$playlistCountSuffix")
                    }
                    _navEvents.tryEmit(CineNavigationEvent.OpenJsonSheet)
                    prewarmFirstItemOrigin(updatedState.items.ifEmpty { updatedState.allItems })
                    startJsonImdbEnrichment(updatedState.allItems)
                }
                is JsonPlaylistLoadResult.Error -> {
                    _jsonPlaylistState.update { it.copy(isLoading = false, errorMessage = result.message) }
                    _uiState.update { it.copy(toastMessage = result.message) }
                }
            }
        }
    }

    fun selectJsonPlaylist(playlistId: String?) {
        _jsonPlaylistState.update { current ->
            MultiJsonPlaylistReducer.selectPlaylist(current, playlistId)
        }
    }

    fun removeJsonPlaylist(playlistId: String) {
        val target = _jsonPlaylistState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        var updatedState = _jsonPlaylistState.value
        _jsonPlaylistState.update { current ->
            MultiJsonPlaylistReducer.removePlaylist(current, playlistId).also { updatedState = it }
        }
        viewModelScope.launch {
            if (target.isRemoteHttp) {
                val persisted = prefsRepo.loadJsonPlaylistUrls()
                    .filterNot { it.equals(target.sourceUrl, ignoreCase = true) }
                prefsRepo.saveJsonPlaylistUrls(persisted)
            }
            if (updatedState.playlists.isEmpty()) {
                jsonEnrichJob?.cancel()
            }
        }
        _uiState.update { it.copy(toastMessage = "Removed playlist: ${target.label}") }
    }

    fun refreshJsonPlaylist() {
        val state = _jsonPlaylistState.value
        val selectedId = state.selectedPlaylistId
        if (selectedId != null) {
            val selected = state.playlists.firstOrNull { it.id == selectedId }
            if (selected != null && selected.isRemoteHttp) {
                loadJsonPlaylistFromUrl(
                    url = selected.sourceUrl,
                    openSheetOnSuccess = false,
                    silentOnError = false,
                    selectLoadedPlaylist = true
                )
                return
            }
        }
        val remoteUrls = state.remotePlaylistUrls
        if (remoteUrls.isNotEmpty()) {
            viewModelScope.launch {
                loadMultipleJsonPlaylistsInternal(
                    urls = remoteUrls,
                    silentOnError = false,
                    showSummaryToast = true
                )
            }
            return
        }
        val legacyUrl = state.sourceUrl ?: return
        if (legacyUrl.startsWith("http://", ignoreCase = true) ||
            legacyUrl.startsWith("https://", ignoreCase = true)
        ) {
            loadJsonPlaylistFromUrl(
                url = legacyUrl,
                openSheetOnSuccess = false,
                silentOnError = false,
                selectLoadedPlaylist = true
            )
        }
    }

    fun clearJsonPlaylist() {
        jsonEnrichJob?.cancel()
        viewModelScope.launch { prefsRepo.saveJsonPlaylistUrls(emptyList()) }
        _jsonPlaylistState.value = JsonPlaylistUiState()
    }

    fun playJsonPlaylistItem(item: JsonPlaylistItem) {
        if (item.isSeries) {
            _jsonPlaylistState.update { it.copy(selectedSeriesForSheet = item) }
            val firstEpUrl = item.seasons.firstOrNull()?.episodes?.firstOrNull()?.url
            StreamingNetworkEngine.prewarmOrigin(firstEpUrl, playerManager.okHttpClient)
            return
        }
        viewModelScope.launch {
            _jsonPlaylistState.update {
                it.copy(
                    activeItemId = item.id,
                    activeEpisodeId = null,
                    selectedSeriesForSheet = null
                )
            }
            val subs = mutableListOf<ExternalSubtitle>()
            _uiState.value.stagedSubtitle?.let { subs.add(it) }
            if (!item.subtitleUrl.isNullOrBlank()) {
                val remoteSub = subtitleHelper.buildFromRemoteUrl(item.subtitleUrl)
                subs.add(
                    if (!item.subtitleLabel.isNullOrBlank()) {
                        remoteSub.copy(label = item.subtitleLabel)
                    } else {
                        remoteSub
                    }
                )
            }
            val effectiveFormat = if (item.format == StreamFormat.AUTO) {
                MediaFormatDetector.detectFormat(item.url)
            } else {
                item.format
            }
            startPlayback(
                title = item.title,
                sourceUri = item.url,
                format = effectiveFormat,
                isLocalFile = item.url.startsWith("content://") || item.url.startsWith("file://"),
                initialSubtitles = subs,
                mirrors = item.mirrors,
                headers = item.headers,
                posterUrl = item.imageUrl,
                playlistId = _jsonPlaylistState.value.selectedPlaylistId
            )
        }
    }

    fun dismissSeriesSheet() {
        _jsonPlaylistState.update { it.copy(selectedSeriesForSheet = null) }
    }

    fun playSeriesEpisode(series: JsonPlaylistItem, episode: SeriesEpisode) {
        viewModelScope.launch {
            _jsonPlaylistState.update {
                it.copy(
                    activeItemId = series.id,
                    activeEpisodeId = episode.id,
                    selectedSeriesForSheet = null
                )
            }
            val subs = mutableListOf<ExternalSubtitle>()
            _uiState.value.stagedSubtitle?.let { subs.add(it) }
            if (!episode.subtitleUrl.isNullOrBlank()) {
                val remoteSub = subtitleHelper.buildFromRemoteUrl(episode.subtitleUrl)
                subs.add(
                    if (!episode.subtitleLabel.isNullOrBlank()) {
                        remoteSub.copy(label = episode.subtitleLabel)
                    } else {
                        remoteSub
                    }
                )
            }
            val effectiveFormat = if (episode.format == StreamFormat.AUTO) {
                MediaFormatDetector.detectFormat(episode.url)
            } else {
                episode.format
            }
            startPlayback(
                title = "${series.title} · ${episode.codeLabel} — ${episode.title}",
                sourceUri = episode.url,
                format = effectiveFormat,
                isLocalFile = episode.url.startsWith("content://") || episode.url.startsWith("file://"),
                initialSubtitles = subs,
                mirrors = episode.mirrors,
                headers = episode.headers,
                seriesItemId = series.id,
                seriesTitle = series.title,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                episodeId = episode.id,
                episodeCodeLabel = episode.codeLabel,
                posterUrl = series.imageUrl,
                playlistId = _jsonPlaylistState.value.selectedPlaylistId
            )
            val nextEp = series.findNextEpisode(episode.id)
            if (nextEp != null) {
                StreamingNetworkEngine.prewarmOrigin(nextEp.url, playerManager.okHttpClient)
            }
        }
    }

    private fun prewarmFirstItemOrigin(items: List<JsonPlaylistItem>) {
        val firstUrl = items.firstNotNullOfOrNull { item ->
            if (item.isSeries) {
                item.seasons.firstOrNull()?.episodes?.firstOrNull()?.url
            } else {
                item.url.takeIf { it.isNotBlank() }
            }
        }
        StreamingNetworkEngine.prewarmOrigin(firstUrl, playerManager.okHttpClient)
    }

    fun playNextEpisode() {
        playNextEpisodeIfAvailable(autoTriggered = false)
    }

    private fun playNextEpisodeIfAvailable(autoTriggered: Boolean) {
        val session = _uiState.value.activeSession ?: return
        val seriesId = session.seriesItemId ?: return
        val series = _jsonPlaylistState.value.allItems.firstOrNull { it.id == seriesId } ?: return
        val nextEp = series.findNextEpisode(session.episodeId) ?: return
        _uiState.update {
            it.copy(
                toastMessage = if (autoTriggered) {
                    "Up Next: ${nextEp.codeLabel} — ${nextEp.title}"
                } else {
                    "Playing ${nextEp.codeLabel} — ${nextEp.title}"
                }
            )
        }
        playSeriesEpisode(series, nextEp)
    }

    fun switchToNextCdnMirror() {
        val host = playerManager.switchToNextCdnMirror()
        if (host != null) {
            val st = playerManager.state.value
            _uiState.update {
                it.copy(toastMessage = "Switched CDN → $host (${st.activeCdnIndex}/${st.totalCdnNodes})")
            }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        } else {
            _uiState.update {
                it.copy(toastMessage = "No alternate CDN mirrors configured for this stream")
            }
        }
    }

    fun clearMediaEdgeCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                MediaEdgeCache.clearAll()
            }
            playerManager.syncNetworkStats()
            _uiState.update {
                it.copy(toastMessage = "Local edge disk cache cleared")
            }
        }
    }

    fun setVideoDecoderMode(mode: VideoDecoderMode) {
        playerManager.setPreferredDecoderMode(mode, reloadActiveStream = true)
        viewModelScope.launch {
            prefsRepo.updateVideoDecoderMode(mode)
        }
        _uiState.update {
            it.copy(toastMessage = "Decoder set to ${mode.label}")
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    fun setNetworkTransportMode(mode: NetworkTransportMode) {
        playerManager.setPreferredTransportMode(mode, reloadActiveStream = true)
        viewModelScope.launch {
            prefsRepo.updateNetworkTransportMode(mode)
        }
        _uiState.update {
            it.copy(toastMessage = "Network engine set to ${mode.label}")
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    // ==================== CLOUDFLARE 1.1.1.1 DoH & NORDVPN ====================

    fun refreshSystemVpnStatus() {
        val appCtx = getApplication<Application>()
        val sysVpn = SystemVpnMonitor.isSystemVpnActive(appCtx)
        _networkPrivacyState.update {
            it.copy(
                systemVpnActive = sysVpn,
                nordLynxTunnelUp = nordLynxController.isTunnelUp
            )
        }
        // Populate live 118+ countries & cities catalog when sheet opens if still on offline list
        if (_networkPrivacyState.value.allLocations.size <= NordVpnLocationCatalog.FALLBACK_COUNTRIES.size) {
            viewModelScope.launch {
                val liveLocations = nordVpnClient.fetchAllLocations()
                if (liveLocations.isNotEmpty()) {
                    _networkPrivacyState.update { it.copy(allLocations = liveLocations) }
                }
            }
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    fun setPrivateDnsMode(mode: PrivateDnsMode) {
        val current = _networkPrivacyState.value
        val socksConfig = buildCurrentSocksFallbackConfig(current)
        playerManager.applyNetworkPrivacyConfig(
            dnsMode = mode,
            nordConfig = socksConfig,
            reloadActiveStream = !current.nordLynxTunnelUp
        )
        _networkPrivacyState.update {
            it.copy(privateDnsMode = mode, probeResult = null)
        }
        viewModelScope.launch {
            prefsRepo.updatePrivateDnsMode(mode)
            if (current.nordVpnLoggedIn && current.nordVpnEnabled && activeNordLynxKey.isNotBlank()) {
                connectNordLynxInternal(
                    location = current.selectedLocation,
                    dnsMode = mode,
                    reloadActiveStream = true,
                    autoProbe = false
                )
            }
        }
        _uiState.update {
            it.copy(toastMessage = "Private DNS set to ${mode.label}")
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    fun loginNordVpnWithConfiguredSecret() {
        val configuredToken = configuredBuildToken()
        if (configuredToken.isBlank()) {
            _networkPrivacyState.update {
                it.copy(loginError = "No NordVPN token configured in build secrets. Paste your token below.")
            }
            return
        }
        loginNordVpnWithToken(configuredToken)
    }

    fun loginNordVpnWithToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            _networkPrivacyState.update { it.copy(loginError = "Enter your NordVPN Access Key.") }
            return
        }
        val wasAlreadyLoggedIn = _networkPrivacyState.value.nordVpnLoggedIn
        viewModelScope.launch {
            _networkPrivacyState.update { it.copy(isLoggingInNord = true, loginError = null) }
            when (val result = nordVpnClient.loginWithAccessToken(trimmed)) {
                is NordVpnLoginResult.Success -> {
                    activeNordUsername = result.credentials.username
                    activeNordPassword = result.credentials.password
                    activeNordLynxKey = result.credentials.nordLynxPrivateKey

                    val locations = result.locations.ifEmpty { NordVpnLocationCatalog.FALLBACK_COUNTRIES }
                    val servers = result.servers.ifEmpty { NordVpnCatalog.DEFAULT_SERVERS }
                    val currentState = _networkPrivacyState.value
                    val chosenLocation = NordVpnLocationCatalog.findByCountryAndCity(
                        countryId = currentState.selectedLocation.countryId,
                        cityId = currentState.selectedLocation.cityId,
                        available = locations
                    )
                    val effectiveDnsMode = if (currentState.privateDnsMode == PrivateDnsMode.SYSTEM) {
                        PrivateDnsMode.CLOUDFLARE_1111
                    } else {
                        currentState.privateDnsMode
                    }

                    if (effectiveDnsMode != currentState.privateDnsMode) {
                        prefsRepo.updatePrivateDnsMode(effectiveDnsMode)
                    }
                    prefsRepo.saveNordVpnCredentials(
                        credentials = result.credentials,
                        serverHost = currentState.selectedNordServerHost,
                        serverLabel = chosenLocation.displayLabel,
                        location = chosenLocation,
                        enableImmediately = true
                    )

                    _networkPrivacyState.update {
                        it.copy(
                            privateDnsMode = effectiveDnsMode,
                            nordVpnLoggedIn = true,
                            nordVpnEnabled = true,
                            selectedLocation = chosenLocation,
                            allLocations = locations,
                            selectedNordServerLabel = chosenLocation.displayLabel,
                            availableNordServers = servers,
                            isLoggingInNord = false,
                            loginError = null,
                            probeResult = null
                        )
                    }

                    if (activeNordLynxKey.isNotBlank()) {
                        connectNordLynxInternal(
                            location = chosenLocation,
                            dnsMode = effectiveDnsMode,
                            reloadActiveStream = true,
                            autoProbe = true
                        )
                    } else {
                        // Fallback to SOCKS5 only if account returned no NordLynx key
                        val fallbackHost = NordVpnCatalog.findByHostname(currentState.selectedNordServerHost, servers)
                        playerManager.applyNetworkPrivacyConfig(
                            dnsMode = effectiveDnsMode,
                            nordConfig = DynamicSocksProxyManager.ActiveConfig(
                                enabled = true,
                                host = fallbackHost.hostname,
                                port = fallbackHost.port,
                                username = activeNordUsername,
                                password = activeNordPassword
                            ),
                            reloadActiveStream = true
                        )
                        probeNetworkPrivacyRoute()
                    }

                    _uiState.update {
                        it.copy(
                            toastMessage = if (wasAlreadyLoggedIn) {
                                "NordVPN key updated · ${chosenLocation.displayLabel}"
                            } else {
                                "Connected: ${effectiveDnsMode.shortBadge.ifBlank { "DNS" }} + NordVPN (${chosenLocation.displayLabel})"
                            }
                        )
                    }
                }
                is NordVpnLoginResult.Error -> {
                    _networkPrivacyState.update {
                        it.copy(isLoggingInNord = false, loginError = result.message)
                    }
                }
            }
        }
    }

    /**
     * Called by MainActivity after Android's built-in 1-tap VPN consent dialog returns.
     */
    fun onVpnPermissionResult(granted: Boolean) {
        val targetLoc = pendingNordLynxLocation ?: _networkPrivacyState.value.selectedLocation
        val targetDns = pendingNordLynxDnsMode ?: _networkPrivacyState.value.privateDnsMode
        pendingNordLynxLocation = null
        pendingNordLynxDnsMode = null
        if (!granted) {
            _networkPrivacyState.update {
                it.copy(
                    isConnectingVpn = false,
                    nordVpnEnabled = false,
                    nordLynxTunnelUp = false,
                    loginError = "Android VPN permission is required to connect in-app NordLynx."
                )
            }
            viewModelScope.launch { prefsRepo.updateNordVpnEnabled(false) }
            return
        }
        viewModelScope.launch {
            connectNordLynxInternal(
                location = targetLoc,
                dnsMode = targetDns,
                reloadActiveStream = true,
                autoProbe = true
            )
        }
    }

    fun selectNordVpnLocation(location: NordVpnLocation) {
        val current = _networkPrivacyState.value
        _networkPrivacyState.update {
            it.copy(
                selectedLocation = location,
                selectedNordServerLabel = location.displayLabel,
                loginError = null,
                probeResult = null
            )
        }
        viewModelScope.launch {
            prefsRepo.updateNordVpnLocation(location)
            if (current.nordVpnLoggedIn && current.nordVpnEnabled) {
                if (activeNordLynxKey.isNotBlank()) {
                    connectNordLynxInternal(
                        location = location,
                        dnsMode = current.privateDnsMode,
                        reloadActiveStream = true,
                        autoProbe = true
                    )
                } else {
                    _networkPrivacyState.update {
                        it.copy(loginError = "Replace/re-enter your NordVPN Access Key to activate all-countries NordLynx.")
                    }
                }
            }
        }
        _uiState.update {
            it.copy(toastMessage = "NordVPN location set to ${location.displayLabel}")
        }
    }

    fun setNordVpnEnabled(enabled: Boolean) {
        val current = _networkPrivacyState.value
        val hasCreds = activeNordLynxKey.isNotBlank() ||
            (activeNordUsername.isNotBlank() && activeNordPassword.isNotBlank())
        if (enabled && !hasCreds) {
            _networkPrivacyState.update {
                it.copy(loginError = "Log in with your NordVPN Access Key first.")
            }
            return
        }
        _networkPrivacyState.update {
            it.copy(nordVpnEnabled = enabled, loginError = null, probeResult = null)
        }
        viewModelScope.launch {
            prefsRepo.updateNordVpnEnabled(enabled)
            if (enabled) {
                if (activeNordLynxKey.isNotBlank()) {
                    connectNordLynxInternal(
                        location = current.selectedLocation,
                        dnsMode = current.privateDnsMode,
                        reloadActiveStream = true,
                        autoProbe = true
                    )
                } else {
                    playerManager.applyNetworkPrivacyConfig(
                        dnsMode = current.privateDnsMode,
                        nordConfig = buildCurrentSocksFallbackConfig(_networkPrivacyState.value),
                        reloadActiveStream = true
                    )
                    probeNetworkPrivacyRoute()
                }
            } else {
                smartVpnFailover.suppressForCurrentSession()
                nordLynxController.disconnect()
                playerManager.applyNetworkPrivacyConfig(
                    dnsMode = current.privateDnsMode,
                    nordConfig = DynamicSocksProxyManager.ActiveConfig(),
                    reloadActiveStream = true
                )
                _networkPrivacyState.update {
                    it.copy(
                        nordLynxTunnelUp = false,
                        activeNordLynxHostname = "",
                        isConnectingVpn = false,
                        systemVpnActive = SystemVpnMonitor.isSystemVpnActive(getApplication())
                    )
                }
            }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        }
        _uiState.update {
            it.copy(
                toastMessage = if (enabled) {
                    "Connecting NordVPN (${current.selectedLocation.displayLabel})…"
                } else {
                    "NordVPN disconnected"
                }
            )
        }
    }

    fun selectNordVpnServer(server: NordVpnServer) {
        val current = _networkPrivacyState.value
        val matchedLoc = current.allLocations.firstOrNull {
            it.countryName.equals(server.country, ignoreCase = true) && it.cityId == null
        } ?: current.selectedLocation
        selectNordVpnLocation(matchedLoc)
    }

    fun logoutNordVpn() {
        activeNordUsername = ""
        activeNordPassword = ""
        activeNordLynxKey = ""
        pendingNordLynxLocation = null
        pendingNordLynxDnsMode = null
        val current = _networkPrivacyState.value
        _networkPrivacyState.update {
            it.copy(
                nordVpnLoggedIn = false,
                nordVpnEnabled = false,
                smartAutoVpnFailoverEnabled = false,
                nordLynxTunnelUp = false,
                activeNordLynxHostname = "",
                isConnectingVpn = false,
                loginError = null,
                probeResult = null
            )
        }
        viewModelScope.launch {
            nordLynxController.disconnect()
            playerManager.applyNetworkPrivacyConfig(
                dnsMode = current.privateDnsMode,
                nordConfig = DynamicSocksProxyManager.ActiveConfig(),
                reloadActiveStream = true
            )
            prefsRepo.clearNordVpnCredentials()
            _networkPrivacyState.update {
                it.copy(systemVpnActive = SystemVpnMonitor.isSystemVpnActive(getApplication()))
            }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        }
        _uiState.update {
            it.copy(toastMessage = "Signed out of NordVPN")
        }
    }

    /**
     * Disables both Cloudflare 1.1.1.1 / Google 8.8.8.8 Private DNS (back to System DNS) and
     * in-app NordVPN NordLynx/SOCKS5 routing in a single tap, while keeping the saved NordVPN key
     * & selected location intact so the user can re-enable anytime without re-entering their key.
     */
    fun disableAllPrivacyRouting() {
        smartVpnFailover.suppressForCurrentSession()
        pendingNordLynxLocation = null
        pendingNordLynxDnsMode = null
        _networkPrivacyState.update {
            it.copy(
                privateDnsMode = PrivateDnsMode.SYSTEM,
                nordVpnEnabled = false,
                nordLynxTunnelUp = false,
                activeNordLynxHostname = "",
                isConnectingVpn = false,
                loginError = null,
                probeResult = null
            )
        }
        viewModelScope.launch {
            nordLynxController.disconnect()
            playerManager.applyNetworkPrivacyConfig(
                dnsMode = PrivateDnsMode.SYSTEM,
                nordConfig = DynamicSocksProxyManager.ActiveConfig(),
                reloadActiveStream = true
            )
            prefsRepo.updatePrivateDnsMode(PrivateDnsMode.SYSTEM)
            prefsRepo.updateNordVpnEnabled(false)
            _networkPrivacyState.update {
                it.copy(systemVpnActive = SystemVpnMonitor.isSystemVpnActive(getApplication()))
            }
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
        }
        _uiState.update {
            it.copy(toastMessage = "1.1.1.1 & NordVPN disabled (Direct ISP)")
        }
    }

    private suspend fun connectNordLynxInternal(
        location: NordVpnLocation,
        dnsMode: PrivateDnsMode,
        reloadActiveStream: Boolean,
        autoProbe: Boolean
    ) {
        val privateKey = activeNordLynxKey.trim()
        if (privateKey.isBlank()) return

        // Request Android's built-in 1-tap VPN consent dialog if not yet approved (stays inside CineStream)
        val consentIntent = nordLynxController.prepareVpnPermissionIntent()
        if (consentIntent != null) {
            pendingNordLynxLocation = location
            pendingNordLynxDnsMode = dnsMode
            _networkPrivacyState.update { it.copy(isConnectingVpn = true, loginError = null) }
            _vpnConsentEvents.tryEmit(consentIntent)
            return
        }

        _networkPrivacyState.update { it.copy(isConnectingVpn = true, loginError = null) }
        val endpoint = nordVpnClient.fetchRecommendedNordLynxServer(location)
        if (endpoint == null) {
            _networkPrivacyState.update {
                it.copy(
                    isConnectingVpn = false,
                    loginError = "No NordLynx server responded for ${location.displayLabel}. Try another location."
                )
            }
            return
        }

        // Disable OkHttp SOCKS5 so traffic routes cleanly through the native WireGuard tunnel
        playerManager.applyNetworkPrivacyConfig(
            dnsMode = dnsMode,
            nordConfig = DynamicSocksProxyManager.ActiveConfig(),
            reloadActiveStream = false
        )

        val connectResult = nordLynxController.connect(
            privateKey = privateKey,
            endpoint = endpoint,
            dnsMode = dnsMode
        )

        if (connectResult.isSuccess) {
            val sysVpn = SystemVpnMonitor.isSystemVpnActive(getApplication())
            _networkPrivacyState.update {
                it.copy(
                    nordVpnEnabled = true,
                    nordLynxTunnelUp = true,
                    isConnectingVpn = false,
                    activeNordLynxHostname = endpoint.hostname,
                    selectedNordServerHost = endpoint.hostname,
                    selectedNordServerLabel = location.displayLabel,
                    systemVpnActive = sysVpn,
                    loginError = null
                )
            }
            playerManager.applyNetworkPrivacyConfig(
                dnsMode = dnsMode,
                nordConfig = DynamicSocksProxyManager.ActiveConfig(),
                nordLynxActive = true,
                nordLynxHost = endpoint.hostname,
                reloadActiveStream = reloadActiveStream
            )
            if (statsForNerdsEnabled.value) {
                playerManager.syncNetworkStats()
            }
            if (autoProbe) {
                delay(400L) // allow TUN routes to settle before tracing exit IP
                probeNetworkPrivacyRoute()
            }
        } else {
            val err = connectResult.exceptionOrNull()?.message ?: "Unable to establish NordLynx tunnel"
            _networkPrivacyState.update {
                it.copy(
                    isConnectingVpn = false,
                    nordLynxTunnelUp = false,
                    loginError = "NordLynx connection failed: $err"
                )
            }
        }
    }

    fun probeNetworkPrivacyRoute() {
        viewModelScope.launch {
            refreshSystemVpnStatus()
            val state = _networkPrivacyState.value
            _networkPrivacyState.update { it.copy(isProbingRoute = true) }
            val activeHost = state.activeNordLynxHostname.ifBlank { state.selectedLocation.displayLabel }
            val result = nordVpnClient.probeActiveRoute(
                activeStreamingClient = playerManager.okHttpClient,
                dnsMode = state.privateDnsMode,
                nordEnabled = state.nordVpnLoggedIn && state.nordVpnEnabled,
                nordServerHost = activeHost,
                systemVpnActive = state.systemVpnActive || state.nordLynxTunnelUp
            )
            _networkPrivacyState.update {
                it.copy(isProbingRoute = false, probeResult = result)
            }
        }
    }

    fun setSmartAutoVpnFailoverEnabled(enabled: Boolean) {
        val current = _networkPrivacyState.value
        if (enabled && !current.nordVpnLoggedIn) {
            _networkPrivacyState.update {
                it.copy(loginError = "Log in with your NordVPN Access Key to enable Smart Auto-VPN Failover.")
            }
            return
        }
        _networkPrivacyState.update { it.copy(smartAutoVpnFailoverEnabled = enabled) }
        viewModelScope.launch {
            prefsRepo.updateSmartAutoVpnFailover(enabled)
        }
        _uiState.update {
            it.copy(
                toastMessage = if (enabled) {
                    "Smart Auto-VPN Failover enabled (${current.selectedLocation.displayLabel})"
                } else {
                    "Smart Auto-VPN Failover disabled"
                }
            )
        }
    }

    private fun handleSmartVpnStallCandidate(estimatedBps: Long) {
        val privacy = _networkPrivacyState.value
        val isLocal = _uiState.value.activeSession?.isLocalFile ?: false
        val isNordActive = privacy.nordVpnEnabled || privacy.nordLynxTunnelUp || privacy.systemVpnActive
        val shouldFailover = smartVpnFailover.recordRebufferStall(
            isFeatureEnabled = privacy.smartAutoVpnFailoverEnabled,
            hasNordCredentials = privacy.nordVpnLoggedIn,
            isNordAlreadyActive = isNordActive,
            isLocalMedia = isLocal,
            estimatedBandwidthBps = estimatedBps
        )
        if (shouldFailover) {
            triggerSmartVpnFailover("Direct ISP stalled")
        }
    }

    private fun handleSmartVpnFatalNetworkError(reason: String): Boolean {
        val privacy = _networkPrivacyState.value
        val isLocal = _uiState.value.activeSession?.isLocalFile ?: false
        val isNordActive = privacy.nordVpnEnabled || privacy.nordLynxTunnelUp || privacy.systemVpnActive
        val shouldFailover = smartVpnFailover.recordFatalNetworkError(
            isFeatureEnabled = privacy.smartAutoVpnFailoverEnabled,
            hasNordCredentials = privacy.nordVpnLoggedIn,
            isNordAlreadyActive = isNordActive,
            isLocalMedia = isLocal
        )
        if (shouldFailover) {
            triggerSmartVpnFailover("Upstream error ($reason)")
            return true
        }
        return false
    }

    private fun triggerSmartVpnFailover(triggerReason: String) {
        val current = _networkPrivacyState.value
        val loc = current.selectedLocation
        _uiState.update {
            it.copy(toastMessage = "$triggerReason → Auto-connecting NordVPN (${loc.displayLabel})…")
        }
        viewModelScope.launch {
            if (activeNordLynxKey.isNotBlank()) {
                connectNordLynxInternal(
                    location = loc,
                    dnsMode = current.privateDnsMode,
                    reloadActiveStream = true,
                    autoProbe = false
                )
            } else if (activeNordUsername.isNotBlank() && activeNordPassword.isNotBlank()) {
                _networkPrivacyState.update { it.copy(nordVpnEnabled = true) }
                playerManager.applyNetworkPrivacyConfig(
                    dnsMode = current.privateDnsMode,
                    nordConfig = buildCurrentSocksFallbackConfig(_networkPrivacyState.value),
                    reloadActiveStream = true
                )
            }
        }
    }

    fun launchCompanionVpnApp(packageName: String) {
        // Kept as no-op fallback; all 118+ NordVPN countries run natively inside CineStream.
    }

    private fun buildCurrentSocksFallbackConfig(state: NetworkPrivacyUiState): DynamicSocksProxyManager.ActiveConfig {
        val useSocks = state.nordVpnLoggedIn &&
            state.nordVpnEnabled &&
            activeNordLynxKey.isBlank() &&
            activeNordUsername.isNotBlank() &&
            activeNordPassword.isNotBlank()
        return if (useSocks) {
            DynamicSocksProxyManager.ActiveConfig(
                enabled = true,
                host = state.selectedNordServerHost,
                port = NordVpnServer.DEFAULT_SOCKS5_PORT,
                username = activeNordUsername,
                password = activeNordPassword
            )
        } else {
            DynamicSocksProxyManager.ActiveConfig()
        }
    }

    fun retryWithDecoderMode(mode: VideoDecoderMode) {
        playerManager.retryWithDecoderMode(mode)
        _uiState.update {
            it.copy(toastMessage = "Retrying stream with ${mode.label}")
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    fun skipBadSegmentAndRetry() {
        playerManager.skipBadSegmentAndRetry(deltaMs = 5_000L)
        _uiState.update {
            it.copy(toastMessage = "Skipped +5s past bad frame (cache bypassed)")
        }
    }

    private fun startJsonImdbEnrichment(initialItems: List<JsonPlaylistItem>) {
        jsonEnrichJob?.cancel()
        jsonEnrichJob = viewModelScope.launch {
            val needsEnrichment = initialItems.filter {
                it.imageUrl.isNullOrBlank() || it.imdbRating.isNullOrBlank() || it.imdbId.isNullOrBlank()
            }
            if (needsEnrichment.isEmpty()) return@launch

            _jsonPlaylistState.update { it.copy(isEnrichingImdb = true) }
            try {
                val semaphore = Semaphore(2)
                val updatedMap = coroutineScope {
                    needsEnrichment.map { item ->
                        async {
                            semaphore.withPermit {
                                val info = imdbClient.lookup(
                                    imdbId = item.imdbId,
                                    titleQuery = item.title,
                                    releaseYear = item.releaseYear
                                )
                                if (info != null) {
                                    item.id to item.copy(
                                        imageUrl = item.imageUrl ?: info.posterUrl,
                                        imdbId = item.imdbId ?: info.imdbId,
                                        imdbRating = item.imdbRating ?: info.rating,
                                        releaseYear = item.releaseYear ?: info.year,
                                        description = item.description ?: info.plot ?: info.cast,
                                        groupTitle = if (item.groupTitle == "Movies" && !info.genres.isNullOrBlank()) {
                                            info.genres
                                        } else {
                                            item.groupTitle
                                        }
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
                if (updatedMap.isNotEmpty()) {
                    _jsonPlaylistState.update { state ->
                        MultiJsonPlaylistReducer.applyImdbUpdates(state, updatedMap)
                    }
                }
            } finally {
                _jsonPlaylistState.update { it.copy(isEnrichingImdb = false) }
            }
        }
    }

    private suspend fun enrichSamplesWithImdb() {
        val current = _sampleStreams.value
        val semaphore = Semaphore(2)
        val enriched = coroutineScope {
            current.map { sample ->
                async {
                    semaphore.withPermit {
                        val info = imdbClient.lookup(
                            imdbId = sample.imdbId,
                            titleQuery = sample.title,
                            releaseYear = sample.releaseYear
                        )
                        if (info != null) {
                            sample.copy(
                                posterUrl = sample.posterUrl ?: info.posterUrl,
                                imdbId = sample.imdbId ?: info.imdbId,
                                imdbRating = sample.imdbRating ?: info.rating,
                                releaseYear = sample.releaseYear ?: info.year
                            )
                        } else {
                            sample
                        }
                    }
                }
            }.awaitAll()
        }
        _sampleStreams.value = enriched
    }

    fun playHistoryItem(item: MediaHistoryEntity) {
        viewModelScope.launch {
            val format = runCatching { StreamFormat.valueOf(item.formatName) }
                .getOrDefault(MediaFormatDetector.detectFormat(item.sourceUri))
            val subs = mutableListOf<ExternalSubtitle>()
            if (!item.subtitleUri.isNullOrBlank()) {
                val mime = item.subtitleMimeType
                    ?: subtitleHelper.detectMimeFromName(item.subtitleName ?: item.subtitleUri)
                    ?: androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                subs.add(
                    ExternalSubtitle(
                        id = "saved_${item.id}",
                        uri = item.subtitleUri,
                        label = item.subtitleName ?: "Saved Subtitle",
                        mimeType = mime,
                        formatBadge = subtitleHelper.mimeToBadge(mime),
                        isLocalFile = item.subtitleUri.startsWith("content://") || item.subtitleUri.startsWith("file://")
                    )
                )
            }
            val resumeMs = if (item.durationMs > 0 && item.lastPositionMs < item.durationMs - 5_000L) {
                item.lastPositionMs
            } else {
                0L
            }

            // Re-attach mirrors & headers if this item still exists in an active JSON playlist
            val matchingPlaylistItem = _jsonPlaylistState.value.allItems.firstOrNull { candidate ->
                (item.seriesId != null && candidate.id == item.seriesId) ||
                    candidate.url == item.sourceUri
            }
            val matchingEpisode = if (matchingPlaylistItem != null && item.episodeId != null) {
                matchingPlaylistItem.seasons.flatMap { it.episodes }.firstOrNull { it.id == item.episodeId }
            } else {
                null
            }

            startPlayback(
                title = item.title,
                sourceUri = item.sourceUri,
                format = format,
                isLocalFile = item.isLocalFile,
                initialSubtitles = subs,
                resumePositionMs = resumeMs,
                mirrors = matchingEpisode?.mirrors ?: matchingPlaylistItem?.mirrors.orEmpty(),
                headers = matchingEpisode?.headers ?: matchingPlaylistItem?.headers.orEmpty(),
                seriesItemId = item.seriesId,
                seriesTitle = item.seriesTitle,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeId = item.episodeId,
                episodeCodeLabel = ContinueWatchingHelper.formatEpisodeCode(item.seasonNumber, item.episodeNumber),
                posterUrl = item.posterUrl ?: matchingPlaylistItem?.imageUrl,
                playlistId = item.playlistId
            )
        }
    }

    fun dismissContinueWatchingItem(item: MediaHistoryEntity) {
        viewModelScope.launch {
            historyRepo.clearResumeProgress(item.id)
        }
        _uiState.update { it.copy(toastMessage = "Removed from Continue Watching") }
    }

    fun handleExternalIntentUri(uri: Uri) {
        viewModelScope.launch {
            val uriStr = uri.toString()
            if (!MediaFormatDetector.isValidMediaLink(uriStr)) return@launch
            if (uri.scheme == "content" || uri.scheme == "file") {
                openLocalVideo(uri)
            } else {
                _uiState.update {
                    it.copy(
                        urlInput = uriStr,
                        detectedFormat = MediaFormatDetector.detectFormat(uriStr)
                    )
                }
                launchFromUrlInput()
            }
        }
    }

    private suspend fun startPlayback(
        title: String,
        sourceUri: String,
        format: StreamFormat,
        isLocalFile: Boolean,
        initialSubtitles: List<ExternalSubtitle>,
        resumePositionMs: Long? = null,
        mirrors: List<String> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        seriesItemId: String? = null,
        seriesTitle: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeId: String? = null,
        episodeCodeLabel: String? = null,
        posterUrl: String? = null,
        playlistId: String? = null
    ) {
        smartVpnFailover.onNewMediaSession(sourceUri)
        val existingRecord = historyRepo.getBySourceUri(sourceUri)
        val mergedSubs = initialSubtitles.ifEmpty {
            val savedUri = existingRecord?.subtitleUri
            if (!savedUri.isNullOrBlank()) {
                val mime = existingRecord.subtitleMimeType
                    ?: subtitleHelper.detectMimeFromName(existingRecord.subtitleName ?: savedUri)
                    ?: androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                listOf(
                    ExternalSubtitle(
                        id = "saved_${existingRecord.id}",
                        uri = savedUri,
                        label = existingRecord.subtitleName ?: "Saved Subtitle",
                        mimeType = mime,
                        formatBadge = subtitleHelper.mimeToBadge(mime),
                        isLocalFile = savedUri.startsWith("content://") || savedUri.startsWith("file://")
                    )
                )
            } else {
                emptyList()
            }
        }

        val startPos = resumePositionMs ?: run {
            if (existingRecord != null &&
                existingRecord.durationMs > 0L &&
                existingRecord.lastPositionMs in 3_000L until (existingRecord.durationMs - 5_000L)
            ) {
                existingRecord.lastPositionMs
            } else {
                0L
            }
        }

        val restoredSubOffset = (existingRecord?.subtitleOffsetMs ?: 0L).coerceIn(-5_000L, 5_000L)
        val restoredAudioDelay = AudioDelayMath.clampDelayMs(existingRecord?.audioDelayMs ?: 0L)
        playerManager.setAudioDelayMs(restoredAudioDelay)

        val session = ActiveMediaSession(
            title = title,
            sourceUri = sourceUri,
            format = format,
            isLocalFile = isLocalFile,
            externalSubtitles = mergedSubs,
            subtitleOffsetMs = restoredSubOffset,
            mirrors = mirrors,
            headers = headers,
            seriesItemId = seriesItemId ?: existingRecord?.seriesId,
            seriesTitle = seriesTitle ?: existingRecord?.seriesTitle,
            seasonNumber = seasonNumber ?: existingRecord?.seasonNumber,
            episodeNumber = episodeNumber ?: existingRecord?.episodeNumber,
            episodeId = episodeId ?: existingRecord?.episodeId,
            episodeCodeLabel = episodeCodeLabel ?: ContinueWatchingHelper.formatEpisodeCode(
                seasonNumber ?: existingRecord?.seasonNumber,
                episodeNumber ?: existingRecord?.episodeNumber
            ),
            posterUrl = posterUrl ?: existingRecord?.posterUrl,
            playlistId = playlistId ?: existingRecord?.playlistId
        )

        _uiState.update { it.copy(activeSession = session) }
        clearAbLoop(silent = true)
        dismissUpNextCountdown(rememberDismissal = false)
        dismissedUpNextEpisodeId = null
        _chapters.value = emptyList()
        _secondarySubtitleState.update { it.copy(currentText = null) }

        val resolvedPairs = mergedSubs.map { sub ->
            sub to subtitleHelper.resolvePlayableSubtitleUri(sub, restoredSubOffset)
        }

        playerManager.loadMedia(
            mediaUri = sourceUri,
            format = format,
            externalSubtitles = resolvedPairs,
            startPositionMs = startPos,
            autoPlay = true,
            autoSelectSubtitleId = mergedSubs.lastOrNull()?.id,
            mirrors = mirrors,
            headers = headers
        )

        historyRepo.recordPlayback(
            title = title,
            sourceUri = sourceUri,
            format = format,
            isLocalFile = isLocalFile,
            subtitle = mergedSubs.lastOrNull(),
            seriesId = session.seriesItemId,
            seriesTitle = session.seriesTitle,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            episodeId = session.episodeId,
            posterUrl = session.posterUrl,
            playlistId = session.playlistId
        )

        syncBackgroundAudioNotification()
        _navEvents.tryEmit(CineNavigationEvent.OpenPlayer)
    }

    // ==================== NETWORK & BUFFER STATS OVERLAY ====================

    fun toggleStatsForNerds() {
        setStatsForNerdsEnabled(!statsForNerdsEnabled.value)
    }

    fun setStatsForNerdsEnabled(enabled: Boolean) {
        if (enabled) {
            playerManager.syncNetworkStats()
        }
        viewModelScope.launch {
            prefsRepo.updateStatsForNerdsEnabled(enabled)
        }
    }

    // ==================== EXTERNAL SUBTITLES & TRACKS ====================

    fun loadExternalSubtitleFileIntoPlayer(uri: Uri) {
        val currentSession = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            val newSub = subtitleHelper.buildFromLocalUri(uri)
            attachSubtitleToActiveSession(currentSession, newSub)
        }
    }

    fun loadExternalSubtitleUrlIntoPlayer(url: String) {
        val currentSession = _uiState.value.activeSession ?: return
        val trimmed = url.trim()
        if (!MediaFormatDetector.isValidMediaLink(trimmed)) {
            _uiState.update { it.copy(toastMessage = "Enter a valid http:// or https:// subtitle URL") }
            return
        }
        viewModelScope.launch {
            val newSub = subtitleHelper.buildFromRemoteUrl(trimmed)
            attachSubtitleToActiveSession(currentSession, newSub)
        }
    }

    private suspend fun attachSubtitleToActiveSession(
        currentSession: ActiveMediaSession,
        newSub: ExternalSubtitle
    ) {
        val resumePos = playerManager.player.currentPosition.coerceAtLeast(0L)

        val updatedList = currentSession.externalSubtitles
            .filterNot { it.uri == newSub.uri } + newSub
        val updatedSession = currentSession.copy(
            externalSubtitles = updatedList,
            subtitleOffsetMs = 0L
        )
        _uiState.update {
            it.copy(
                activeSession = updatedSession,
                toastMessage = "Loaded subtitle: ${newSub.label} (${newSub.formatBadge})"
            )
        }

        val resolvedPairs = updatedList.map { sub ->
            sub to subtitleHelper.resolvePlayableSubtitleUri(sub, 0L)
        }

        playerManager.loadMedia(
            mediaUri = updatedSession.sourceUri,
            format = updatedSession.format,
            externalSubtitles = resolvedPairs,
            startPositionMs = resumePos,
            autoPlay = true,
            autoSelectSubtitleId = newSub.id,
            mirrors = updatedSession.mirrors,
            headers = updatedSession.headers
        )

        historyRepo.saveAttachedSubtitle(updatedSession.sourceUri, newSub)
    }

    fun adjustSubtitleOffset(deltaMs: Long) {
        val currentSession = _uiState.value.activeSession ?: return
        if (currentSession.externalSubtitles.isEmpty()) return
        val newOffset = (currentSession.subtitleOffsetMs + deltaMs).coerceIn(-5_000L, 5_000L)
        applySubtitleOffset(currentSession, newOffset)
    }

    fun resetSubtitleOffset() {
        val currentSession = _uiState.value.activeSession ?: return
        if (currentSession.subtitleOffsetMs == 0L) return
        applySubtitleOffset(currentSession, 0L)
    }

    private fun applySubtitleOffset(session: ActiveMediaSession, newOffsetMs: Long) {
        viewModelScope.launch {
            val resumePos = playerManager.player.currentPosition.coerceAtLeast(0L)
            val wasPlaying = playerManager.player.playWhenReady
            val updatedSession = session.copy(subtitleOffsetMs = newOffsetMs)
            _uiState.update { it.copy(activeSession = updatedSession) }

            val resolvedPairs = updatedSession.externalSubtitles.map { sub ->
                sub to subtitleHelper.resolvePlayableSubtitleUri(sub, newOffsetMs)
            }
            playerManager.loadMedia(
                mediaUri = updatedSession.sourceUri,
                format = updatedSession.format,
                externalSubtitles = resolvedPairs,
                startPositionMs = resumePos,
                autoPlay = wasPlaying,
                autoSelectSubtitleId = updatedSession.externalSubtitles.lastOrNull()?.id,
                mirrors = updatedSession.mirrors,
                headers = updatedSession.headers
            )
            historyRepo.saveSyncOffsets(
                sourceUri = updatedSession.sourceUri,
                subtitleOffsetMs = newOffsetMs,
                audioDelayMs = playbackState.value.audioDelayMs
            )
        }
    }

    fun adjustAudioDelay(deltaMs: Long) {
        val next = playerManager.adjustAudioDelayMs(deltaMs)
        val session = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            historyRepo.saveSyncOffsets(
                sourceUri = session.sourceUri,
                subtitleOffsetMs = session.subtitleOffsetMs,
                audioDelayMs = next
            )
        }
    }

    fun resetAudioDelay() {
        playerManager.resetAudioDelayMs()
        val session = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            historyRepo.saveSyncOffsets(
                sourceUri = session.sourceUri,
                subtitleOffsetMs = session.subtitleOffsetMs,
                audioDelayMs = 0L
            )
        }
    }

    fun setBackgroundAudioEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.updateBackgroundAudioEnabled(enabled)
        }
        syncBackgroundAudioNotification(forceEnabled = enabled)
        _uiState.update {
            it.copy(
                toastMessage = if (enabled) {
                    "Background Audio ON — plays with screen off"
                } else {
                    "Background Audio OFF"
                }
            )
        }
    }

    fun syncBackgroundAudioNotification(forceEnabled: Boolean? = null) {
        val enabled = forceEnabled ?: backgroundAudioEnabled.value
        val session = _uiState.value.activeSession
        val appCtx = getApplication<Application>()
        if (enabled && session != null && playerManager.hasLoadedMedia()) {
            val subtitle = session.episodeCodeLabel
                ?.let { "${session.seriesTitle ?: session.title} · $it" }
                ?: "Background audio playing"
            MediaPlaybackService.startOrUpdate(
                context = appCtx,
                title = session.title,
                subtitle = subtitle,
                isPlaying = playerManager.player.isPlaying
            )
        } else {
            MediaPlaybackService.stop(appCtx)
        }
    }

    fun retryWithFormat(overrideFormat: StreamFormat) {
        val session = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            val updated = session.copy(format = overrideFormat)
            _uiState.update { it.copy(activeSession = updated) }
            val resolvedPairs = updated.externalSubtitles.map { sub ->
                sub to subtitleHelper.resolvePlayableSubtitleUri(sub, updated.subtitleOffsetMs)
            }
            playerManager.loadMedia(
                mediaUri = updated.sourceUri,
                format = overrideFormat,
                externalSubtitles = resolvedPairs,
                startPositionMs = playerManager.player.currentPosition.coerceAtLeast(0L),
                autoPlay = true,
                autoSelectSubtitleId = updated.externalSubtitles.lastOrNull()?.id,
                mirrors = updated.mirrors,
                headers = updated.headers
            )
        }
    }

    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        playerManager.setVolumePercent(clamped)
        volumePersistJob?.cancel()
        volumePersistJob = viewModelScope.launch {
            delay(250L)
            prefsRepo.updateVolumeBoostPercent(clamped)
        }
    }

    fun selectSubtitleTrack(option: TrackOption) = playerManager.selectSubtitleTrack(option)
    fun disableSubtitles() = playerManager.disableSubtitles()
    fun selectAudioTrack(option: TrackOption) = playerManager.selectAudioTrack(option)
    fun selectVideoTrack(option: TrackOption?) = playerManager.selectVideoTrack(option)

    fun updateSubtitleTextSize(size: SubtitleTextSize) {
        viewModelScope.launch { prefsRepo.updateTextSize(size) }
    }

    fun updateSubtitleColor(color: SubtitleColorPreset) {
        viewModelScope.launch { prefsRepo.updateColorPreset(color) }
    }

    fun updateSubtitleBg(bg: SubtitleBgPreset) {
        viewModelScope.launch { prefsRepo.updateBgPreset(bg) }
    }

    fun cycleAspectRatio() {
        val modes = AspectRatioMode.entries
        val next = modes[(modes.indexOf(aspectRatioMode.value) + 1) % modes.size]
        viewModelScope.launch { prefsRepo.updateAspectRatio(next) }
    }

    fun togglePinHistoryItem(item: MediaHistoryEntity) {
        viewModelScope.launch { historyRepo.togglePin(item) }
    }

    fun deleteHistoryItem(item: MediaHistoryEntity) {
        viewModelScope.launch { historyRepo.deleteItem(item.id) }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun pauseAndPersistProgress() {
        playerManager.pause()
        persistCurrentPosition()
    }

    /**
     * Stops playback, persists resume progress, and immediately frees ExoPlayer's Java-heap
     * buffers BEFORE navigating back to HomeScreen so AnimatedContent/HomeScreen never coexist
     * with live video buffers on constrained Android TVs.
     */
    fun exitPlayerAndReleaseBuffers() {
        persistCurrentPosition()
        MediaPlaybackService.stop(getApplication())
        playerManager.stopAndTrimBuffers()
    }

    /**
     * Resumes the active media session from HomeScreen, reloading ExoPlayer at its saved position
     * if buffers were trimmed on the previous exit.
     */
    fun resumeActiveSession() {
        val session = _uiState.value.activeSession ?: return
        if (playerManager.hasLoadedMedia()) {
            playerManager.player.play()
            syncBackgroundAudioNotification()
            _navEvents.tryEmit(CineNavigationEvent.OpenPlayer)
            return
        }
        viewModelScope.launch {
            val resumeMs = playbackProgressState.value.currentPositionMs.takeIf { it > 0L }
            startPlayback(
                title = session.title,
                sourceUri = session.sourceUri,
                format = session.format,
                isLocalFile = session.isLocalFile,
                initialSubtitles = session.externalSubtitles,
                resumePositionMs = resumeMs,
                mirrors = session.mirrors,
                headers = session.headers,
                seriesItemId = session.seriesItemId,
                seriesTitle = session.seriesTitle,
                seasonNumber = session.seasonNumber,
                episodeNumber = session.episodeNumber,
                episodeId = session.episodeId,
                episodeCodeLabel = session.episodeCodeLabel,
                posterUrl = session.posterUrl,
                playlistId = session.playlistId
            )
        }
    }

    fun persistCurrentPosition() {
        val session = _uiState.value.activeSession ?: return
        playerManager.syncPlaybackProgress()
        val progress = playbackProgressState.value
        if (progress.currentPositionMs > 0L) {
            lastSavedProgressUri = session.sourceUri
            lastSavedProgressMs = progress.currentPositionMs
            viewModelScope.launch {
                historyRepo.saveProgress(
                    sourceUri = session.sourceUri,
                    positionMs = progress.currentPositionMs,
                    durationMs = progress.durationMs
                )
            }
        }
    }

    private fun startProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val session = _uiState.value.activeSession
                val isPlayingOrBuffering = playerManager.player.isPlaying ||
                    playerManager.player.playbackState == androidx.media3.common.Player.STATE_BUFFERING

                if (session != null && isPlayingOrBuffering) {
                    playerManager.syncPlaybackProgress()
                    val progress = playbackProgressState.value
                    updateDualSubtitleCue(progress.currentPositionMs)
                    if (progress.durationMs > 0L && _chapters.value.isEmpty() && !progress.isLiveStream) {
                        _chapters.value = buildDefaultChapters(progress.durationMs)
                    }
                    if (!progress.isLiveStream &&
                        progress.durationMs > 30_000L &&
                        (progress.durationMs - progress.currentPositionMs) in 1L..15_000L &&
                        autoPlayUpNextEnabled.value &&
                        _sleepTimerState.value.mode != SleepTimerMode.END_OF_EPISODE
                    ) {
                        maybeStartUpNextCountdown(forceImmediateOnEnd = false)
                    }
                    tick++
                    if (tick % 2 == 0 && statsForNerdsEnabled.value) {
                        playerManager.syncNetworkStats()
                    }
                    if (tick % 10 == 0 && playbackState.value.isPlaying) {
                        val movedEnough = session.sourceUri != lastSavedProgressUri ||
                            abs(progress.currentPositionMs - lastSavedProgressMs) >= 2_000L
                        if (progress.currentPositionMs > 0L && movedEnough) {
                            lastSavedProgressUri = session.sourceUri
                            lastSavedProgressMs = progress.currentPositionMs
                            historyRepo.saveProgress(
                                sourceUri = session.sourceUri,
                                positionMs = progress.currentPositionMs,
                                durationMs = progress.durationMs
                            )
                        }
                    }
                    delay(500L)
                } else {
                    if (session != null && playerManager.hasLoadedMedia()) {
                        playerManager.syncPlaybackProgress()
                        updateDualSubtitleCue(playbackProgressState.value.currentPositionMs)
                        if (statsForNerdsEnabled.value) {
                            playerManager.syncNetworkStats()
                        }
                    }
                    delay(1_000L)
                }
            }
        }
    }

    // ==================== V1.24: SLEEP TIMER ====================

    fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimerJob?.cancel()
        playerManager.setSleepFadeMultiplier(1.0f)
        if (mode == SleepTimerMode.OFF) {
            _sleepTimerState.value = SleepTimerUiState()
            _uiState.update { it.copy(toastMessage = "Sleep Timer turned off") }
            return
        }
        if (mode == SleepTimerMode.END_OF_EPISODE) {
            _sleepTimerState.value = SleepTimerUiState(mode = mode, remainingMs = 0L, isFadingOut = false)
            _uiState.update { it.copy(toastMessage = "Sleep Timer set to End of Episode") }
            return
        }
        val totalMs = mode.minutes * 60_000L
        _sleepTimerState.value = SleepTimerUiState(mode = mode, remainingMs = totalMs, isFadingOut = false)
        _uiState.update { it.copy(toastMessage = "Sleep Timer set for ${mode.minutes} minutes") }
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMs
            while (isActive && remaining > 0L) {
                delay(1_000L)
                remaining = (remaining - 1_000L).coerceAtLeast(0L)
                val fading = remaining in 1L..5_000L
                if (fading) {
                    playerManager.setSleepFadeMultiplier(remaining / 5_000f)
                }
                _sleepTimerState.value = SleepTimerUiState(
                    mode = mode,
                    remainingMs = remaining,
                    isFadingOut = fading
                )
            }
            if (isActive) {
                playerManager.pause()
                playerManager.setSleepFadeMultiplier(1.0f)
                persistCurrentPosition()
                _sleepTimerState.value = SleepTimerUiState()
                _uiState.update { it.copy(toastMessage = "Sleep Timer paused playback") }
            }
        }
    }

    fun cancelSleepTimer(silent: Boolean = false) {
        sleepTimerJob?.cancel()
        playerManager.setSleepFadeMultiplier(1.0f)
        _sleepTimerState.value = SleepTimerUiState()
        if (!silent) {
            _uiState.update { it.copy(toastMessage = "Sleep Timer cancelled") }
        }
    }

    // ==================== V1.24: UP-NEXT COUNTDOWN ====================

    private fun maybeStartUpNextCountdown(forceImmediateOnEnd: Boolean): Boolean {
        if (_upNextCountdown.value != null) return true
        val session = _uiState.value.activeSession ?: return false
        val seriesId = session.seriesItemId ?: return false
        val series = _jsonPlaylistState.value.allItems.firstOrNull { it.id == seriesId } ?: return false
        val nextEp = series.findNextEpisode(session.episodeId) ?: return false
        if (!forceImmediateOnEnd && dismissedUpNextEpisodeId == nextEp.id) return false

        val totalSecs = 10
        _upNextCountdown.value = UpNextCountdownState(
            series = series,
            nextEpisode = nextEp,
            secondsRemaining = totalSecs,
            totalSeconds = totalSecs
        )
        upNextCountdownJob?.cancel()
        upNextCountdownJob = viewModelScope.launch {
            var secs = totalSecs
            while (isActive && secs > 0) {
                delay(1_000L)
                secs--
                _upNextCountdown.update { cur -> cur?.copy(secondsRemaining = secs) }
            }
            if (isActive && _upNextCountdown.value != null) {
                val pending = _upNextCountdown.value
                _upNextCountdown.value = null
                if (pending != null) {
                    playSeriesEpisode(pending.series, pending.nextEpisode)
                }
            }
        }
        return true
    }

    fun playUpNextNow() {
        val pending = _upNextCountdown.value
        upNextCountdownJob?.cancel()
        _upNextCountdown.value = null
        if (pending != null) {
            playSeriesEpisode(pending.series, pending.nextEpisode)
        } else {
            playNextEpisode()
        }
    }

    fun dismissUpNextCountdown(rememberDismissal: Boolean = true) {
        if (rememberDismissal) {
            dismissedUpNextEpisodeId = _upNextCountdown.value?.nextEpisode?.id
        }
        upNextCountdownJob?.cancel()
        _upNextCountdown.value = null
    }

    fun setAutoPlayUpNextEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.updateAutoPlayUpNextEnabled(enabled) }
    }

    // ==================== V1.24: NIGHT MODE, LANGUAGES, SKIP, 2X HOLD ====================

    fun setNightModeAudioEnabled(enabled: Boolean) {
        playerManager.setNightModeAudio(enabled)
        viewModelScope.launch { prefsRepo.updateNightModeAudioEnabled(enabled) }
        _uiState.update {
            it.copy(
                toastMessage = if (enabled) {
                    "Night Mode ON — Dialogue Boost & Dynamic Limiter active"
                } else {
                    "Night Mode OFF"
                }
            )
        }
    }

    fun setPreferredAudioLanguage(langCode: String) {
        viewModelScope.launch { prefsRepo.updatePreferredAudioLanguage(langCode) }
        playerManager.applyPreferredLanguages(langCode, preferredSubtitleLanguage.value)
        _uiState.update { it.copy(toastMessage = "Default audio language updated") }
    }

    fun setPreferredSubtitleLanguage(langCode: String) {
        viewModelScope.launch { prefsRepo.updatePreferredSubtitleLanguage(langCode) }
        playerManager.applyPreferredLanguages(preferredAudioLanguage.value, langCode)
        _uiState.update { it.copy(toastMessage = "Default subtitle language updated") }
    }

    fun setSeekIntervalSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(5, 30)
        viewModelScope.launch { prefsRepo.updateSeekIntervalSeconds(clamped) }
        _uiState.update { it.copy(toastMessage = "Skip interval set to ${clamped}s") }
    }

    fun setTemporaryFastForward(active: Boolean) {
        playerManager.setTemporarySpeed(if (active) 2.0f else null)
    }

    fun skipIntro() {
        playerManager.seekBy(85_000L)
        _uiState.update { it.copy(toastMessage = "Skipped intro (+85s)") }
    }

    // ==================== V1.24: A-B LOOP & CHAPTER MARKERS ====================

    fun setAbLoopPointA() {
        val pos = playerManager.player.currentPosition.coerceAtLeast(0L)
        _abLoopState.value = AbLoopState(startMs = pos, endMs = null)
        playerManager.setAbLoopBounds(pos, null)
        _uiState.update { it.copy(toastMessage = "A-B Loop point A set") }
    }

    fun setAbLoopPointB() {
        val cur = _abLoopState.value
        val pos = playerManager.player.currentPosition.coerceAtLeast(0L)
        val start = cur.startMs ?: (pos - 10_000L).coerceAtLeast(0L)
        if (pos <= start + 500L) {
            _uiState.update { it.copy(toastMessage = "Point B must be after Point A") }
            return
        }
        _abLoopState.value = AbLoopState(startMs = start, endMs = pos)
        playerManager.setAbLoopBounds(start, pos)
        _uiState.update { it.copy(toastMessage = "A-B Loop active") }
    }

    fun clearAbLoop(silent: Boolean = false) {
        _abLoopState.value = AbLoopState()
        playerManager.setAbLoopBounds(null, null)
        if (!silent) {
            _uiState.update { it.copy(toastMessage = "A-B Loop cleared") }
        }
    }

    private fun buildDefaultChapters(durationMs: Long): List<ChapterMarker> {
        if (durationMs < 180_000L) return emptyList()
        val count = when {
            durationMs >= 5_400_000L -> 8 // >= 90m movie
            durationMs >= 2_400_000L -> 6 // >= 40m episode
            else -> 4
        }
        val step = durationMs / count
        return (0 until count).map { idx ->
            val label = when (idx) {
                0 -> "Start"
                count - 1 -> "Finale"
                else -> "Part ${idx + 1}"
            }
            ChapterMarker(title = label, startMs = idx * step)
        }
    }

    fun jumpToChapter(chapter: ChapterMarker) {
        playerManager.seekTo(chapter.startMs)
        _uiState.update { it.copy(toastMessage = "Chapter: ${chapter.title}") }
    }

    fun jumpNextChapter() {
        val list = _chapters.value
        if (list.isEmpty()) {
            playerManager.seekBy(seekIntervalSeconds.value * 1_000L)
            return
        }
        val pos = playerManager.player.currentPosition.coerceAtLeast(0L)
        val next = list.firstOrNull { it.startMs > pos + 2_000L } ?: list.last()
        jumpToChapter(next)
    }

    fun jumpPreviousChapter() {
        val list = _chapters.value
        if (list.isEmpty()) {
            playerManager.seekBy(-seekIntervalSeconds.value * 1_000L)
            return
        }
        val pos = playerManager.player.currentPosition.coerceAtLeast(0L)
        val prev = list.lastOrNull { it.startMs < pos - 3_000L } ?: list.first()
        jumpToChapter(prev)
    }

    // ==================== V1.24: GLOBAL SEARCH & FAVORITES / WATCHLIST ====================

    fun updateGlobalSearchQuery(query: String) {
        _globalSearchQuery.value = query
    }

    fun isFavorited(mediaKey: String): Boolean {
        return favorites.value.any { it.mediaKey == mediaKey }
    }

    fun toggleFavoriteActiveSession() {
        val session = _uiState.value.activeSession ?: return
        val mediaKey = session.seriesItemId ?: session.sourceUri
        val title = session.seriesTitle ?: session.title
        val type = if (session.seriesItemId != null) "SERIES" else "STREAM_URL"
        toggleFavorite(
            mediaKey = mediaKey,
            title = title,
            sourceUri = session.sourceUri,
            itemType = type,
            format = session.format,
            posterUrl = session.posterUrl,
            subtitleUrl = session.externalSubtitles.lastOrNull()?.uri
        )
    }

    fun toggleFavoriteM3uEntry(entry: M3uEntry) {
        toggleFavorite(
            mediaKey = entry.url,
            title = entry.title,
            sourceUri = entry.url,
            itemType = "M3U_CHANNEL",
            format = entry.format,
            posterUrl = entry.tvgLogo,
            groupTitle = entry.groupTitle
        )
    }

    fun toggleFavoriteJsonItem(item: JsonPlaylistItem) {
        val uri = if (item.isSeries) {
            item.seasons.firstOrNull()?.episodes?.firstOrNull()?.url.orEmpty()
        } else {
            item.url
        }
        toggleFavorite(
            mediaKey = item.id,
            title = item.title,
            sourceUri = uri,
            itemType = if (item.isSeries) "SERIES" else "MOVIE",
            format = item.format,
            posterUrl = item.imageUrl,
            imdbId = item.imdbId,
            imdbRating = item.imdbRating,
            releaseYear = item.releaseYear,
            groupTitle = item.groupTitle,
            subtitleUrl = item.subtitleUrl
        )
    }

    fun toggleFavorite(
        mediaKey: String,
        title: String,
        sourceUri: String,
        itemType: String,
        format: StreamFormat = StreamFormat.AUTO,
        posterUrl: String? = null,
        imdbId: String? = null,
        imdbRating: String? = null,
        releaseYear: String? = null,
        groupTitle: String? = null,
        subtitleUrl: String? = null
    ) {
        viewModelScope.launch {
            val added = favoriteRepo.toggleFavorite(
                FavoriteItemEntity(
                    mediaKey = mediaKey,
                    title = title,
                    sourceUri = sourceUri,
                    itemType = itemType,
                    formatName = format.name,
                    posterUrl = posterUrl,
                    imdbId = imdbId,
                    imdbRating = imdbRating,
                    releaseYear = releaseYear,
                    groupTitle = groupTitle,
                    subtitleUrl = subtitleUrl
                )
            )
            _uiState.update {
                it.copy(
                    toastMessage = if (added) "Added \"$title\" to My Watchlist" else "Removed \"$title\" from My Watchlist"
                )
            }
        }
    }

    fun playFavoriteItem(fav: FavoriteItemEntity) {
        val matchingJson = _jsonPlaylistState.value.allItems.firstOrNull {
            it.id == fav.mediaKey || (!it.isSeries && it.url == fav.sourceUri)
        }
        if (matchingJson != null) {
            playJsonPlaylistItem(matchingJson)
            return
        }
        val fmt = StreamFormat.entries.firstOrNull { it.name == fav.formatName } ?: StreamFormat.AUTO
        viewModelScope.launch {
            val subs = fav.subtitleUrl?.takeIf { it.isNotBlank() }?.let {
                listOf(subtitleHelper.buildFromRemoteUrl(it))
            }.orEmpty()
            startPlayback(
                title = fav.title,
                sourceUri = fav.sourceUri,
                format = fmt,
                isLocalFile = fav.sourceUri.startsWith("file://") || fav.sourceUri.startsWith("content://"),
                initialSubtitles = subs,
                posterUrl = fav.posterUrl
            )
        }
    }

    fun openGlobalSearchResult(result: GlobalSearchResult) {
        _globalSearchQuery.value = ""
        when {
            result.jsonItem != null -> playJsonPlaylistItem(result.jsonItem)
            result.m3uEntry != null -> playM3uEntry(result.m3uEntry)
            else -> {
                viewModelScope.launch {
                    val isLocal = result.sourceUri.startsWith("file://") || result.sourceUri.startsWith("content://")
                    startPlayback(
                        title = result.title,
                        sourceUri = result.sourceUri,
                        format = result.format,
                        isLocalFile = isLocal,
                        initialSubtitles = emptyList(),
                        posterUrl = result.posterUrl
                    )
                }
            }
        }
    }

    // ==================== V1.24: LIVE TV EPG & CHANNEL ZAPPING ====================

    fun loadEpgFromUrl(url: String, silent: Boolean = false) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            if (!silent) {
                _epgState.update { it.copy(errorMessage = "Enter a valid http:// or https:// XMLTV EPG URL") }
            }
            return
        }
        viewModelScope.launch {
            _epgState.update { it.copy(epgUrl = trimmed, isLoading = true, errorMessage = null) }
            prefsRepo.updateEpgUrl(trimmed)
            val result = xmltvEpgParser.fetchAndParse(trimmed)
            result.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to load XMLTV EPG"
                _epgState.update { it.copy(isLoading = false, errorMessage = msg) }
                if (!silent) {
                    _uiState.update { it.copy(toastMessage = "EPG load failed: $msg") }
                }
            }.onSuccess { programsMap ->
                val totalPrograms = programsMap.values.sumOf { it.size }
                _epgState.update {
                    it.copy(
                        isLoading = false,
                        programsByChannel = programsMap,
                        loadedProgramCount = totalPrograms,
                        errorMessage = null
                    )
                }
                if (!silent) {
                    _uiState.update { it.copy(toastMessage = "Loaded EPG guide ($totalPrograms programs)") }
                }
            }
        }
    }

    fun resolveEpgForChannel(entry: M3uEntry, nowMs: Long = System.currentTimeMillis()): ChannelEpgNowNext? {
        val epg = _epgState.value
        if (epg.programsByChannel.isEmpty()) return null
        return XmltvEpgParser.resolveNowNext(
            epgIndex = epg.programsByChannel,
            entry = entry,
            nowMs = nowMs
        )
    }

    fun zapM3uChannel(delta: Int) {
        val entries = _uiState.value.m3uEntries
        if (entries.size < 2) return
        val currentIdx = entries.indexOfFirst { it.id == _uiState.value.activeM3uEntryId }.coerceAtLeast(0)
        val nextIdx = (currentIdx + delta + entries.size) % entries.size
        val nextEntry = entries[nextIdx]
        playM3uEntry(nextEntry)
        val epgInfo = resolveEpgForChannel(nextEntry)
        _channelZapBanner.value = ChannelZapBannerState(
            channelNumber = nextIdx + 1,
            totalChannels = entries.size,
            channelTitle = nextEntry.title,
            groupTitle = nextEntry.groupTitle,
            epgNowNext = epgInfo
        )
        channelZapBannerJob?.cancel()
        channelZapBannerJob = viewModelScope.launch {
            delay(4_500L)
            _channelZapBanner.value = null
        }
    }

    // ==================== V1.24: ONLINE SUBTITLES & DUAL SUBTITLES ====================

    fun updateOnlineSubtitleQuery(query: String) {
        _onlineSubtitleState.update { it.copy(query = query) }
    }

    fun updateOnlineSubtitleLanguage(language: String) {
        _onlineSubtitleState.update { it.copy(language = language) }
    }

    fun searchOnlineSubtitles(overrideQuery: String? = null) {
        val sessionTitle = _uiState.value.activeSession?.let { it.seriesTitle ?: it.title }.orEmpty()
        val query = (overrideQuery ?: _onlineSubtitleState.value.query).ifBlank { sessionTitle }.trim()
        val lang = _onlineSubtitleState.value.language
        _onlineSubtitleState.update {
            it.copy(
                query = query,
                isConfigured = openSubtitlesClient.hasApiKey,
                isSearching = true,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            when (val result = openSubtitlesClient.searchSubtitles(query = query, languageCode = lang)) {
                is OnlineSubtitleSearchResult.Success -> _onlineSubtitleState.update {
                    it.copy(
                        isSearching = false,
                        results = result.candidates,
                        errorMessage = if (result.candidates.isEmpty()) "No subtitles found for \"$query\"" else null
                    )
                }
                is OnlineSubtitleSearchResult.MissingApiKey -> _onlineSubtitleState.update {
                    it.copy(isSearching = false, isConfigured = false, errorMessage = result.message)
                }
                is OnlineSubtitleSearchResult.Error -> _onlineSubtitleState.update {
                    it.copy(isSearching = false, errorMessage = result.message)
                }
            }
        }
    }

    fun loginOpenSubtitles(username: String, password: String) {
        viewModelScope.launch {
            _onlineSubtitleState.update { it.copy(isLoggingIn = true, errorMessage = null) }
            when (val res = openSubtitlesClient.login(username, password)) {
                is OpenSubtitlesLoginResult.Success -> {
                    prefsRepo.saveOpenSubtitlesCredentials(res.username, password)
                    _onlineSubtitleState.update {
                        it.copy(
                            isLoggingIn = false,
                            loggedInUsername = res.username,
                            remainingDownloads = res.allowedDownloads,
                            errorMessage = null
                        )
                    }
                    _uiState.update {
                        it.copy(toastMessage = "Signed in to OpenSubtitles as ${res.username} (${res.allowedDownloads}/day)")
                    }
                }
                is OpenSubtitlesLoginResult.Error -> {
                    _onlineSubtitleState.update {
                        it.copy(isLoggingIn = false, errorMessage = res.message)
                    }
                }
            }
        }
    }

    fun logoutOpenSubtitles() {
        openSubtitlesClient.logout()
        viewModelScope.launch {
            prefsRepo.clearOpenSubtitlesCredentials()
        }
        _onlineSubtitleState.update {
            it.copy(loggedInUsername = null, remainingDownloads = null)
        }
        _uiState.update { it.copy(toastMessage = "Signed out of OpenSubtitles") }
    }

    fun applyOnlineSubtitle(candidate: OnlineSubtitleCandidate, asSecondary: Boolean) {
        val session = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            _onlineSubtitleState.update { it.copy(isDownloadingId = candidate.fileId, errorMessage = null) }
            val downloadResult = openSubtitlesClient.downloadSubtitleToCache(candidate)
            _onlineSubtitleState.update { it.copy(isDownloadingId = null) }
            val downloaded = downloadResult.getOrElse { err ->
                val msg = err.localizedMessage ?: "Subtitle download failed"
                _onlineSubtitleState.update { it.copy(errorMessage = msg) }
                _uiState.update { it.copy(toastMessage = msg) }
                return@launch
            }
            downloaded.remainingDownloads?.let { rem ->
                _onlineSubtitleState.update { it.copy(remainingDownloads = rem) }
            }
            val fileUri = downloaded.uri
            val label = "${candidate.language.uppercase()} · ${candidate.fileName}"
            if (asSecondary) {
                val track = SecondarySubtitleEngine.loadTrack(
                    context = getApplication(),
                    okHttpClient = playerManager.okHttpClient,
                    uriString = fileUri.toString(),
                    label = label
                )
                if (track == null) {
                    _uiState.update { it.copy(toastMessage = "Could not parse downloaded dual subtitle") }
                } else {
                    _secondarySubtitleState.value = SecondarySubtitleUiState(
                        activeTrack = track,
                        currentText = SecondarySubtitleEngine.findActiveCueText(
                            track.cues,
                            playerManager.player.currentPosition
                        )
                    )
                    _uiState.update { it.copy(toastMessage = "Dual subtitle active (${track.cues.size} cues)") }
                }
            } else {
                val mime = subtitleHelper.detectMimeFromName(candidate.fileName)
                    ?: androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                val extSub = ExternalSubtitle(
                    id = "os_${candidate.fileId}",
                    uri = fileUri.toString(),
                    label = label,
                    mimeType = mime,
                    formatBadge = subtitleHelper.mimeToBadge(mime),
                    isLocalFile = true
                )
                attachSubtitleToActiveSession(session, extSub)
            }
        }
    }

    fun loadSecondarySubtitleFromUri(uri: Uri) {
        viewModelScope.launch {
            _secondarySubtitleState.update { it.copy(isLoading = true) }
            val name = subtitleHelper.resolveDisplayName(uri)
            val track = SecondarySubtitleEngine.loadTrack(
                context = getApplication(),
                okHttpClient = playerManager.okHttpClient,
                uriString = uri.toString(),
                label = name
            )
            if (track == null) {
                _secondarySubtitleState.update { it.copy(isLoading = false) }
                _uiState.update { it.copy(toastMessage = "Could not parse secondary subtitle (.srt / .vtt)") }
            } else {
                _secondarySubtitleState.value = SecondarySubtitleUiState(
                    activeTrack = track,
                    currentText = SecondarySubtitleEngine.findActiveCueText(
                        track.cues,
                        playerManager.player.currentPosition
                    ),
                    isLoading = false
                )
                _uiState.update { it.copy(toastMessage = "Dual subtitle loaded: ${track.label}") }
            }
        }
    }

    fun loadSecondarySubtitleFromUrl(url: String) {
        val trimmed = url.trim()
        if (!MediaFormatDetector.isValidMediaLink(trimmed)) {
            _uiState.update { it.copy(toastMessage = "Enter a valid http:// or https:// subtitle URL") }
            return
        }
        viewModelScope.launch {
            _secondarySubtitleState.update { it.copy(isLoading = true) }
            val label = MediaFormatDetector.inferTitle(trimmed)
            val track = SecondarySubtitleEngine.loadTrack(
                context = getApplication(),
                okHttpClient = playerManager.okHttpClient,
                uriString = trimmed,
                label = label
            )
            if (track == null) {
                _secondarySubtitleState.update { it.copy(isLoading = false) }
                _uiState.update { it.copy(toastMessage = "Failed to load dual subtitle URL") }
            } else {
                _secondarySubtitleState.value = SecondarySubtitleUiState(
                    activeTrack = track,
                    currentText = SecondarySubtitleEngine.findActiveCueText(
                        track.cues,
                        playerManager.player.currentPosition
                    ),
                    isLoading = false
                )
                _uiState.update { it.copy(toastMessage = "Dual subtitle loaded (${track.label})") }
            }
        }
    }

    fun clearSecondarySubtitle() {
        _secondarySubtitleState.value = SecondarySubtitleUiState()
    }

    private fun updateDualSubtitleCue(positionMs: Long) {
        val track = _secondarySubtitleState.value.activeTrack ?: return
        val text = SecondarySubtitleEngine.findActiveCueText(track.cues, positionMs)
        if (text != _secondarySubtitleState.value.currentText) {
            _secondarySubtitleState.update { it.copy(currentText = text) }
        }
    }

    // ==================== V1.24: OFFLINE DOWNLOADS (MOBILE) ====================

    fun startOfflineDownloadForActiveSession() {
        val session = _uiState.value.activeSession ?: return
        if (session.isLocalFile) {
            _uiState.update { it.copy(toastMessage = "Already playing from local storage") }
            return
        }
        offlineDownloadManager.enqueueDownload(
            title = session.title,
            sourceUrl = session.sourceUri,
            format = session.format,
            posterUrl = session.posterUrl,
            headers = session.headers
        )
        _uiState.update { it.copy(toastMessage = "Downloading \"${session.title}\" for offline playback") }
    }

    fun startOfflineDownload(
        title: String,
        url: String,
        format: StreamFormat = StreamFormat.AUTO,
        posterUrl: String? = null
    ) {
        offlineDownloadManager.enqueueDownload(title, url, format, posterUrl)
        _uiState.update { it.copy(toastMessage = "Queued offline download: $title") }
    }

    fun cancelOfflineDownload(downloadId: Long) {
        offlineDownloadManager.cancelDownload(downloadId)
        _uiState.update { it.copy(toastMessage = "Download cancelled") }
    }

    fun deleteOfflineDownload(downloadId: Long) {
        offlineDownloadManager.deleteDownload(downloadId)
        _uiState.update { it.copy(toastMessage = "Offline file deleted") }
    }

    fun playOfflineDownload(entity: OfflineDownloadEntity) {
        if (entity.status != OfflineDownloadManager.STATUS_COMPLETED || entity.localFilePath.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Download is not completed yet") }
            return
        }
        val localFile = File(entity.localFilePath)
        if (!localFile.exists()) {
            _uiState.update { it.copy(toastMessage = "Offline file missing — please re-download") }
            return
        }
        val fmt = if (localFile.extension.equals("m3u8", ignoreCase = true)) {
            StreamFormat.HLS
        } else {
            StreamFormat.entries.firstOrNull { it.name == entity.formatName } ?: StreamFormat.AUTO
        }
        viewModelScope.launch {
            startPlayback(
                title = entity.title,
                sourceUri = Uri.fromFile(localFile).toString(),
                format = fmt,
                isLocalFile = true,
                initialSubtitles = emptyList(),
                posterUrl = entity.posterUrl
            )
        }
    }

    // ==================== V1.24: LOCAL NETWORK (WEBDAV / JELLYFIN) ====================

    fun addNetworkServer(
        name: String,
        serverType: String,
        baseUrl: String,
        username: String,
        secret: String
    ) {
        val cleanUrl = LocalNetworkClient.normalizeBaseUrl(baseUrl)
        if (!cleanUrl.startsWith("http://", true) && !cleanUrl.startsWith("https://", true)) {
            _localNetworkState.update { it.copy(errorMessage = "Enter a valid server URL (e.g. http://192.168.1.10:8096)") }
            return
        }
        val displayName = name.trim().ifBlank { cleanUrl.substringAfter("://").substringBefore("/") }
        viewModelScope.launch {
            val entity = NetworkServerEntity(
                name = displayName,
                serverType = serverType.uppercase(),
                baseUrl = cleanUrl,
                usernameEnc = CredentialVault.encrypt(username.trim()),
                secretEnc = CredentialVault.encrypt(secret.trim())
            )
            val id = networkServerDao.upsert(entity)
            _uiState.update { it.copy(toastMessage = "Saved $displayName (${serverType.uppercase()})") }
            openNetworkServer(entity.copy(id = id))
        }
    }

    fun deleteNetworkServer(serverId: Long) {
        viewModelScope.launch {
            networkServerDao.deleteById(serverId)
            if (_localNetworkState.value.activeServer?.id == serverId) {
                _localNetworkState.value = LocalNetworkUiState()
            }
            _uiState.update { it.copy(toastMessage = "Removed network server") }
        }
    }

    fun openNetworkServer(server: NetworkServerEntity) {
        viewModelScope.launch {
            _localNetworkState.value = LocalNetworkUiState(
                activeServer = server,
                breadcrumbPath = listOf(server.name to ""),
                isLoading = true
            )
            val res = localNetworkClient.browse(server, folderPathOrId = null)
            _localNetworkState.update {
                it.copy(
                    items = res.items,
                    isLoading = false,
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    fun browseNetworkFolder(item: NetworkBrowserItem) {
        val server = _localNetworkState.value.activeServer ?: return
        if (!item.isFolder) {
            playNetworkMediaItem(item)
            return
        }
        viewModelScope.launch {
            val nextBreadcrumbs = _localNetworkState.value.breadcrumbPath + (item.name to item.pathOrParentId)
            _localNetworkState.update {
                it.copy(breadcrumbPath = nextBreadcrumbs, isLoading = true, errorMessage = null)
            }
            val res = localNetworkClient.browse(server, folderPathOrId = item.pathOrParentId)
            _localNetworkState.update {
                it.copy(items = res.items, isLoading = false, errorMessage = res.errorMessage)
            }
        }
    }

    fun navigateNetworkFolderUp() {
        val state = _localNetworkState.value
        val server = state.activeServer ?: return
        if (state.breadcrumbPath.size <= 1) {
            _localNetworkState.value = LocalNetworkUiState()
            return
        }
        val parentBreadcrumbs = state.breadcrumbPath.dropLast(1)
        val targetPath = parentBreadcrumbs.last().second.ifBlank { null }
        viewModelScope.launch {
            _localNetworkState.update {
                it.copy(breadcrumbPath = parentBreadcrumbs, isLoading = true, errorMessage = null)
            }
            val res = localNetworkClient.browse(server, folderPathOrId = targetPath)
            _localNetworkState.update {
                it.copy(items = res.items, isLoading = false, errorMessage = res.errorMessage)
            }
        }
    }

    fun playNetworkMediaItem(item: NetworkBrowserItem) {
        if (item.playbackUrl.isBlank()) return
        viewModelScope.launch {
            startPlayback(
                title = item.name,
                sourceUri = item.playbackUrl,
                format = item.format,
                isLocalFile = false,
                initialSubtitles = emptyList(),
                posterUrl = item.posterUrl,
                headers = item.requestHeaders
            )
        }
    }

    // ==================== V1.24: PHONE-TO-TV WEB REMOTE & LOCAL WI-FI / SAF SYNC ====================

    fun startWebRemoteServer() {
        val status = webRemoteServer.start(getApplication())
        _webRemoteState.value = WebRemoteUiState(
            isRunning = status.isRunning,
            serverUrl = status.qrUrl,
            pin = status.pin,
            lastReceivedAction = _webRemoteState.value.lastReceivedAction,
            errorMessage = status.errorMessage
        )
    }

    fun stopWebRemoteServer() {
        webRemoteServer.stop()
        _webRemoteState.update { it.copy(isRunning = false) }
    }

    private fun handleRemotePushCommand(cmd: RemotePushCommand): String {
        val payload = cmd.payload.trim()
        viewModelScope.launch {
            when (cmd.action) {
                RemotePushAction.PLAY_STREAM -> {
                    _webRemoteState.update { it.copy(lastReceivedAction = "Stream URL pushed") }
                    onUrlInputChanged(payload)
                    launchFromUrlInput()
                }
                RemotePushAction.LOAD_JSON -> {
                    _webRemoteState.update { it.copy(lastReceivedAction = "JSON Catalog URL pushed") }
                    loadJsonPlaylistFromUrl(payload, openSheetOnSuccess = true, silentOnError = false)
                }
                RemotePushAction.LOAD_M3U -> {
                    _webRemoteState.update { it.copy(lastReceivedAction = "M3U Playlist URL pushed") }
                    onUrlInputChanged(payload)
                    onFormatSelected(StreamFormat.M3U)
                    launchFromUrlInput()
                }
                RemotePushAction.LOAD_SUBTITLE -> {
                    _webRemoteState.update { it.copy(lastReceivedAction = "Subtitle URL pushed") }
                    if (_uiState.value.activeSession != null) {
                        loadExternalSubtitleUrlIntoPlayer(payload)
                    } else {
                        stageRemoteSubtitle(payload)
                    }
                }
                RemotePushAction.LOAD_EPG -> {
                    _webRemoteState.update { it.copy(lastReceivedAction = "EPG Guide URL pushed") }
                    loadEpgFromUrl(payload)
                }
                RemotePushAction.SET_NORDVPN_TOKEN -> {
                    _webRemoteState.update { it.copy(lastReceivedAction = "NordVPN Access Key applied") }
                    loginNordVpnWithToken(payload)
                }
                RemotePushAction.IMPORT_SYNC_JSON -> {
                    _webRemoteState.update { it.copy(lastReceivedAction = "Wi-Fi Sync snapshot imported") }
                    importDeviceSyncSnapshotJson(payload)
                }
            }
        }
        return "Sent to CineStream: ${cmd.action.label}"
    }

    private fun exportDeviceSyncSnapshotJsonBlocking(): String {
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            exportDeviceSyncSnapshotJson()
        }
    }

    suspend fun exportDeviceSyncSnapshotJson(): String {
        val allHistory = historyRepo.getAllHistory()
        val allFavorites = favoriteRepo.getAll()
        val playlistUrls = prefsRepo.loadJsonPlaylistUrls()
        val snapshot = DeviceSyncSnapshot(
            jsonPlaylistUrls = playlistUrls,
            epgUrl = _epgState.value.epgUrl,
            preferredAudioLanguage = preferredAudioLanguage.value,
            preferredSubtitleLanguage = preferredSubtitleLanguage.value,
            seekIntervalSeconds = seekIntervalSeconds.value,
            nightModeAudioEnabled = nightModeAudioEnabled.value,
            autoPlayUpNextEnabled = autoPlayUpNextEnabled.value,
            favorites = allFavorites,
            history = allHistory
        )
        return DeviceSyncCodec.encode(snapshot)
    }

    fun importDeviceSyncSnapshotJson(rawJson: String) {
        viewModelScope.launch {
            val snapshot = DeviceSyncCodec.decode(rawJson)
            if (snapshot == null) {
                _uiState.update { it.copy(toastMessage = "Invalid CineStream Sync JSON file") }
                return@launch
            }
            val histCount = historyRepo.mergeSyncedHistory(snapshot.history)
            val favCount = favoriteRepo.mergeSyncedFavorites(snapshot.favorites)
            snapshot.jsonPlaylistUrls.forEach { url ->
                loadJsonPlaylistFromUrl(url, openSheetOnSuccess = false, silentOnError = true)
            }
            if (snapshot.preferredAudioLanguage.isNotBlank()) {
                prefsRepo.updatePreferredAudioLanguage(snapshot.preferredAudioLanguage)
            }
            if (snapshot.preferredSubtitleLanguage.isNotBlank()) {
                prefsRepo.updatePreferredSubtitleLanguage(snapshot.preferredSubtitleLanguage)
            }
            prefsRepo.updateSeekIntervalSeconds(snapshot.seekIntervalSeconds)
            prefsRepo.updateNightModeAudioEnabled(snapshot.nightModeAudioEnabled)
            prefsRepo.updateAutoPlayUpNextEnabled(snapshot.autoPlayUpNextEnabled)
            playerManager.setNightModeAudio(snapshot.nightModeAudioEnabled)
            if (snapshot.epgUrl.isNotBlank()) {
                loadEpgFromUrl(snapshot.epgUrl, silent = true)
            }
            _uiState.update {
                it.copy(
                    toastMessage = "Synced $histCount watch records & $favCount watchlist items"
                )
            }
        }
    }

    override fun onCleared() {
        progressPollingJob?.cancel()
        jsonEnrichJob?.cancel()
        volumePersistJob?.cancel()
        sleepTimerJob?.cancel()
        upNextCountdownJob?.cancel()
        channelZapBannerJob?.cancel()
        webRemoteServer.stop()
        MediaPlaybackService.onNotificationTogglePlayPause = null
        MediaPlaybackService.onNotificationStop = null
        MediaPlaybackService.stop(getApplication())
        nordLynxController.disconnectBlocking()
        playerManager.release()
        super.onCleared()
    }
}
