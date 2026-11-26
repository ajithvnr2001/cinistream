package com.example.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Container- and codec-agnostic bidirectional audio-sync [WrappingMediaSource].
 *
 * Shifts `C.TRACK_TYPE_AUDIO` sample timestamps (`buffer.timeUs`) directly at the [SampleStream]
 * boundary before samples enter `MediaCodecAudioRenderer`, `FfmpegAudioRenderer`, or
 * `DefaultAudioSink`:
 *
 * - Works identically on **HLS muxed `.ts` segments**, **HLS demuxed `#EXT-X-MEDIA:TYPE=AUDIO`
 *   separate audio segments (`.ts`, `.aac`, `.m4s`)**, DASH, MKV, MP4, and local files.
 * - Works identically across PCM, AAC, Opus, AC-3, E-AC-3, DTS, TrueHD, HDMI/Bluetooth
 *   passthrough, hardware MediaCodec, and NextLib FFmpeg audio decoders.
 * - Supports full bidirectional offsets (`-10 s` to `+10 s`) with zero PCM ring-buffer RAM
 *   overhead and zero silence injection while keeping video, subtitles, and seekbar locked.
 */
@OptIn(UnstableApi::class)
class AudioSyncMediaSource(
    mediaSource: MediaSource,
    private val audioDelayUsProvider: () -> Long
) : WrappingMediaSource(mediaSource) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val delegatePeriod = mediaSource.createPeriod(id, allocator, startPositionUs)
        return AudioSyncMediaPeriod(delegatePeriod, audioDelayUsProvider)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val unwrapped = (mediaPeriod as? AudioSyncMediaPeriod)?.delegate ?: mediaPeriod
        mediaSource.releasePeriod(unwrapped)
    }
}

/**
 * [MediaPeriod] wrapper that intercepts `C.TRACK_TYPE_AUDIO` [SampleStream]s returned by
 * `HlsMediaPeriod`, `DashMediaPeriod`, `ProgressiveMediaPeriod`, or `MergingMediaPeriod`
 * while preserving exact unwrap/rewrap semantics so underlying HLS/DASH periods never see
 * foreign [SampleStream] instances.
 */
@OptIn(UnstableApi::class)
class AudioSyncMediaPeriod(
    val delegate: MediaPeriod,
    private val audioDelayUsProvider: () -> Long
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        delegate.prepare(this, positionUs)
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

        val resultPositionUs = delegate.selectTracks(
            selections,
            mayRetainStreamFlags,
            childStreams,
            streamResetFlags,
            positionUs
        )

        for (i in streams.indices) {
            val childStream = childStreams[i]
            val existingWrapper = streams[i] as? AudioOffsetSampleStream
            streams[i] = when {
                childStream == null -> null
                isAudioSelection(selections.getOrNull(i)) -> {
                    if (existingWrapper != null && existingWrapper.delegate === childStream) {
                        existingWrapper
                    } else {
                        AudioOffsetSampleStream(childStream, audioDelayUsProvider)
                    }
                }
                else -> childStream
            }
        }
        return resultPositionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        val delayUs = audioDelayUsProvider()
        val safePositionUs = if (delayUs > 0L) {
            (positionUs - delayUs).coerceAtLeast(0L)
        } else {
            positionUs
        }
        delegate.discardBuffer(safePositionUs, toKeyframe)
    }

    override fun readDiscontinuity(): Long = delegate.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long = delegate.seekToUs(positionUs)

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        delegate.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long {
        val raw = delegate.bufferedPositionUs
        if (raw == C.TIME_END_OF_SOURCE || raw == C.TIME_UNSET) return raw
        val delayUs = audioDelayUsProvider()
        return if (delayUs < 0L) {
            (raw + delayUs).coerceAtLeast(0L)
        } else {
            raw
        }
    }

    override fun getNextLoadPositionUs(): Long = delegate.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
        delegate.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = delegate.isLoading

    override fun reevaluateBuffer(positionUs: Long) {
        delegate.reevaluateBuffer(positionUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }

    companion object {
        internal fun isAudioSelection(selection: ExoTrackSelection?): Boolean {
            if (selection == null) return false
            val group = selection.trackGroup
            if (group.type == C.TRACK_TYPE_AUDIO) return true
            if (group.length <= 0) return false
            val format = runCatching { selection.selectedFormat }.getOrNull() ?: group.getFormat(0)
            return MimeTypes.isAudio(format.sampleMimeType) ||
                MimeTypes.isAudio(format.containerMimeType)
        }
    }
}

/**
 * [SampleStream] wrapper for `C.TRACK_TYPE_AUDIO` that shifts incoming sample presentation
 * timestamps by `audioDelayUsProvider()`:
 *
 * - Positive delay (`+D`): stamps original sample `t` as `t + D` so audio plays `D` later
 *   relative to video and subtitles.
 * - Negative delay (`-D`): stamps original sample `t` as `t - D` so audio plays `D` earlier
 *   relative to video and subtitles, cleanly dropping any leading samples with `t - D < 0`
 *   so hardware MediaCodec decoders never receive negative `presentationTimeUs`.
 */
@OptIn(UnstableApi::class)
class AudioOffsetSampleStream(
    val delegate: SampleStream,
    private val audioDelayUsProvider: () -> Long
) : SampleStream {

    override fun isReady(): Boolean = delegate.isReady()

    override fun maybeThrowError() {
        delegate.maybeThrowError()
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int
    ): Int {
        val delayUs = audioDelayUsProvider()
        val isPeek = (readFlags and SampleStream.FLAG_PEEK) != 0
        while (true) {
            val result = delegate.readData(formatHolder, buffer, readFlags)
            if (result != C.RESULT_BUFFER_READ || buffer.isEndOfStream || delayUs == 0L) {
                return result
            }
            val shiftedTimeUs = buffer.timeUs + delayUs
            if (shiftedTimeUs < 0L && !isPeek) {
                buffer.clear()
                continue
            }
            buffer.timeUs = shiftedTimeUs.coerceAtLeast(0L)
            return result
        }
    }

    override fun skipData(positionUs: Long): Int {
        val delayUs = audioDelayUsProvider()
        val adjustedPositionUs = (positionUs - delayUs).coerceAtLeast(0L)
        return delegate.skipData(adjustedPositionUs)
    }
}
