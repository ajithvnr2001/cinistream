package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiModelTier
import com.example.ai.AiSubtitleCue
import com.example.ai.AiSubtitleGenerationResult
import com.example.ai.AiSubtitleGenerator
import com.example.ai.GeminiRateLimiter
import com.example.ai.RateLimitSnapshot
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
import com.example.model.StreamFormat
import com.example.model.SubtitleAppearance
import com.example.model.SubtitleBgPreset
import com.example.model.SubtitleColorPreset
import com.example.model.SubtitleTextSize
import com.example.model.TrackOption
import com.example.player.CinePlayerManager
import com.example.player.ImdbMetadataClient
import com.example.player.JsonPlaylistLoadResult
import com.example.player.JsonPlaylistParser
import com.example.player.M3uInspectionResult
import com.example.player.M3uPlaylistParser
import com.example.player.MediaFormatDetector
import com.example.player.PlaybackProgressState
import com.example.player.PlayerPlaybackState
import com.example.player.SubtitleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    val subtitleOffsetMs: Long = 0L
)

data class AiSubtitleUiState(
    val isEnabled: Boolean = false,
    val isAutoMode: Boolean = false,
    val isGenerating: Boolean = false,
    val preferredTier: AiModelTier = AiModelTier.FLASH_3_8,
    val lastModelUsed: AiModelTier? = null,
    val detectedLanguage: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val cues: List<AiSubtitleCue> = emptyList(),
    val activeOverlayText: String? = null
)

data class JsonPlaylistUiState(
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
    val items: List<JsonPlaylistItem> = emptyList(),
    val activeItemId: String? = null,
    val isLoading: Boolean = false,
    val isEnrichingImdb: Boolean = false,
    val errorMessage: String? = null
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

    private val rateLimiter = GeminiRateLimiter(
        onDailyUsageChanged = { dayKey, flashRpd, liteRpd ->
            viewModelScope.launch {
                prefsRepo.saveAiDailyUsage(dayKey, flashRpd, liteRpd)
            }
        }
    )

    private val aiSubtitleGenerator = AiSubtitleGenerator(application, rateLimiter)

    private val _uiState = MutableStateFlow(CineStreamUiState())
    val uiState: StateFlow<CineStreamUiState> = _uiState.asStateFlow()

    private val _jsonPlaylistState = MutableStateFlow(JsonPlaylistUiState())
    val jsonPlaylistState: StateFlow<JsonPlaylistUiState> = _jsonPlaylistState.asStateFlow()

    private val _sampleStreams = MutableStateFlow(SampleStreamsCatalog.items)
    val sampleStreams: StateFlow<List<SampleStream>> = _sampleStreams.asStateFlow()

    private val _aiSubtitleState = MutableStateFlow(AiSubtitleUiState())
    val aiSubtitleState: StateFlow<AiSubtitleUiState> = _aiSubtitleState.asStateFlow()

    val rateLimitSnapshot: StateFlow<RateLimitSnapshot> = rateLimiter.snapshot

    val playbackState: StateFlow<PlayerPlaybackState> = playerManager.state
    val playbackProgressState: StateFlow<PlaybackProgressState> = playerManager.progressState

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

    private val _navEvents = MutableSharedFlow<CineNavigationEvent>(extraBufferCapacity = 4)
    val navEvents: SharedFlow<CineNavigationEvent> = _navEvents.asSharedFlow()

    private var progressPollingJob: Job? = null
    private var aiSubtitleJob: Job? = null
    private var jsonEnrichJob: Job? = null
    private var volumePersistJob: Job? = null
    private var lastSavedProgressUri: String? = null
    private var lastSavedProgressMs: Long = -1L
    private var lastAutoOverlayText: String? = null
    private var lastAutoOverlayExpiresAtMs: Long = 0L

    init {
        viewModelScope.launch {
            val saved = prefsRepo.loadAiDailyUsage()
            rateLimiter.restorePersistedDailyCounts(saved.dayKey, saved.flashRpd, saved.liteRpd)
        }
        viewModelScope.launch {
            val savedVolume = prefsRepo.volumeBoostPercent.first()
            playerManager.setVolumePercent(savedVolume)
        }
        viewModelScope.launch {
            enrichSamplesWithImdb()
        }
        viewModelScope.launch {
            val savedJsonUrl = prefsRepo.loadJsonPlaylistUrl()
            if (!savedJsonUrl.isNullOrBlank()) {
                loadJsonPlaylistFromUrl(savedJsonUrl, openSheetOnSuccess = false, silentOnError = true)
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
        silentOnError: Boolean = false
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
                it.copy(isLoading = true, errorMessage = null, sourceUrl = trimmed)
            }
            when (val result = jsonParser.loadFromUrl(trimmed)) {
                is JsonPlaylistLoadResult.Success -> {
                    prefsRepo.saveJsonPlaylistUrl(trimmed)
                    val label = result.sourceTitle ?: MediaFormatDetector.inferTitle(trimmed)
                    _jsonPlaylistState.update {
                        it.copy(
                            sourceLabel = label,
                            sourceUrl = trimmed,
                            items = result.items,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                    if (!silentOnError) {
                        _uiState.update {
                            it.copy(toastMessage = "Loaded ${result.items.size} items from JSON playlist")
                        }
                    }
                    if (openSheetOnSuccess) {
                        _navEvents.tryEmit(CineNavigationEvent.OpenJsonSheet)
                    }
                    startJsonImdbEnrichment(result.items)
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

    fun loadJsonPlaylistFromUri(uri: Uri) {
        viewModelScope.launch {
            subtitleHelper.takePersistableReadPermission(uri)
            val displayName = subtitleHelper.resolveDisplayName(uri)
            _jsonPlaylistState.update { it.copy(isLoading = true, errorMessage = null, sourceUrl = null) }
            when (val result = jsonParser.loadFromLocalUri(uri)) {
                is JsonPlaylistLoadResult.Success -> {
                    val label = result.sourceTitle ?: displayName
                    _jsonPlaylistState.update {
                        it.copy(
                            sourceLabel = label,
                            sourceUrl = uri.toString(),
                            items = result.items,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                    _uiState.update {
                        it.copy(toastMessage = "Loaded ${result.items.size} items from $label")
                    }
                    _navEvents.tryEmit(CineNavigationEvent.OpenJsonSheet)
                    startJsonImdbEnrichment(result.items)
                }
                is JsonPlaylistLoadResult.Error -> {
                    _jsonPlaylistState.update { it.copy(isLoading = false, errorMessage = result.message) }
                    _uiState.update { it.copy(toastMessage = result.message) }
                }
            }
        }
    }

    fun refreshJsonPlaylist() {
        val currentUrl = _jsonPlaylistState.value.sourceUrl ?: return
        if (currentUrl.startsWith("http://", ignoreCase = true) ||
            currentUrl.startsWith("https://", ignoreCase = true)
        ) {
            loadJsonPlaylistFromUrl(currentUrl, openSheetOnSuccess = false, silentOnError = false)
        }
    }

    fun clearJsonPlaylist() {
        jsonEnrichJob?.cancel()
        viewModelScope.launch { prefsRepo.saveJsonPlaylistUrl(null) }
        _jsonPlaylistState.value = JsonPlaylistUiState()
    }

    fun playJsonPlaylistItem(item: JsonPlaylistItem) {
        viewModelScope.launch {
            _jsonPlaylistState.update { it.copy(activeItemId = item.id) }
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
                initialSubtitles = subs
            )
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
                        state.copy(
                            items = state.items.map { existing -> updatedMap[existing.id] ?: existing }
                        )
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
        resumePositionMs: Long? = null
    ) {
        // Reset AI subtitle session when switching to a new video
        stopAutoAiSubtitles()
        _aiSubtitleState.update {
            it.copy(
                isEnabled = false,
                isGenerating = false,
                statusMessage = null,
                errorMessage = null,
                detectedLanguage = null,
                cues = emptyList(),
                activeOverlayText = null
            )
        }
        lastAutoOverlayText = null
        lastAutoOverlayExpiresAtMs = 0L

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
            subtitleOffsetMs = 0L
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
            autoSelectSubtitleId = mergedSubs.lastOrNull()?.id
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

    // ==================== AI AUDIO-TO-ENGLISH SUBTITLES ====================

    fun selectPreferredAiTier(tier: AiModelTier) {
        _aiSubtitleState.update { it.copy(preferredTier = tier) }
    }

    /**
     * Generates timed English subtitles from the current playing video audio (up to 18s window).
     * Strictly enforces 3 RPM / 10K TPM / 10 RPD per model with automatic Flash -> Flash-Lite failover.
     */
    fun generateAiEnglishSubtitleClipNow() {
        if (_aiSubtitleState.value.isGenerating) return
        aiSubtitleJob?.cancel()
        aiSubtitleJob = viewModelScope.launch {
            _aiSubtitleState.update {
                it.copy(
                    isEnabled = true,
                    isAutoMode = false,
                    isGenerating = true,
                    errorMessage = null,
                    statusMessage = "Capturing video audio…"
                )
            }
            try {
                // If fewer than 4s are buffered yet, wait briefly while playing to capture speech
                var waitTicks = 0
                while (playerManager.audioCaptureProcessor.bufferedDurationSec() < 5f && waitTicks < 16) {
                    if (!playbackState.value.isPlaying) {
                        _aiSubtitleState.update {
                            it.copy(
                                isGenerating = false,
                                statusMessage = "Play the video for a few seconds so audio can be captured."
                            )
                        }
                        return@launch
                    }
                    delay(500L)
                    waitTicks++
                }
                transcribeLatestAudioChunk(requestedSeconds = 18)
            } catch (e: CancellationException) {
                throw e
            } finally {
                _aiSubtitleState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /**
     * Toggles continuous rate-limit-aware English subtitle generation while the video plays.
     * Captures 18-second audio windows paced safely to respect 3 RPM / 10K TPM / 10 RPD per tier.
     */
    fun toggleAutoAiSubtitles() {
        if (_aiSubtitleState.value.isAutoMode) {
            stopAutoAiSubtitles()
            return
        }

        aiSubtitleJob?.cancel()
        aiSubtitleJob = viewModelScope.launch {
            _aiSubtitleState.update {
                it.copy(
                    isEnabled = true,
                    isAutoMode = true,
                    errorMessage = null,
                    statusMessage = "AI Live English Subtitles listening (rate-limit protected)…"
                )
            }
            try {
                while (isActive && _aiSubtitleState.value.isAutoMode) {
                    // Wait until playing and at least 12s of fresh audio is buffered
                    while (isActive && _aiSubtitleState.value.isAutoMode &&
                        (!playbackState.value.isPlaying || playerManager.audioCaptureProcessor.bufferedDurationSec() < 12f)
                    ) {
                        val buffered = playerManager.audioCaptureProcessor.bufferedDurationSec().toInt()
                        _aiSubtitleState.update {
                            it.copy(
                                statusMessage = if (playbackState.value.isPlaying) {
                                    "Listening to audio (${buffered}s / 12s)…"
                                } else {
                                    "Paused — resume video to continue AI captions"
                                }
                            )
                        }
                        delay(1000L)
                    }

                    if (!isActive || !_aiSubtitleState.value.isAutoMode) break

                    val result = transcribeLatestAudioChunk(requestedSeconds = 18)
                    when (result) {
                        is AiSubtitleGenerationResult.DailyLimitReached -> {
                            _aiSubtitleState.update {
                                it.copy(
                                    isAutoMode = false,
                                    isGenerating = false,
                                    errorMessage = result.message,
                                    statusMessage = "Auto AI paused: daily 10+10 RPD quota reached."
                                )
                            }
                            break
                        }
                        is AiSubtitleGenerationResult.Cooldown -> {
                            val waitSec = (result.waitMs / 1000L).coerceIn(5L, 60L)
                            _aiSubtitleState.update {
                                it.copy(
                                    isGenerating = false,
                                    statusMessage = "Respecting rate limits — resuming in ${waitSec}s…"
                                )
                            }
                            delay(result.waitMs + 500L)
                        }
                        else -> {
                            // Clear buffer so the next window captures fresh audio, and pace safely
                            playerManager.audioCaptureProcessor.clearCapturedAudio()
                            delay(12_000L)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _aiSubtitleState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun stopAutoAiSubtitles() {
        aiSubtitleJob?.cancel()
        aiSubtitleJob = null
        _aiSubtitleState.update {
            it.copy(
                isAutoMode = false,
                isGenerating = false,
                statusMessage = if (it.cues.isNotEmpty()) {
                    "AI paused · ${it.cues.size} English cues saved"
                } else null
            )
        }
    }

    fun clearAiSubtitles() {
        stopAutoAiSubtitles()
        lastAutoOverlayText = null
        lastAutoOverlayExpiresAtMs = 0L
        _aiSubtitleState.update {
            it.copy(
                isEnabled = false,
                cues = emptyList(),
                activeOverlayText = null,
                statusMessage = null,
                errorMessage = null,
                detectedLanguage = null
            )
        }
    }

    /**
     * Exports all AI-generated English cues to a standard .srt file and attaches it directly
     * to ExoPlayer as a native external subtitle track (usable with MKV, MP4, M3U8, HLS).
     */
    fun applyAiSubtitlesAsNativeSrtTrack() {
        val session = _uiState.value.activeSession ?: return
        val cues = _aiSubtitleState.value.cues
        if (cues.isEmpty()) return

        viewModelScope.launch {
            val srtUri = aiSubtitleGenerator.exportCuesToSrtUri(session.sourceUri, cues)
            val sub = ExternalSubtitle(
                id = "ai_en_${System.currentTimeMillis()}",
                uri = srtUri.toString(),
                label = "AI English (${cues.size} cues)",
                mimeType = androidx.media3.common.MimeTypes.APPLICATION_SUBRIP,
                formatBadge = "SRT",
                isLocalFile = true
            )
            // Turn off duplicate live overlay once loaded natively into ExoPlayer SubtitleView
            _aiSubtitleState.update { it.copy(isEnabled = false, activeOverlayText = null) }
            attachSubtitleToActiveSession(session, sub)
        }
    }

    private suspend fun transcribeLatestAudioChunk(requestedSeconds: Int): AiSubtitleGenerationResult {
        val posMs = playerManager.player.currentPosition.coerceAtLeast(0L)
        val clip = playerManager.audioCaptureProcessor.snapshotWavClip(requestedSeconds, posMs)
            ?: return AiSubtitleGenerationResult.SilentClip.also {
                _aiSubtitleState.update {
                    it.copy(
                        isGenerating = false,
                        statusMessage = "Waiting for playable audio track…"
                    )
                }
            }

        val preferred = _aiSubtitleState.value.preferredTier
        _aiSubtitleState.update {
            it.copy(
                isGenerating = true,
                errorMessage = null,
                statusMessage = "Transcribing ${clip.durationSec.toInt()}s audio to English (${preferred.displayName})…"
            )
        }

        val result = aiSubtitleGenerator.generateEnglishSubtitlesFromClip(clip, preferredTier = preferred)
        when (result) {
            is AiSubtitleGenerationResult.Success -> {
                val mergedCues = (_aiSubtitleState.value.cues + result.cues)
                    .distinctBy { "${it.startMs}_${it.englishText}" }
                    .sortedBy { it.startMs }
                val latestLines = result.cues
                    .takeLast(2)
                    .map { it.englishText.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .ifBlank { null }
                if (!latestLines.isNullOrBlank()) {
                    lastAutoOverlayText = latestLines
                    lastAutoOverlayExpiresAtMs = System.currentTimeMillis() + 8_000L
                }
                _aiSubtitleState.update {
                    it.copy(
                        isEnabled = true,
                        isGenerating = false,
                        cues = mergedCues,
                        detectedLanguage = result.detectedLanguage,
                        lastModelUsed = result.modelUsed,
                        errorMessage = null,
                        activeOverlayText = latestLines ?: it.activeOverlayText,
                        statusMessage = if (result.cues.isEmpty()) {
                            "No speech detected in clip (${result.detectedLanguage})"
                        } else {
                            "Translated ${result.cues.size} English cues via ${result.modelUsed.displayName} (${result.detectedLanguage} → EN)"
                        }
                    )
                }
            }
            is AiSubtitleGenerationResult.SilentClip -> {
                _aiSubtitleState.update {
                    it.copy(
                        isGenerating = false,
                        statusMessage = "No speech detected in audio segment (quota saved)."
                    )
                }
            }
            is AiSubtitleGenerationResult.Cooldown -> {
                _aiSubtitleState.update {
                    it.copy(
                        isGenerating = false,
                        statusMessage = result.message
                    )
                }
            }
            is AiSubtitleGenerationResult.DailyLimitReached -> {
                _aiSubtitleState.update {
                    it.copy(
                        isGenerating = false,
                        errorMessage = result.message,
                        statusMessage = null
                    )
                }
            }
            is AiSubtitleGenerationResult.Error -> {
                _aiSubtitleState.update {
                    it.copy(
                        isGenerating = false,
                        errorMessage = result.message,
                        statusMessage = null
                    )
                }
            }
        }
        return result
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
            autoSelectSubtitleId = newSub.id
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
                autoSelectSubtitleId = updatedSession.externalSubtitles.lastOrNull()?.id
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
                autoSelectSubtitleId = updated.externalSubtitles.lastOrNull()?.id
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
        stopAutoAiSubtitles()
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
                resumePositionMs = resumeMs
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
                    syncActiveAiCaptionOverlay()
                    tick++
                    if (tick % 4 == 0 && _aiSubtitleState.value.isEnabled) {
                        rateLimiter.refreshSnapshot()
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
                    }
                    delay(1_000L)
                }
            }
        }
    }

    private fun syncActiveAiCaptionOverlay() {
        val aiState = _aiSubtitleState.value
        if (!aiState.isEnabled) return

        val posMs = playbackProgressState.value.currentPositionMs
        val matchedCue = aiState.cues.lastOrNull { posMs in it.startMs..it.endMs }
        val resolvedText = matchedCue?.englishText ?: run {
            if (System.currentTimeMillis() < lastAutoOverlayExpiresAtMs) {
                lastAutoOverlayText
            } else {
                null
            }
        }
        if (resolvedText != aiState.activeOverlayText) {
            _aiSubtitleState.update { it.copy(activeOverlayText = resolvedText) }
        }
    }

    override fun onCleared() {
        aiSubtitleJob?.cancel()
        progressPollingJob?.cancel()
        jsonEnrichJob?.cancel()
        volumePersistJob?.cancel()
        playerManager.release()
        super.onCleared()
    }
}
