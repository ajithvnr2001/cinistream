package com.example

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import com.example.model.ExternalAudioTrack
import com.example.model.PlaylistAudioSpec
import com.example.model.TrackOption
import com.example.player.AudioDelayMath
import com.example.player.AudioOnlyFilteringMediaPeriod
import com.example.player.ExternalAudioSyncMediaPeriod
import com.example.player.ExternalAudioUrlInspector
import com.example.player.JsonPlaylistLoadResult
import com.example.player.JsonPlaylistParser
import com.example.player.PlaylistAudioResolver
import com.example.player.SyncTimeUnit
import com.example.remote.LocalWebRemoteServer
import com.example.remote.RemotePushAction
import com.example.remote.RemotePushCommand
import com.example.ui.player.TracksSheet
import com.example.ui.theme.CineStreamTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalAudioUrlSyncTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun externalAudioUrlInspector_validatesSchemesAndDetectsHlsAndFormats() {
        assertTrue(ExternalAudioUrlInspector.isValidAudioUrl("https://cdn.example.com/hindi/audio.m3u8"))
        assertTrue(ExternalAudioUrlInspector.isValidAudioUrl("http://192.168.1.5:8080/track.aac"))
        assertFalse(ExternalAudioUrlInspector.isValidAudioUrl("file:///sdcard/audio.mp3"))
        assertFalse(ExternalAudioUrlInspector.isValidAudioUrl("content://media/external/audio/1"))
        assertFalse(ExternalAudioUrlInspector.isValidAudioUrl("javascript:alert(1)"))
        assertFalse(ExternalAudioUrlInspector.isValidAudioUrl("   "))

        val hls = ExternalAudioUrlInspector.inferFromUrlOnly(
            url = "https://cdn.example.com/dubs/hindi_51/master.m3u8",
            customLabel = null
        )
        assertTrue(hls.isHls)
        assertFalse(hls.isDash)
        assertEquals("HLS", hls.formatBadge)
        assertEquals("Hindi 51", hls.label)

        val customLabeled = ExternalAudioUrlInspector.inferFromUrlOnly(
            url = "https://cdn.example.com/audio/stream.m3u8?token=abc",
            customLabel = "Tamil 5.1 Dub"
        )
        assertTrue(customLabeled.isHls)
        assertEquals("Tamil 5.1 Dub", customLabeled.label)

        val directAc3 = ExternalAudioUrlInspector.inferFromUrlOnly("https://cdn.example.com/movie_dub.ac3")
        assertFalse(directAc3.isHls)
        assertEquals("AC3", directAc3.formatBadge)
        assertEquals("Movie dub", directAc3.label)

        val dash = ExternalAudioUrlInspector.inferFromUrlOnly("https://cdn.example.com/audio.mpd")
        assertTrue(dash.isDash)
        assertEquals("DASH", dash.formatBadge)

        val taggedId = ExternalAudioTrack.taggedTrackId("abc12345", 0, 1)
        assertEquals("abc12345", ExternalAudioTrack.extractAudioId(taggedId))
    }

    @Test
    fun audioOnlyFilteringMediaPeriod_stripsVideoAndTextAndTagsAudioTracks() {
        val videoGroup = TrackGroup(
            "v0",
            Format.Builder().setId("v_1080").setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(1080).build()
        )
        val audioGroup = TrackGroup(
            "a0",
            Format.Builder().setId("a_hi").setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("hi").setLabel("Hindi").build(),
            Format.Builder().setId("a_en").setSampleMimeType(MimeTypes.AUDIO_AC3).setLanguage("en").build()
        )
        val textGroup = TrackGroup(
            "t0",
            Format.Builder().setId("t_en").setSampleMimeType(MimeTypes.TEXT_VTT).build()
        )

        val fakePeriod = FakeDelegateMediaPeriod(TrackGroupArray(videoGroup, audioGroup, textGroup))
        val extAudio = ExternalAudioTrack(
            id = "aud42",
            url = "https://cdn.example.com/dub/master.m3u8",
            label = "Dubbed",
            formatBadge = "HLS",
            isHls = true
        )
        val filteringPeriod = AudioOnlyFilteringMediaPeriod(fakePeriod, extAudio)

        var prepared = false
        filteringPeriod.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) {
                    prepared = true
                }
                override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
            },
            0L
        )
        assertTrue(prepared)

        // Only the audio TrackGroup survives (video + text stripped!)
        assertEquals(1, filteringPeriod.trackGroups.length)
        val exposedGroup = filteringPeriod.trackGroups[0]
        assertEquals(C.TRACK_TYPE_AUDIO, exposedGroup.type)
        assertEquals(2, exposedGroup.length)
        assertEquals("aud42", ExternalAudioTrack.extractAudioId(exposedGroup.getFormat(0).id))
        assertTrue(exposedGroup.getFormat(0).label!!.contains("Dubbed"))
        assertTrue(exposedGroup.getFormat(0).label!!.contains("[HLS]"))

        // Selecting the tagged group remaps back to the exact original audioGroup on delegate
        val selection = FixedTrackSelection(exposedGroup, 1)
        val streams = arrayOfNulls<SampleStream>(1)
        filteringPeriod.selectTracks(
            arrayOf(selection),
            booleanArrayOf(false),
            streams,
            booleanArrayOf(false),
            5_000_000L
        )
        assertSame(audioGroup, fakePeriod.lastSelectedTrackGroup)
        assertNotNull(streams[0])
    }

    @Test
    fun externalAudioSyncMediaPeriod_shiftsSeekLoadBufferAndSampleTimestampsIndependently() {
        val audioGroup = TrackGroup(
            "a0",
            Format.Builder().setId("a_0").setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        )
        val fakeSampleTimesUs = ArrayDeque(listOf(100_000_000L, 300_000_000L, 400_000_000L))
        val fakePeriod = FakeDelegateMediaPeriod(TrackGroupArray(audioGroup), fakeSampleTimesUs)
        var currentDelayUs = 300_000_000L // +5 minutes (+300s)

        val syncPeriod = ExternalAudioSyncMediaPeriod(fakePeriod) { currentDelayUs }
        syncPeriod.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) = Unit
                override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
            },
            600_000_000L // video at 10:00
        )
        // Prepared at 10:00 - 5:00 = 5:00
        assertEquals(300_000_000L, fakePeriod.lastPreparedPosUs)

        // Positive +5m delay: seek video at 10:00 -> external audio seeks to 05:00, returns exact 10:00
        val seekResPos = syncPeriod.seekToUs(600_000_000L)
        assertEquals(600_000_000L, seekResPos)
        assertEquals(300_000_000L, fakePeriod.lastSeekPosUs)

        // Positive +5m delay at video 01:00 -> external audio clamps seek to 0:00, still returns exact 01:00
        val seekResEarly = syncPeriod.seekToUs(60_000_000L)
        assertEquals(60_000_000L, seekResEarly)
        assertEquals(0L, fakePeriod.lastSeekPosUs)

        // Negative -5m delay: seek video at 10:00 -> external audio jumps directly to 15:00, returns exact 10:00
        currentDelayUs = -300_000_000L
        val seekResNeg = syncPeriod.seekToUs(600_000_000L)
        assertEquals(600_000_000L, seekResNeg)
        assertEquals(900_000_000L, fakePeriod.lastSeekPosUs)

        // Discontinuity must return C.TIME_UNSET for MergingMediaPeriod safety
        assertEquals(C.TIME_UNSET, syncPeriod.readDiscontinuity())

        // continueLoading translates playback position by -delayUs (10m - (-5m) = 15m)
        syncPeriod.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(600_000_000L).build())
        assertEquals(900_000_000L, fakePeriod.lastContinueLoadingPosUs)

        // bufferedPositionUs translates back by +delayUs (16m + (-5m) = 11m)
        fakePeriod.mockBufferedPosUs = 960_000_000L
        assertEquals(660_000_000L, syncPeriod.bufferedPositionUs)

        // Select tracks & verify sample timestamps shift by -5m, dropping < 0 samples cleanly
        val streams = arrayOfNulls<SampleStream>(1)
        val selectRes = syncPeriod.selectTracks(
            arrayOf(FixedTrackSelection(audioGroup, 0)),
            booleanArrayOf(false),
            streams,
            booleanArrayOf(false),
            600_000_000L
        )
        assertEquals(600_000_000L, selectRes)
        val buf = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        // First sample 100s - 300s = -200s -> dropped automatically; next sample 300s - 300s = 0s!
        assertEquals(C.RESULT_BUFFER_READ, streams[0]!!.readData(FormatHolder(), buf, 0))
        assertEquals(0L, buf.timeUs)
        assertEquals(C.RESULT_BUFFER_READ, streams[0]!!.readData(FormatHolder(), buf, 0))
        assertEquals(100_000_000L, buf.timeUs)
    }

    @Test
    fun audioDelayMath_supportsUnboundedCustomSyncAndExternalAnchorSeek() {
        assertEquals(300_000L, AudioDelayMath.parseDelayInputMs("5m", SyncTimeUnit.MILLISECONDS))
        assertEquals(-135_000L, AudioDelayMath.parseDelayInputMs("-2.25min", SyncTimeUnit.SECONDS))
        assertEquals("+5m 00s", AudioDelayMath.formatDelayLabel(300_000L))
        assertEquals("-2m 15s", AudioDelayMath.formatDelayLabel(-135_000L))
        assertEquals("-2m 15.5s", AudioDelayMath.formatDelayLabel(-135_500L))

        // When external audio is active, video stays pinned on anchor (10:00) even with +5m delay
        assertEquals(
            600_000L,
            AudioDelayMath.computeRealignSeekPositionMs(
                currentPositionMs = 600_000L,
                audioDelayMs = 300_000L,
                durationMs = 3_600_000L,
                isExternalAudioActive = true
            )
        )
        // When embedded audio is active, positive delay shifts seek back by delay
        assertEquals(
            597_500L,
            AudioDelayMath.computeRealignSeekPositionMs(
                currentPositionMs = 600_000L,
                audioDelayMs = 2_500L,
                durationMs = 3_600_000L,
                isExternalAudioActive = false
            )
        )
    }

    @Test
    fun tracksSheet_addExternalAudioUrlRemoveAndUnboundedSync() {
        var loadedUrl: String? = null
        var loadedLabel: String? = null
        var removedAudioId: String? = null
        var appliedDelayMs = 0L

        val extTrack = ExternalAudioTrack(
            id = "ext99",
            url = "https://cdn.example.com/hindi/audio.m3u8",
            label = "Hindi Dub",
            formatBadge = "HLS",
            isHls = true
        )
        val audioTracks = listOf(
            TrackOption("orig_0", 0, 0, "English", "Stereo · AAC", "en", isSelected = false),
            TrackOption(
                id = ExternalAudioTrack.taggedTrackId("ext99", 0, 0),
                groupIndex = 1,
                trackIndex = 0,
                label = "Hindi Dub [HLS]",
                detail = "External URL · Stereo · AAC",
                language = "hi",
                isSelected = true,
                isExternal = true,
                externalAudioId = "ext99"
            )
        )

        composeRule.setContent {
            CineStreamTheme {
                var currentDelay by remember { mutableLongStateOf(0L) }
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = audioTracks,
                    audioDelayMs = currentDelay,
                    isExternalAudioActive = true,
                    externalAudios = listOf(extTrack),
                    onLoadExternalAudioUrl = { url, label ->
                        loadedUrl = url
                        loadedLabel = label
                    },
                    onRemoveExternalAudio = { id -> removedAudioId = id },
                    onAdjustAudioDelay = { delta ->
                        currentDelay = AudioDelayMath.clampDelayMs(currentDelay + delta)
                        appliedDelayMs = currentDelay
                    },
                    onSetAudioDelay = { target ->
                        currentDelay = AudioDelayMath.clampDelayMs(target)
                        appliedDelayMs = currentDelay
                    },
                    onResetAudioDelay = {
                        currentDelay = 0L
                        appliedDelayMs = 0L
                    },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        // 1. Coarse +5s stepper & unbounded "5m" input work cleanly
        composeRule.onNodeWithTag("tracks_audio_delay_plus_5s").performScrollTo().performClick()
        assertEquals(5_000L, appliedDelayMs)
        composeRule.onNodeWithTag("tracks_audio_delay_value").assertTextContains("+5.00 s")

        composeRule.onNodeWithTag("tracks_audio_delay_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("tracks_audio_delay_input").performTextInput("5m")
        composeRule.onNodeWithTag("tracks_audio_delay_apply").performScrollTo().performClick()
        assertEquals(300_000L, appliedDelayMs)
        composeRule.onNodeWithTag("tracks_audio_delay_value").assertTextContains("+5m 00s")

        // 2. Open Add Audio URL composer, reject local file:// path, then attach valid .m3u8 URL
        composeRule.onNodeWithTag("tracks_add_audio_url_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_external_audio_url_input").performScrollTo().performTextInput("file:///sdcard/a.mp3")
        composeRule.onNodeWithTag("tracks_external_audio_attach_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_external_audio_url_error").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("tracks_external_audio_url_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("tracks_external_audio_url_input").performTextInput("https://cdn.example.com/tamil.m3u8")
        composeRule.onNodeWithTag("tracks_external_audio_label_input").performScrollTo().performTextInput("Tamil Dub")
        composeRule.onNodeWithTag("tracks_external_audio_attach_button").performScrollTo().performClick()
        assertEquals("https://cdn.example.com/tamil.m3u8", loadedUrl)
        assertEquals("Tamil Dub", loadedLabel)

        // 3. Remove attached external audio track via inline X button
        composeRule.onNodeWithTag("tracks_remove_external_audio_ext99").performScrollTo().performClick()
        assertEquals("ext99", removedAudioId)
    }

    @Test
    fun localWebRemoteServer_dispatchesLoadAudioWithPinAndSchemeValidation() {
        var received: RemotePushCommand? = null
        val server = LocalWebRemoteServer(
            onCommandReceived = { cmd ->
                received = cmd
                "Attached audio URL"
            }
        )
        val info = server.start(context = null, preferredPort = 0)
        try {
            assertTrue(info.isRunning)
            val audioUrl = "https://cdn.example.com/dub/hindi.m3u8"
            val body = "pin=${info.pin}&action=load_audio&payload=${URLEncoder.encode(audioUrl, "UTF-8")}"
            val conn = (URL("http://127.0.0.1:${info.port}/push").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, conn.responseCode)
            assertEquals(RemotePushAction.LOAD_AUDIO, received?.action)
            assertEquals(audioUrl, received?.payload)

            // Reject non-HTTP file:// scheme
            val badBody = "pin=${info.pin}&action=load_audio&payload=${URLEncoder.encode("file:///sdcard/a.mp3", "UTF-8")}"
            val badConn = (URL("http://127.0.0.1:${info.port}/push").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            badConn.outputStream.use { it.write(badBody.toByteArray(Charsets.UTF_8)) }
            assertEquals(400, badConn.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun jsonPlaylistParser_extractsExternalAudiosAcrossMoviesCollectionsAndSeries() {
        val parser = JsonPlaylistParser(RuntimeEnvironment.getApplication(), OkHttpClient())
        val json = """
            {
              "title": "Audio Test Catalog",
              "items": [
                {
                  "id": "m1",
                  "type": "movie",
                  "title": "Multi Audio Movie",
                  "url": "https://cdn.example.com/movie/master.m3u8",
                  "audios": [
                    {
                      "url": "https://cdn.example.com/audio/hindi.m3u8",
                      "label": "Hindi Dub",
                      "language": "hi",
                      "default": true,
                      "delayMs": -1500
                    },
                    "https://cdn.example.com/audio/tamil.m3u8",
                    {
                      "url": "file:///sdcard/local.mp3",
                      "label": "Rejected Local"
                    },
                    {
                      "url": "https://cdn.example.com/audio/hindi.m3u8",
                      "label": "Duplicate Ignored"
                    }
                  ]
                },
                {
                  "id": "m2",
                  "type": "movie",
                  "title": "Shorthand Audio Movie",
                  "url": "https://cdn.example.com/movie2/master.m3u8",
                  "audioUrl": "https://cdn.example.com/audio/english.m3u8",
                  "audioLabel": "English Studio",
                  "audioDelayMs": 2500
                },
                {
                  "id": "col1",
                  "type": "collection",
                  "title": "Test Saga",
                  "movies": [
                    {
                      "id": "c1",
                      "part": 1,
                      "title": "Saga Part 1",
                      "url": "https://cdn.example.com/saga1.m3u8",
                      "audio": {
                        "url": "https://cdn.example.com/saga1_audio.m3u8",
                        "label": "Saga Hindi",
                        "default": true,
                        "delayMs": 3000
                      }
                    }
                  ]
                },
                {
                  "id": "s1",
                  "type": "series",
                  "title": "Audio Series",
                  "seasons": [
                    {
                      "season": 1,
                      "episodes": [
                        {
                          "id": "ep1",
                          "episode": 1,
                          "title": "Pilot",
                          "url": "https://cdn.example.com/ep1.m3u8",
                          "audioTracks": [
                            {
                              "url": "https://cdn.example.com/ep1_hi.m3u8",
                              "label": "Hindi",
                              "default": true
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parser.parseJson(json)
        assertTrue(result is JsonPlaylistLoadResult.Success)
        val items = (result as JsonPlaylistLoadResult.Success).items
        assertEquals(4, items.size)

        val m1 = items[0]
        assertEquals(2, m1.externalAudios.size)
        assertEquals("https://cdn.example.com/audio/hindi.m3u8", m1.externalAudios[0].url)
        assertEquals("Hindi Dub", m1.externalAudios[0].label)
        assertEquals(true, m1.externalAudios[0].isDefault)
        assertEquals(-1500L, m1.externalAudios[0].delayMs)
        assertEquals("https://cdn.example.com/audio/tamil.m3u8", m1.externalAudios[1].url)

        val m2 = items[1]
        assertEquals(1, m2.externalAudios.size)
        assertEquals("English Studio", m2.externalAudios[0].label)
        assertEquals(2500L, m2.externalAudios[0].delayMs)

        val colMovie = items[2].collectionMovies.first()
        assertEquals(1, colMovie.externalAudios.size)
        assertEquals("Saga Hindi", colMovie.externalAudios[0].label)
        assertEquals(3000L, colMovie.externalAudios[0].delayMs)

        val ep = items[3].seasons.first().episodes.first()
        assertEquals(1, ep.externalAudios.size)
        assertEquals("Hindi", ep.externalAudios[0].label)
    }

    @Test
    fun playlistAudioResolver_autoSelectsDefaultOrFirstAndRespectsAllDisabled() = runBlocking {
        // Explicit default on second entry wins along with its delayMs
        val bundle1 = PlaylistAudioResolver.resolve(
            specs = listOf(
                PlaylistAudioSpec(url = "https://cdn.example.com/a1.m3u8", label = "Track 1", isDefault = false),
                PlaylistAudioSpec(url = "https://cdn.example.com/a2.m3u8", label = "Hindi", isDefault = true, delayMs = -5000L)
            )
        )
        assertEquals(2, bundle1.tracks.size)
        assertEquals(bundle1.tracks[1].id, bundle1.autoSelectAudioId)
        assertEquals(-5000L, bundle1.initialDelayMs)

        // No default flag -> automatically selects first audio URL
        val bundle2 = PlaylistAudioResolver.resolve(
            specs = listOf(
                PlaylistAudioSpec(url = "https://cdn.example.com/hindi/index.m3u8", label = "Hindi HLS")
            )
        )
        assertEquals(1, bundle2.tracks.size)
        assertEquals(bundle2.tracks[0].id, bundle2.autoSelectAudioId)
        assertTrue(bundle2.tracks[0].isHls)

        // Explicitly default=false on all -> still loads track into Tracks sheet, keeps embedded selected
        val bundle3 = PlaylistAudioResolver.resolve(
            specs = listOf(
                PlaylistAudioSpec(url = "https://cdn.example.com/commentary.m3u8", label = "Commentary", isDefault = false)
            )
        )
        assertEquals(1, bundle3.tracks.size)
        assertNull(bundle3.autoSelectAudioId)
    }

    @Test
    fun egPlaylistV3File_parsesCleanlyWithExternalAudioTracks() {
        val candidates = listOf(
            File("eg-playlist-v3.json"),
            File("../eg-playlist-v3.json")
        )
        val playlistFile = candidates.firstOrNull { it.exists() }
        assertNotNull("eg-playlist-v3.json must exist in workspace root", playlistFile)

        val parser = JsonPlaylistParser(RuntimeEnvironment.getApplication(), OkHttpClient())
        val result = parser.parseJson(playlistFile!!.readText())
        assertTrue("eg-playlist-v3.json must parse cleanly", result is JsonPlaylistLoadResult.Success)
        val items = (result as JsonPlaylistLoadResult.Success).items

        val demoMovie = items.firstOrNull { it.id == "tears-of-steel-dual-audio" }
        assertNotNull(demoMovie)
        assertEquals(2, demoMovie!!.externalAudios.size)
        assertEquals(true, demoMovie.externalAudios[0].isDefault)

        val reCollection = items.firstOrNull { it.id == "resident-evil-collection" }
        assertNotNull(reCollection)
        assertTrue(reCollection!!.collectionMovies[0].externalAudios.isNotEmpty())
        assertTrue(reCollection.collectionMovies[1].externalAudios.isNotEmpty())

        val series = items.firstOrNull { it.id == "blender-anthology-series" }
        assertNotNull(series)
        assertTrue(series!!.seasons.first().episodes[0].externalAudios.isNotEmpty())
        assertTrue(series.seasons.first().episodes[1].externalAudios.isNotEmpty())
    }

    @Test
    fun version39MetadataIsSet() {
        assertEquals(39, BuildConfig.VERSION_CODE)
        assertEquals("1.39", BuildConfig.VERSION_NAME)
    }

    private class FakeDelegateMediaPeriod(
        private val groups: TrackGroupArray,
        private val sampleTimestampsUs: ArrayDeque<Long> = ArrayDeque()
    ) : MediaPeriod {
        var lastPreparedPosUs: Long = -1L
        var lastSeekPosUs: Long = -1L
        var lastContinueLoadingPosUs: Long = -1L
        var lastSelectedTrackGroup: TrackGroup? = null
        var mockBufferedPosUs: Long = 0L

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            lastPreparedPosUs = positionUs
            callback.onPrepared(this)
        }

        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = groups
        override fun getStreamKeys(trackSelections: MutableList<ExoTrackSelection>): MutableList<StreamKey> = mutableListOf()

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            selections.firstOrNull()?.let { lastSelectedTrackGroup = it.trackGroup }
            for (i in selections.indices) {
                if (selections[i] != null) {
                    streams[i] = object : SampleStream {
                        override fun isReady(): Boolean = true
                        override fun maybeThrowError() = Unit
                        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
                            val nextTs = sampleTimestampsUs.removeFirstOrNull() ?: return C.RESULT_NOTHING_READ
                            buffer.timeUs = nextTs
                            return C.RESULT_BUFFER_READ
                        }
                        override fun skipData(positionUs: Long): Int = 0
                    }
                }
            }
            return positionUs
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = 123_456L
        override fun seekToUs(positionUs: Long): Long {
            lastSeekPosUs = positionUs
            return positionUs
        }

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs
        override fun getBufferedPositionUs(): Long = mockBufferedPosUs
        override fun getNextLoadPositionUs(): Long = mockBufferedPosUs
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            lastContinueLoadingPosUs = loadingInfo.playbackPositionUs
            return true
        }

        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }
}
