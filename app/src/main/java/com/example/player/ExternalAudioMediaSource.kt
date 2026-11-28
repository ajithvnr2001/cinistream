package com.example.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.example.model.ExternalAudioTrack
import com.example.model.PlaylistAudioSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Validates and inspects remote external audio URLs (HLS `.m3u8` first, plus DASH `.mpd` and
 * direct network audio streams). Performs an optional bounded 2 KB HTTP peek on `Dispatchers.IO`
 * so even extensionless HLS audio playlists are reliably identified as HLS.
 */
object ExternalAudioUrlInspector {

    private val DIRECT_AUDIO_EXTENSIONS = mapOf(
        "aac" to "AAC",
        "m4a" to "M4A",
        "mp3" to "MP3",
        "ac3" to "AC3",
        "eac3" to "EAC3",
        "ec3" to "EAC3",
        "dts" to "DTS",
        "opus" to "OPUS",
        "flac" to "FLAC",
        "ogg" to "OGG",
        "oga" to "OGG",
        "wav" to "WAV",
        "mka" to "MKA",
        "weba" to "WEBA",
        "ts" to "TS"
    )

    private val GENERIC_FILENAMES = setOf(
        "master", "index", "playlist", "audio", "stream", "manifest", "chunklist", "media"
    )

    fun isValidAudioUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.length < 10 || trimmed.length > 4096) return false
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return false
        val lower = trimmed.lowercase(Locale.US)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    fun inferFromUrlOnly(
        url: String,
        customLabel: String? = null,
        id: String = UUID.randomUUID().toString().take(8)
    ): ExternalAudioTrack {
        val trimmed = url.trim()
        val lowerPath = trimmed.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val lowerFull = trimmed.lowercase(Locale.US)
        val ext = lowerPath.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .takeIf { it.length in 1..5 }

        val isHls = ext == "m3u8" ||
            lowerFull.contains(".m3u8") ||
            lowerFull.contains("format=m3u8") ||
            lowerFull.contains("/hls/")
        val isDash = !isHls && (ext == "mpd" || lowerFull.contains(".mpd"))
        val badge = when {
            isHls -> "HLS"
            isDash -> "DASH"
            ext != null && DIRECT_AUDIO_EXTENSIONS.containsKey(ext) -> DIRECT_AUDIO_EXTENSIONS[ext]!!
            else -> "AUDIO"
        }

        val label = customLabel?.trim()?.takeIf { it.isNotBlank() } ?: deriveReadableLabel(trimmed)
        return ExternalAudioTrack(
            id = id,
            url = trimmed,
            label = label,
            formatBadge = badge,
            isHls = isHls,
            isDash = isDash
        )
    }

    suspend fun inspectAndBuild(
        url: String,
        customLabel: String? = null,
        okHttpClient: OkHttpClient? = null,
        headers: Map<String, String> = emptyMap()
    ): ExternalAudioTrack {
        val base = inferFromUrlOnly(url = url, customLabel = customLabel)
        if (base.isHls || base.isDash || okHttpClient == null) return base

        // For extensionless or generic URLs, peek up to 2 KB off the main thread to detect #EXTM3U / DASH
        val lowerPath = base.url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val ext = lowerPath.substringAfterLast('/', "").substringAfterLast('.', "")
        if (ext in DIRECT_AUDIO_EXTENSIONS) return base

        return withContext(Dispatchers.IO) {
            runCatching {
                val peekClient = okHttpClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val reqBuilder = Request.Builder()
                    .url(base.url)
                    .header("Range", "bytes=0-2047")
                    .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                headers.forEach { (k, v) ->
                    if (!k.equals("Range", ignoreCase = true)) {
                        reqBuilder.header(k, v)
                    }
                }
                peekClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val ct = resp.header("Content-Type").orEmpty().lowercase(Locale.US)
                    val bodyBytes = resp.body?.byteStream()?.let { stream ->
                        val buf = ByteArray(2048)
                        val read = stream.read(buf)
                        if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
                    }.orEmpty()
                    val trimmedBody = bodyBytes.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    when {
                        ct.contains("mpegurl") ||
                            ct.contains("x-mpegurl") ||
                            trimmedBody.startsWith("#EXTM3U") -> {
                            base.copy(isHls = true, isDash = false, formatBadge = "HLS")
                        }
                        ct.contains("dash+xml") ||
                            trimmedBody.contains("<MPD", ignoreCase = true) -> {
                            base.copy(isHls = false, isDash = true, formatBadge = "DASH")
                        }
                        ct.contains("audio/x-m4a") || ct.contains("audio/mp4") || ct.contains("audio/mp4a") -> {
                            base.copy(formatBadge = "M4A")
                        }
                        ct.contains("audio/aac") -> {
                            base.copy(formatBadge = "AAC")
                        }
                        ct.contains("audio/mpeg") || ct.contains("audio/mp3") -> {
                            base.copy(formatBadge = "MP3")
                        }
                        ct.contains("audio/eac3") || ct.contains("audio/ec-3") -> {
                            base.copy(formatBadge = "EAC3")
                        }
                        ct.contains("audio/ac3") || ct.contains("audio/vnd.dolby") -> {
                            base.copy(formatBadge = "AC3")
                        }
                        ct.contains("audio/opus") || ct.contains("audio/ogg") -> {
                            base.copy(formatBadge = "OPUS")
                        }
                        ct.contains("audio/flac") || ct.contains("audio/x-flac") -> {
                            base.copy(formatBadge = "FLAC")
                        }
                        ct.contains("audio/wav") || ct.contains("audio/x-wav") ||
                            ct.contains("audio/wave") || ct.contains("audio/vnd.wave") -> {
                            base.copy(formatBadge = "WAV")
                        }
                        else -> base
                    }
                }
            }.getOrDefault(base)
        }
    }

    internal fun deriveReadableLabel(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val segments = parsed?.pathSegments.orEmpty().filter { it.isNotBlank() }
        val rawLast = segments.lastOrNull().orEmpty()
        val decodedLast = runCatching { URLDecoder.decode(rawLast, "UTF-8") }.getOrDefault(rawLast)
        val stem = decodedLast.substringBeforeLast('.').trim()

        val candidate = if (stem.isNotBlank() && stem.lowercase(Locale.US) !in GENERIC_FILENAMES) {
            stem
        } else {
            val parent = segments.dropLast(1).lastOrNull()?.let { seg ->
                runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
            }.orEmpty()
            val host = parsed?.host
            when {
                parent.isNotBlank() && parent.lowercase(Locale.US) !in GENERIC_FILENAMES -> parent
                !host.isNullOrBlank() -> host
                else -> "External Audio"
            }
        }

        val cleaned = candidate
            .replace('_', ' ')
            .replace('-', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(36)

        return if (cleaned.isBlank()) {
            "External Audio"
        } else {
            cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
    }
}

/**
 * Result of resolving external audio URLs declared on a JSON playlist movie, collection film, or episode.
 */
data class ResolvedPlaylistAudioBundle(
    val tracks: List<ExternalAudioTrack> = emptyList(),
    val autoSelectAudioId: String? = null,
    val initialDelayMs: Long? = null
)

/**
 * Converts JSON playlist [PlaylistAudioSpec] entries into playable [ExternalAudioTrack] instances,
 * validates and deduplicates URLs, and determines which external audio track (and optional initial
 * sync delay) should activate automatically when playback starts.
 */
object PlaylistAudioResolver {

    suspend fun resolve(
        specs: List<PlaylistAudioSpec>,
        okHttpClient: OkHttpClient? = null,
        headers: Map<String, String> = emptyMap()
    ): ResolvedPlaylistAudioBundle {
        if (specs.isEmpty()) return ResolvedPlaylistAudioBundle()

        val seen = LinkedHashSet<String>()
        val resolvedPairs = mutableListOf<Pair<PlaylistAudioSpec, ExternalAudioTrack>>()
        for (spec in specs) {
            val trimmed = spec.url.trim()
            if (!ExternalAudioUrlInspector.isValidAudioUrl(trimmed)) continue
            if (!seen.add(trimmed.lowercase(Locale.ROOT))) continue

            val preferredLabel = spec.label?.takeIf { it.isNotBlank() }
                ?: spec.language?.takeIf { it.isNotBlank() }
            val track = ExternalAudioUrlInspector.inspectAndBuild(
                url = trimmed,
                customLabel = preferredLabel,
                okHttpClient = okHttpClient,
                headers = headers
            )
            resolvedPairs.add(spec to track)
        }
        if (resolvedPairs.isEmpty()) return ResolvedPlaylistAudioBundle()

        val explicitDefault = resolvedPairs.firstOrNull { it.first.isDefault == true }
        val allExplicitlyDisabled = resolvedPairs.all { it.first.isDefault == false }
        val selectedPair = explicitDefault ?: if (!allExplicitlyDisabled) resolvedPairs.first() else null

        val initialDelay = selectedPair?.first?.delayMs
            ?: resolvedPairs.firstNotNullOfOrNull { it.first.delayMs }

        return ResolvedPlaylistAudioBundle(
            tracks = resolvedPairs.map { it.second },
            autoSelectAudioId = selectedPair?.second?.id,
            initialDelayMs = initialDelay?.let { AudioDelayMath.clampDelayMs(it) }
        )
    }
}

/**
 * Wraps an external audio [MediaSource] (including muxed HLS `.m3u8` master playlists) so ONLY
 * `C.TRACK_TYPE_AUDIO` track groups are exposed to `MergingMediaSource`, preventing foreign video
 * or subtitle tracks from interfering with primary playback. Each exposed audio format is tagged
 * with [ExternalAudioTrack.TRACK_ID_PREFIX] for reliable auto-selection and UI identification.
 */
@OptIn(UnstableApi::class)
class AudioOnlyFilteringMediaSource(
    mediaSource: MediaSource,
    private val externalAudio: ExternalAudioTrack
) : WrappingMediaSource(mediaSource) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val delegatePeriod = mediaSource.createPeriod(id, allocator, startPositionUs)
        return AudioOnlyFilteringMediaPeriod(delegatePeriod, externalAudio)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val unwrapped = (mediaPeriod as? AudioOnlyFilteringMediaPeriod)?.delegate ?: mediaPeriod
        mediaSource.releasePeriod(unwrapped)
    }
}

@OptIn(UnstableApi::class)
class AudioOnlyFilteringMediaPeriod(
    val delegate: MediaPeriod,
    private val externalAudio: ExternalAudioTrack
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null
    private var filteredTrackGroups: TrackGroupArray = TrackGroupArray.EMPTY
    private val taggedToOriginalGroup = LinkedHashMap<TrackGroup, TrackGroup>()

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        delegate.prepare(this, positionUs)
    }

    override fun maybeThrowPrepareError() {
        delegate.maybeThrowPrepareError()
    }

    override fun getTrackGroups(): TrackGroupArray = filteredTrackGroups

    override fun getStreamKeys(trackSelections: MutableList<ExoTrackSelection>): MutableList<StreamKey> {
        val remapped = trackSelections.mapTo(ArrayList(trackSelections.size)) { remapSelection(it) }
        return delegate.getStreamKeys(remapped)
    }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        val remappedSelections = Array<ExoTrackSelection?>(selections.size) { idx ->
            selections[idx]?.let { remapSelection(it) }
        }
        return delegate.selectTracks(
            remappedSelections,
            mayRetainStreamFlags,
            streams,
            streamResetFlags,
            positionUs
        )
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        delegate.discardBuffer(positionUs, toKeyframe)
    }

    override fun readDiscontinuity(): Long = delegate.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long = delegate.seekToUs(positionUs)

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        delegate.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = delegate.bufferedPositionUs

    override fun getNextLoadPositionUs(): Long = delegate.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
        delegate.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = delegate.isLoading

    override fun reevaluateBuffer(positionUs: Long) {
        delegate.reevaluateBuffer(positionUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        rebuildFilteredTrackGroups(mediaPeriod.trackGroups)
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }

    private fun rebuildFilteredTrackGroups(sourceGroups: TrackGroupArray) {
        taggedToOriginalGroup.clear()
        val audioGroups = mutableListOf<TrackGroup>()
        var audioGroupOrdinal = 0

        for (groupIdx in 0 until sourceGroups.length) {
            val origGroup = sourceGroups[groupIdx]
            val isAudioGroup = origGroup.type == C.TRACK_TYPE_AUDIO ||
                (0 until origGroup.length).any { idx ->
                    val f = origGroup.getFormat(idx)
                    MimeTypes.isAudio(f.sampleMimeType) || MimeTypes.isAudio(f.containerMimeType)
                }
            if (!isAudioGroup) continue

            val taggedFormats = Array(origGroup.length) { trackIdx ->
                val f = origGroup.getFormat(trackIdx)
                val baseOrigLabel = f.label?.takeIf { it.isNotBlank() }
                    ?: f.language?.let { lang ->
                        Locale.forLanguageTag(lang).displayLanguage.takeIf { d ->
                            d.isNotBlank() && !d.equals("und", ignoreCase = true)
                        }
                    }
                val displayLabel = buildString {
                    append(externalAudio.label)
                    if (!baseOrigLabel.isNullOrBlank() &&
                        !baseOrigLabel.equals(externalAudio.label, ignoreCase = true)
                    ) {
                        append(" · ")
                        append(baseOrigLabel)
                    }
                    append(" [")
                    append(externalAudio.formatBadge)
                    append("]")
                }
                f.buildUpon()
                    .setId(ExternalAudioTrack.taggedTrackId(externalAudio.id, audioGroupOrdinal, trackIdx))
                    .setLabels(emptyList())
                    .setLabel(displayLabel)
                    .build()
            }
            val taggedGroup = TrackGroup(
                "${ExternalAudioTrack.TRACK_ID_PREFIX}${externalAudio.id}_g$audioGroupOrdinal",
                *taggedFormats
            )
            taggedToOriginalGroup[taggedGroup] = origGroup
            audioGroups.add(taggedGroup)
            audioGroupOrdinal++
        }

        filteredTrackGroups = if (audioGroups.isEmpty()) {
            TrackGroupArray.EMPTY
        } else {
            TrackGroupArray(*audioGroups.toTypedArray())
        }
    }

    private fun remapSelection(selection: ExoTrackSelection): ExoTrackSelection {
        val originalGroup = taggedToOriginalGroup[selection.trackGroup] ?: return selection
        return RemappedTrackGroupSelection(selection, originalGroup)
    }

    private class RemappedTrackGroupSelection(
        private val delegate: ExoTrackSelection,
        private val originalGroup: TrackGroup
    ) : ExoTrackSelection by delegate {
        override fun getTrackGroup(): TrackGroup = originalGroup
        override fun getFormat(index: Int): Format = originalGroup.getFormat(delegate.getIndexInTrackGroup(index))
        override fun getSelectedFormat(): Format = originalGroup.getFormat(delegate.selectedIndexInTrackGroup)
    }
}

/**
 * Independent bidirectional audio-sync [WrappingMediaSource] for external audio streams merged via
 * `MergingMediaSource`.
 *
 * Unlike shifting a muxed video+audio stream, external audio runs on its own [MediaPeriod], so this
 * wrapper translates `prepare`, `seekToUs`, `selectTracks`, `continueLoading`, `discardBuffer`,
 * `reevaluateBuffer`, `bufferedPositionUs`, and [AudioOffsetSampleStream] sample timestamps
 * independently by `audioDelayUsProvider()`:
 *
 * - Positive delay (`+D`, e.g. `+2m 30s`): external audio seeks to `max(0, videoPos - D)` and shifts
 *   sample timestamps by `+D` so video stays pinned on the exact frame while audio plays `D` later.
 * - Negative delay (`-D`, e.g. `-5m`): external audio seeks directly to `videoPos + |D|` and shifts
 *   sample timestamps by `-D` so audio jumps instantaneously ahead with zero multi-minute buffering wait.
 * - Always returns the exact requested `positionUs` from `seekToUs` / `selectTracks` and `C.TIME_UNSET`
 *   from `readDiscontinuity()` so `MergingMediaPeriod` never throws on mismatched durations or large offsets.
 */
@OptIn(UnstableApi::class)
class ExternalAudioSyncMediaSource(
    mediaSource: MediaSource,
    private val audioDelayUsProvider: () -> Long
) : WrappingMediaSource(mediaSource) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val delayUs = audioDelayUsProvider()
        val shiftedStartUs = (startPositionUs - delayUs).coerceAtLeast(0L)
        val delegatePeriod = mediaSource.createPeriod(id, allocator, shiftedStartUs)
        return ExternalAudioSyncMediaPeriod(delegatePeriod, audioDelayUsProvider)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val unwrapped = (mediaPeriod as? ExternalAudioSyncMediaPeriod)?.delegate ?: mediaPeriod
        mediaSource.releasePeriod(unwrapped)
    }
}

@OptIn(UnstableApi::class)
class ExternalAudioSyncMediaPeriod(
    val delegate: MediaPeriod,
    private val audioDelayUsProvider: () -> Long
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        val shiftedUs = (positionUs - audioDelayUsProvider()).coerceAtLeast(0L)
        delegate.prepare(this, shiftedUs)
    }

    override fun maybeThrowPrepareError() {
        delegate.maybeThrowPrepareError()
    }

    override fun getTrackGroups(): TrackGroupArray = delegate.trackGroups

    override fun getStreamKeys(trackSelections: MutableList<ExoTrackSelection>): MutableList<StreamKey> =
        delegate.getStreamKeys(trackSelections)

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        val childStreams = arrayOfNulls<SampleStream>(streams.size)
        for (i in streams.indices) {
            childStreams[i] = (streams[i] as? AudioOffsetSampleStream)?.delegate ?: streams[i]
        }

        val shiftedUs = (positionUs - audioDelayUsProvider()).coerceAtLeast(0L)
        delegate.selectTracks(
            selections,
            mayRetainStreamFlags,
            childStreams,
            streamResetFlags,
            shiftedUs
        )

        for (i in streams.indices) {
            val childStream = childStreams[i]
            val existingWrapper = streams[i] as? AudioOffsetSampleStream
            streams[i] = when {
                childStream == null -> null
                existingWrapper != null && existingWrapper.delegate === childStream -> existingWrapper
                else -> AudioOffsetSampleStream(childStream, audioDelayUsProvider)
            }
        }
        // Always return exact requested positionUs so MergingMediaPeriod never throws on large offsets
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        val shiftedUs = (positionUs - audioDelayUsProvider()).coerceAtLeast(0L)
        delegate.discardBuffer(shiftedUs, toKeyframe)
    }

    override fun readDiscontinuity(): Long {
        // Consume any internal delegate discontinuity without tripping MergingMediaPeriod's secondary-period assertion
        delegate.readDiscontinuity()
        return C.TIME_UNSET
    }

    override fun seekToUs(positionUs: Long): Long {
        val shiftedUs = (positionUs - audioDelayUsProvider()).coerceAtLeast(0L)
        delegate.seekToUs(shiftedUs)
        // Return exact requested positionUs to satisfy MergingMediaPeriod contract across any offset/duration mismatch
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        positionUs

    override fun getBufferedPositionUs(): Long {
        val raw = delegate.bufferedPositionUs
        if (raw == C.TIME_END_OF_SOURCE || raw == C.TIME_UNSET) return raw
        return (raw + audioDelayUsProvider()).coerceAtLeast(0L)
    }

    override fun getNextLoadPositionUs(): Long {
        val raw = delegate.nextLoadPositionUs
        if (raw == C.TIME_END_OF_SOURCE || raw == C.TIME_UNSET) return raw
        return (raw + audioDelayUsProvider()).coerceAtLeast(0L)
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        val shiftedPos = (loadingInfo.playbackPositionUs - audioDelayUsProvider()).coerceAtLeast(0L)
        val adjustedInfo = loadingInfo.buildUpon()
            .setPlaybackPositionUs(shiftedPos)
            .build()
        return delegate.continueLoading(adjustedInfo)
    }

    override fun isLoading(): Boolean = delegate.isLoading

    override fun reevaluateBuffer(positionUs: Long) {
        val shiftedUs = (positionUs - audioDelayUsProvider()).coerceAtLeast(0L)
        delegate.reevaluateBuffer(shiftedUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }
}
