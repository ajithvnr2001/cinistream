package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
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
import com.example.player.CinePlayerManager
import com.example.player.DynamicSocksProxyManager
import com.example.player.ImdbMetadataClient
import com.example.player.JsonPlaylistLoadResult
import com.example.player.JsonPlaylistParser
import com.example.player.M3uInspectionResult
import com.example.player.M3uPlaylistParser
import com.example.player.MediaEdgeCache
import com.example.player.MediaFormatDetector
import com.example.player.NetworkTransportMode
import com.example.player.NordVpnApiClient
import com.example.player.NordVpnCatalog
import com.example.player.NordVpnLoginResult
import com.example.player.NordVpnServer
import com.example.player.PlaybackProgressState
import com.example.player.PlayerNetworkStats
import com.example.player.PlayerPlaybackState
import com.example.player.PrivateDnsMode
import com.example.player.StreamingNetworkEngine
import com.example.player.SubtitleHelper
import com.example.player.SystemVpnMonitor
import com.example.player.VideoDecoderMode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val episodeId: String? = null,
    val episodeCodeLabel: String? = null
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
    private val prefsRepo = SubtitlePrefsRepository(application)

    val playerManager = CinePlayerManager(application)
    private val subtitleHelper = SubtitleHelper(application, playerManager.okHttpClient)
    private val m3uParser = M3uPlaylistParser(application, playerManager.okHttpClient)
    private val jsonParser = JsonPlaylistParser(application, playerManager.okHttpClient)
    private val imdbClient = ImdbMetadataClient(playerManager.okHttpClient)
    private val nordVpnClient = NordVpnApiClient()
    private var activeNordUsername: String = ""
    private var activeNordPassword: String = ""

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

    private val _navEvents = MutableSharedFlow<CineNavigationEvent>(extraBufferCapacity = 4)
    val navEvents: SharedFlow<CineNavigationEvent> = _navEvents.asSharedFlow()

    private var progressPollingJob: Job? = null
    private var jsonEnrichJob: Job? = null
    private var volumePersistJob: Job? = null
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
            playNextEpisodeIfAvailable(autoTriggered = true)
        }
        viewModelScope.launch {
            val savedVolume = prefsRepo.volumeBoostPercent.first()
            playerManager.setVolumePercent(savedVolume)
        }
        viewModelScope.launch {
            val savedDecoderMode = prefsRepo.videoDecoderMode.first()
            playerManager.setPreferredDecoderMode(savedDecoderMode, reloadActiveStream = false)
        }
        viewModelScope.launch {
            val savedTransportMode = prefsRepo.networkTransportMode.first()
            playerManager.setPreferredTransportMode(savedTransportMode, reloadActiveStream = false)
        }
        viewModelScope.launch {
            val savedPrivacy = prefsRepo.networkPrivacyPrefs.first()
            activeNordUsername = savedPrivacy.nordVpnUsername
            activeNordPassword = savedPrivacy.nordVpnPassword
            val nordConfig = DynamicSocksProxyManager.ActiveConfig(
                enabled = savedPrivacy.nordVpnEnabled && savedPrivacy.hasNordCredentials,
                host = savedPrivacy.nordVpnServerHost,
                username = savedPrivacy.nordVpnUsername,
                password = savedPrivacy.nordVpnPassword
            )
            playerManager.applyNetworkPrivacyConfig(
                dnsMode = savedPrivacy.privateDnsMode,
                nordConfig = nordConfig,
                reloadActiveStream = false
            )
            val appCtx = getApplication<Application>()
            val sysVpn = SystemVpnMonitor.isSystemVpnActive(appCtx)
            val warpInstalled = SystemVpnMonitor.isPackageInstalled(appCtx, SystemVpnMonitor.PACKAGE_CLOUDFLARE_WARP)
            val nordInstalled = SystemVpnMonitor.isPackageInstalled(appCtx, SystemVpnMonitor.PACKAGE_NORDVPN)
            _networkPrivacyState.update {
                it.copy(
                    privateDnsMode = savedPrivacy.privateDnsMode,
                    nordVpnLoggedIn = savedPrivacy.hasNordCredentials,
                    nordVpnEnabled = nordConfig.isActive,
                    selectedNordServerHost = savedPrivacy.nordVpnServerHost,
                    selectedNordServerLabel = savedPrivacy.nordVpnServerLabel,
                    systemVpnActive = sysVpn,
                    warpAppInstalled = warpInstalled,
                    nordAppInstalled = nordInstalled
                )
            }
            if (savedPrivacy.hasNordCredentials) {
                val liveServers = nordVpnClient.fetchRecommendedSocksServers()
                if (liveServers.isNotEmpty()) {
                    _networkPrivacyState.update { it.copy(availableNordServers = liveServers) }
                }
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
                initialSubtitles = subs
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
                headers = item.headers
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
                episodeId = episode.id,
                episodeCodeLabel = episode.codeLabel
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
        val warpInstalled = SystemVpnMonitor.isPackageInstalled(appCtx, SystemVpnMonitor.PACKAGE_CLOUDFLARE_WARP)
        val nordInstalled = SystemVpnMonitor.isPackageInstalled(appCtx, SystemVpnMonitor.PACKAGE_NORDVPN)
        _networkPrivacyState.update {
            it.copy(
                systemVpnActive = sysVpn,
                warpAppInstalled = warpInstalled,
                nordAppInstalled = nordInstalled
            )
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    fun setPrivateDnsMode(mode: PrivateDnsMode) {
        val current = _networkPrivacyState.value
        val nordConfig = buildCurrentNordConfig(current)
        playerManager.applyNetworkPrivacyConfig(
            dnsMode = mode,
            nordConfig = nordConfig,
            reloadActiveStream = true
        )
        _networkPrivacyState.update {
            it.copy(privateDnsMode = mode, probeResult = null)
        }
        viewModelScope.launch {
            prefsRepo.updatePrivateDnsMode(mode)
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

                    val servers = result.servers.ifEmpty { NordVpnCatalog.DEFAULT_SERVERS }
                    val currentState = _networkPrivacyState.value
                    val chosen = NordVpnCatalog.findByHostname(currentState.selectedNordServerHost, servers)
                    // Auto-enable Cloudflare 1.1.1.1 DoH when connecting with NordVPN key if still on System DNS
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
                        serverHost = chosen.hostname,
                        serverLabel = chosen.label,
                        enableImmediately = true
                    )

                    val activeNordConfig = DynamicSocksProxyManager.ActiveConfig(
                        enabled = true,
                        host = chosen.hostname,
                        port = chosen.port,
                        username = activeNordUsername,
                        password = activeNordPassword
                    )
                    playerManager.applyNetworkPrivacyConfig(
                        dnsMode = effectiveDnsMode,
                        nordConfig = activeNordConfig,
                        reloadActiveStream = true
                    )

                    _networkPrivacyState.update {
                        it.copy(
                            privateDnsMode = effectiveDnsMode,
                            nordVpnLoggedIn = true,
                            nordVpnEnabled = true,
                            selectedNordServerHost = chosen.hostname,
                            selectedNordServerLabel = chosen.label,
                            availableNordServers = servers,
                            isLoggingInNord = false,
                            loginError = null,
                            probeResult = null
                        )
                    }
                    _uiState.update {
                        it.copy(
                            toastMessage = if (wasAlreadyLoggedIn) {
                                "NordVPN key updated · 1.1.1.1 + ${chosen.label}"
                            } else {
                                "Connected: ${effectiveDnsMode.shortBadge.ifBlank { "DNS" }} + NordVPN (${chosen.label})"
                            }
                        )
                    }
                    if (statsForNerdsEnabled.value) {
                        playerManager.syncNetworkStats()
                    }
                    probeNetworkPrivacyRoute()
                }
                is NordVpnLoginResult.Error -> {
                    _networkPrivacyState.update {
                        it.copy(isLoggingInNord = false, loginError = result.message)
                    }
                }
            }
        }
    }

    fun setNordVpnEnabled(enabled: Boolean) {
        val current = _networkPrivacyState.value
        val hasCreds = activeNordUsername.isNotBlank() && activeNordPassword.isNotBlank()
        if (enabled && !hasCreds) {
            _networkPrivacyState.update {
                it.copy(loginError = "Log in with your NordVPN Access Token first.")
            }
            return
        }
        val updatedState = current.copy(nordVpnEnabled = enabled, probeResult = null)
        _networkPrivacyState.value = updatedState
        playerManager.applyNetworkPrivacyConfig(
            dnsMode = updatedState.privateDnsMode,
            nordConfig = buildCurrentNordConfig(updatedState),
            reloadActiveStream = true
        )
        viewModelScope.launch {
            prefsRepo.updateNordVpnEnabled(enabled)
        }
        _uiState.update {
            it.copy(
                toastMessage = if (enabled) {
                    "NordVPN routing ON (${updatedState.selectedNordServerLabel})"
                } else {
                    "NordVPN routing paused"
                }
            )
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    fun selectNordVpnServer(server: NordVpnServer) {
        val current = _networkPrivacyState.value
        val updatedState = current.copy(
            selectedNordServerHost = server.hostname,
            selectedNordServerLabel = server.label,
            probeResult = null
        )
        _networkPrivacyState.value = updatedState
        playerManager.applyNetworkPrivacyConfig(
            dnsMode = updatedState.privateDnsMode,
            nordConfig = buildCurrentNordConfig(updatedState),
            reloadActiveStream = true
        )
        viewModelScope.launch {
            prefsRepo.updateNordVpnServer(server.hostname, server.label)
        }
        _uiState.update {
            it.copy(toastMessage = "NordVPN location switched to ${server.label}")
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
        if (updatedState.nordVpnLoggedIn && updatedState.nordVpnEnabled) {
            probeNetworkPrivacyRoute()
        }
    }

    fun logoutNordVpn() {
        activeNordUsername = ""
        activeNordPassword = ""
        val current = _networkPrivacyState.value
        val updatedState = current.copy(
            nordVpnLoggedIn = false,
            nordVpnEnabled = false,
            loginError = null,
            probeResult = null
        )
        _networkPrivacyState.value = updatedState
        playerManager.applyNetworkPrivacyConfig(
            dnsMode = updatedState.privateDnsMode,
            nordConfig = DynamicSocksProxyManager.ActiveConfig(),
            reloadActiveStream = true
        )
        viewModelScope.launch {
            prefsRepo.clearNordVpnCredentials()
        }
        _uiState.update {
            it.copy(toastMessage = "Signed out of NordVPN")
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    /**
     * Disables both Cloudflare 1.1.1.1 / Google 8.8.8.8 Private DNS (back to System DNS) and
     * NordVPN SOCKS5 routing in a single tap, while keeping the saved NordVPN key & location
     * intact so the user can re-enable anytime without re-entering their key.
     */
    fun disableAllPrivacyRouting() {
        val current = _networkPrivacyState.value
        val updatedState = current.copy(
            privateDnsMode = PrivateDnsMode.SYSTEM,
            nordVpnEnabled = false,
            probeResult = null
        )
        _networkPrivacyState.value = updatedState
        playerManager.applyNetworkPrivacyConfig(
            dnsMode = PrivateDnsMode.SYSTEM,
            nordConfig = DynamicSocksProxyManager.ActiveConfig(),
            reloadActiveStream = true
        )
        viewModelScope.launch {
            prefsRepo.updatePrivateDnsMode(PrivateDnsMode.SYSTEM)
            prefsRepo.updateNordVpnEnabled(false)
        }
        _uiState.update {
            it.copy(toastMessage = "1.1.1.1 & NordVPN disabled (Direct ISP)")
        }
        if (statsForNerdsEnabled.value) {
            playerManager.syncNetworkStats()
        }
    }

    fun probeNetworkPrivacyRoute() {
        viewModelScope.launch {
            refreshSystemVpnStatus()
            val state = _networkPrivacyState.value
            _networkPrivacyState.update { it.copy(isProbingRoute = true) }
            val result = nordVpnClient.probeActiveRoute(
                activeStreamingClient = playerManager.okHttpClient,
                dnsMode = state.privateDnsMode,
                nordEnabled = state.nordVpnLoggedIn && state.nordVpnEnabled,
                nordServerHost = state.selectedNordServerHost,
                systemVpnActive = state.systemVpnActive
            )
            _networkPrivacyState.update {
                it.copy(isProbingRoute = false, probeResult = result)
            }
        }
    }

    fun launchCompanionVpnApp(packageName: String) {
        val launched = SystemVpnMonitor.launchOrOpenStore(getApplication(), packageName)
        if (!launched) {
            _uiState.update {
                it.copy(toastMessage = "Unable to open companion app or Play Store on this device")
            }
        }
    }

    private fun buildCurrentNordConfig(state: NetworkPrivacyUiState): DynamicSocksProxyManager.ActiveConfig {
        return DynamicSocksProxyManager.ActiveConfig(
            enabled = state.nordVpnLoggedIn && state.nordVpnEnabled,
            host = state.selectedNordServerHost,
            port = NordVpnServer.DEFAULT_SOCKS5_PORT,
            username = activeNordUsername,
            password = activeNordPassword
        )
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
            startPlayback(
                title = item.title,
                sourceUri = item.sourceUri,
                format = format,
                isLocalFile = item.isLocalFile,
                initialSubtitles = subs,
                resumePositionMs = resumeMs
            )
        }
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
        episodeId: String? = null,
        episodeCodeLabel: String? = null
    ) {
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

        val session = ActiveMediaSession(
            title = title,
            sourceUri = sourceUri,
            format = format,
            isLocalFile = isLocalFile,
            externalSubtitles = mergedSubs,
            subtitleOffsetMs = 0L,
            mirrors = mirrors,
            headers = headers,
            seriesItemId = seriesItemId,
            seriesTitle = seriesTitle,
            episodeId = episodeId,
            episodeCodeLabel = episodeCodeLabel
        )

        _uiState.update { it.copy(activeSession = session) }

        val resolvedPairs = mergedSubs.map { sub ->
            sub to subtitleHelper.resolvePlayableSubtitleUri(sub, 0L)
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
            subtitle = mergedSubs.lastOrNull()
        )

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
        val newOffset = (currentSession.subtitleOffsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
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
                episodeId = session.episodeId,
                episodeCodeLabel = session.episodeCodeLabel
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
                    tick++
                    if (tick % 2 == 0 && statsForNerdsEnabled.value) {
                        playerManager.syncNetworkStats()
                    }
                    if (tick % 10 == 0 && playbackState.value.isPlaying) {
                        val progress = playbackProgressState.value
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
                        if (statsForNerdsEnabled.value) {
                            playerManager.syncNetworkStats()
                        }
                    }
                    delay(1_000L)
                }
            }
        }
    }

    override fun onCleared() {
        progressPollingJob?.cancel()
        jsonEnrichJob?.cancel()
        volumePersistJob?.cancel()
        playerManager.release()
        super.onCleared()
    }
}
