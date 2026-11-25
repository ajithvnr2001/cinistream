package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import com.example.data.DeviceSyncCodec
import com.example.data.DeviceSyncSnapshot
import com.example.data.FavoriteItemEntity
import com.example.data.GlobalSearchEngine
import com.example.data.MediaHistoryEntity
import com.example.data.NetworkServerEntity
import com.example.data.OfflineDownloadEntity
import com.example.model.AbLoopState
import com.example.model.ChapterMarker
import com.example.model.ChannelEpgNowNext
import com.example.model.EpgProgram
import com.example.model.GlobalSearchCategory
import com.example.model.JsonPlaylistItem
import com.example.model.M3uEntry
import com.example.model.SeriesEpisode
import com.example.model.SleepTimerMode
import com.example.model.SleepTimerUiState
import com.example.model.StreamFormat
import com.example.model.SubtitleAppearance
import com.example.model.UpNextCountdownState
import com.example.player.LocalNetworkClient
import com.example.player.M3uInspectionResult
import com.example.player.M3uPlaylistParser
import com.example.player.NetworkBrowserItem
import com.example.player.OfflineDownloadManager
import com.example.player.SecondarySubtitleEngine
import com.example.player.SecondarySubtitleTrack
import com.example.player.SubtitleCue
import com.example.player.XmltvEpgParser
import com.example.remote.LocalWebRemoteServer
import com.example.remote.QrCodeMatrixGenerator
import com.example.remote.RemotePushAction
import com.example.remote.RemotePushCommand
import com.example.ui.player.ChannelZapEpgBanner
import com.example.ui.player.DownloadsSheet
import com.example.ui.player.DualSubtitleOverlay
import com.example.ui.player.Hold2xSpeedPill
import com.example.ui.player.LocalNetworkSheet
import com.example.ui.player.SkipIntroButton
import com.example.ui.player.SubtitleSheet
import com.example.ui.player.TracksSheet
import com.example.ui.player.UpNextCountdownCard
import com.example.ui.remote.WebRemoteDialog
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.ChannelZapBannerState
import com.example.viewmodel.LocalNetworkUiState
import com.example.viewmodel.SecondarySubtitleUiState
import com.example.viewmodel.WebRemoteUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CineStreamV24FeaturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    // -------------------------------------------------------------------------
    // 1. QR Code Generator & Local Wi-Fi Web Remote Security Hardening
    // -------------------------------------------------------------------------

    @Test
    fun qrCodeMatrixGenerator_producesValidSquareMatrixWithFinderPatterns() {
        val url = "http://192.168.1.42:8765/?pin=482910"
        val matrix = QrCodeMatrixGenerator.generate(url)
        assertTrue(matrix.size >= 25)
        assertEquals(matrix.size, matrix[0].size)

        // Top-left 7x7 finder pattern outer border & center 3x3 must be dark
        assertTrue(matrix[0][0])
        assertTrue(matrix[0][6])
        assertTrue(matrix[6][0])
        assertTrue(matrix[3][3])
        assertFalse(matrix[1][1])

        // Top-right and bottom-left finder centers must be dark
        val n = matrix.size
        assertTrue(matrix[3][n - 4])
        assertTrue(matrix[n - 4][3])
    }

    @Test
    fun localWebRemoteServer_enforcesPinValidationUrlAllowlistHtmlEscapingAndSyncExport() {
        val received = mutableListOf<RemotePushCommand>()
        val server = LocalWebRemoteServer(
            onCommandReceived = { cmd ->
                received.add(cmd)
                "Queued <script>alert(1)</script> ok"
            },
            onExportSyncJson = { """{"schemaVersion":1,"favorites":[]}""" }
        )
        val info = server.start(context = null, preferredPort = 0)
        try {
            assertTrue(info.isRunning)
            assertEquals(6, info.pin.length)
            val base = "http://127.0.0.1:${info.port}"

            // 1. GET / serves Web Remote HTML
            val getConn = (URL("$base/?pin=${info.pin}").openConnection() as HttpURLConnection)
            assertEquals(200, getConn.responseCode)
            val html = getConn.inputStream.bufferedReader().readText()
            assertTrue(html.contains("CineStream TV Web Remote"))
            assertTrue(html.contains(info.pin))

            // 2. GET /api/sync/export rejects bad PIN (403) and succeeds with valid PIN (200)
            val badExport = (URL("$base/api/sync/export?pin=000000").openConnection() as HttpURLConnection)
            assertEquals(403, badExport.responseCode)

            val goodExport = (URL("$base/api/sync/export?pin=${info.pin}").openConnection() as HttpURLConnection)
            assertEquals(200, goodExport.responseCode)
            val exported = goodExport.inputStream.bufferedReader().readText()
            assertTrue(exported.contains("\"schemaVersion\":1"))

            // 3. POST /push with wrong PIN -> 403
            val wrongPinCode = postForm(
                "$base/push",
                mapOf("pin" to "999999", "action" to "play_stream", "payload" to "https://cdn.example/video.m3u8")
            ).first
            assertEquals(403, wrongPinCode)
            assertTrue(received.isEmpty())

            // 4. POST /push with javascript: URL -> 400 rejected
            val badSchemeCode = postForm(
                "$base/push",
                mapOf("pin" to info.pin, "action" to "play_stream", "payload" to "javascript:alert(1)")
            ).first
            assertEquals(400, badSchemeCode)
            assertTrue(received.isEmpty())

            // 5. POST /push with valid PIN + https URL -> 200 and HTML-escapes response message
            val (okCode, okBody) = postForm(
                "$base/push",
                mapOf("pin" to info.pin, "action" to "play_stream", "payload" to "https://cdn.example/movie.m3u8")
            )
            assertEquals(200, okCode)
            assertEquals(1, received.size)
            assertEquals(RemotePushAction.PLAY_STREAM, received[0].action)
            assertEquals("https://cdn.example/movie.m3u8", received[0].payload)
            assertFalse(okBody.contains("<script>alert(1)</script>"))
            assertTrue(okBody.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        } finally {
            server.stop()
        }
    }

    private fun postForm(url: String, params: Map<String, String>): Pair<Int, String> {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return code to text
    }

    // -------------------------------------------------------------------------
    // 2. Local Wi-Fi Sync & Backup Snapshot Codec
    // -------------------------------------------------------------------------

    @Test
    fun deviceSyncCodec_roundTripsAndExcludesLocalFilesAndCredentials() {
        val snapshot = DeviceSyncSnapshot(
            jsonPlaylistUrls = listOf("https://catalog.example/movies.json"),
            epgUrl = "https://epg.example/guide.xml.gz",
            preferredAudioLanguage = "ta",
            preferredSubtitleLanguage = "en",
            seekIntervalSeconds = 15,
            nightModeAudioEnabled = true,
            autoPlayUpNextEnabled = true,
            favorites = listOf(
                FavoriteItemEntity(
                    id = 1L,
                    mediaKey = "json:dune2",
                    title = "Dune: Part Two",
                    sourceUri = "https://cdn.example/dune2.m3u8",
                    itemType = "MOVIE",
                    formatName = "HLS",
                    imdbId = "tt15239678",
                    imdbRating = "8.6"
                )
            ),
            history = listOf(
                MediaHistoryEntity(
                    id = 10L,
                    title = "Remote Film",
                    sourceUri = "https://cdn.example/remote.m3u8",
                    formatName = "HLS",
                    isLocalFile = false,
                    lastPositionMs = 450_000L,
                    durationMs = 3_600_000L
                ),
                MediaHistoryEntity(
                    id = 11L,
                    title = "Private Local File",
                    sourceUri = "content://com.android.providers/doc/1",
                    formatName = "MKV",
                    isLocalFile = true,
                    lastPositionMs = 120_000L,
                    durationMs = 900_000L
                )
            )
        )

        val json = DeviceSyncCodec.encode(snapshot)
        assertFalse(json.contains("nordlynx"))
        assertFalse(json.contains("password"))

        val decoded = DeviceSyncCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(listOf("https://catalog.example/movies.json"), decoded!!.jsonPlaylistUrls)
        assertEquals("https://epg.example/guide.xml.gz", decoded.epgUrl)
        assertEquals("ta", decoded.preferredAudioLanguage)
        assertEquals("en", decoded.preferredSubtitleLanguage)
        assertEquals(15, decoded.seekIntervalSeconds)
        assertTrue(decoded.nightModeAudioEnabled)
        assertEquals(1, decoded.favorites.size)
        assertEquals("Dune: Part Two", decoded.favorites[0].title)
        // Local file history must be excluded from cross-device sync
        assertEquals(1, decoded.history.size)
        assertEquals("Remote Film", decoded.history[0].title)
        assertEquals(450_000L, decoded.history[0].lastPositionMs)
    }

    // -------------------------------------------------------------------------
    // 3. Dual Subtitles Engine (SRT/VTT Parser & Cue Sync)
    // -------------------------------------------------------------------------

    @Test
    fun secondarySubtitleEngine_parsesSrtAndVttStripsTagsAndFindsCueWithOffset() {
        val srt = """
            1
            00:00:02,000 --> 00:00:05,500
            <i>Hello</i> {\\an8}world!

            2
            00:00:10,000 --> 00:00:13,000
            Second line
        """.trimIndent()

        val cues = SecondarySubtitleEngine.parseSrtOrVtt(srt)
        assertEquals(2, cues.size)
        assertEquals("Hello world!", cues[0].text)
        assertEquals("Hello world!", SecondarySubtitleEngine.findActiveCueText(cues, 3_000L))
        assertNull(SecondarySubtitleEngine.findActiveCueText(cues, 7_000L))
        // Offset +1000ms delays subtitle by 1s, so at position 11_500ms -> adjusted 10_500ms matches cue 2
        assertEquals("Second line", SecondarySubtitleEngine.findActiveCueText(cues, 11_500L, offsetMs = 1_000L))

        val vtt = """
            WEBVTT

            00:01.000 --> 00:03.200
            <b>Bonjour</b>
        """.trimIndent()
        val vttCues = SecondarySubtitleEngine.parseSrtOrVtt(vtt)
        assertEquals(1, vttCues.size)
        assertEquals("Bonjour", vttCues[0].text)
    }

    // -------------------------------------------------------------------------
    // 4. Live TV M3U EPG Tag Extraction & XMLTV Parser
    // -------------------------------------------------------------------------

    @Test
    fun m3uAndXmltvEpgParser_extractsTvgAttributesAndResolvesNowNext() {
        val m3u = """
            #EXTM3U url-tvg="https://epg.example/india.xml.gz"
            #EXTINF:-1 tvg-id="sun.tv.in" tvg-logo="https://img.example/suntv.png" group-title="Tamil",Sun TV HD
            https://live.example/suntv.m3u8
            #EXTINF:-1 tvg-id="star.sports.1" group-title="Sports",Star Sports 1
            https://live.example/ss1.m3u8
        """.trimIndent()

        val parser = M3uPlaylistParser(ApplicationProvider.getApplicationContext(), OkHttpClient())
        val inspected = parser.parseContent(m3u)
        assertTrue(inspected is M3uInspectionResult.ChannelPlaylist)
        val playlist = inspected as M3uInspectionResult.ChannelPlaylist
        assertEquals("https://epg.example/india.xml.gz", playlist.epgUrl)
        assertEquals(2, playlist.entries.size)
        assertEquals("sun.tv.in", playlist.entries[0].tvgId)
        assertEquals("https://img.example/suntv.png", playlist.entries[0].tvgLogo)

        val nowMs = XmltvEpgParser.parseXmltvTimeMs("20261124123000 +0000")
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="sun.tv.in">
                <display-name>Sun TV HD</display-name>
              </channel>
              <programme start="20261124120000 +0000" stop="20261124130000 +0000" channel="sun.tv.in">
                <title>Afternoon Cinema</title>
                <desc>Blockbuster Tamil feature</desc>
              </programme>
              <programme start="20261124130000 +0000" stop="20261124140000 +0000" channel="sun.tv.in">
                <title>Evening News</title>
              </programme>
            </tv>
        """.trimIndent()

        val index = XmltvEpgParser.parseXmlString(xml, nowMs = nowMs)
        val nowNext = XmltvEpgParser.resolveNowNext(index, playlist.entries[0], nowMs = nowMs)
        assertNotNull(nowNext)
        assertEquals("Afternoon Cinema", nowNext!!.now?.title)
        assertEquals("Evening News", nowNext.next?.title)
        assertEquals(0.5f, nowNext.now!!.progressFraction(nowMs), 0.02f)
    }

    // -------------------------------------------------------------------------
    // 5. Offline Download Helpers & Local Network (WebDAV + Jellyfin) Parsers
    // -------------------------------------------------------------------------

    @Test
    fun offlineDownloadManager_picksBest1080pHlsVariantAndResolvesRelativeUrls() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480
            480p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=18000000,RESOLUTION=3840x2160
            4k/index.m3u8
        """.trimIndent()

        val chosen = OfflineDownloadManager.pickBestHlsVariantUrl(
            "https://cdn.example/movie/master.m3u8",
            master
        )
        assertEquals("https://cdn.example/movie/1080p/index.m3u8", chosen)
        assertEquals(
            "https://cdn.example/movie/1080p/seg01.ts",
            OfflineDownloadManager.resolveRelativeUrl("https://cdn.example/movie/1080p/index.m3u8", "seg01.ts")
        )
        assertEquals("7_Dune_Part_Two", OfflineDownloadManager.sanitizeFolderName("Dune: Part Two!", 7L))
    }

    @Test
    fun localNetworkClient_parsesWebDavPropfindAndJellyfinItems() {
        val auth = LocalNetworkClient.buildBasicAuthHeader("admin", "secret")
        assertNotNull(auth)
        assertTrue(auth!!.startsWith("Basic "))

        val webdavXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/movies/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/movies/SciFi/</D:href>
                <D:propstat><D:prop><D:displayname>SciFi</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/movies/Interstellar.mkv</D:href>
                <D:propstat><D:prop><D:displayname>Interstellar.mkv</D:displayname><D:getcontentlength>4200000000</D:getcontentlength></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/movies/readme.txt</D:href>
                <D:propstat><D:prop><D:displayname>readme.txt</D:displayname></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val davItems = LocalNetworkClient.parseWebDavPropfindXml(
            xml = webdavXml,
            currentDirUrl = "http://192.168.1.10:8080/movies",
            authHeader = auth
        )
        // Self folder and non-media readme.txt must be filtered out; folder sorted before file
        assertEquals(2, davItems.size)
        assertTrue(davItems[0].isFolder)
        assertEquals("SciFi", davItems[0].name)
        assertFalse(davItems[1].isFolder)
        assertEquals("Interstellar.mkv", davItems[1].name)
        assertEquals(StreamFormat.MKV, davItems[1].format)
        assertEquals(auth, davItems[1].requestHeaders["Authorization"])

        val jellyfinJson = """
            {
              "Items": [
                {"Id": "lib1", "Name": "Movies", "IsFolder": true, "Type": "CollectionFolder"},
                {"Id": "mov9", "Name": "Blade Runner 2049", "IsFolder": false, "Type": "Movie"}
              ]
            }
        """.trimIndent()
        val jfItems = LocalNetworkClient.parseJellyfinItemsJson(jellyfinJson, "http://192.168.1.20:8096", "jf-key-123")
        assertEquals(2, jfItems.size)
        assertTrue(jfItems[0].isFolder)
        assertEquals("Blade Runner 2049", jfItems[1].name)
        assertTrue(jfItems[1].playbackUrl.contains("/Videos/mov9/stream?static=true"))
        assertEquals("jf-key-123", jfItems[1].requestHeaders["X-Emby-Token"])
    }

    // -------------------------------------------------------------------------
    // 6. Global Search, A-B Loop & Sleep Timer State Models
    // -------------------------------------------------------------------------

    @Test
    fun globalSearchEngine_searchesAcrossAllFiveSources() {
        val results = GlobalSearchEngine.search(
            query = "matrix",
            favorites = listOf(
                FavoriteItemEntity(
                    id = 1L,
                    mediaKey = "fav1",
                    title = "The Matrix Reloaded",
                    sourceUri = "https://cdn.example/matrix2.m3u8",
                    itemType = "MOVIE"
                )
            ),
            jsonItems = listOf(
                JsonPlaylistItem(
                    id = "m1",
                    title = "The Matrix",
                    url = "https://cdn.example/matrix1.m3u8",
                    groupTitle = "Sci-Fi",
                    releaseYear = "1999"
                )
            ),
            m3uEntries = listOf(
                M3uEntry(
                    id = "ch1",
                    title = "Matrix Action TV",
                    url = "https://live.example/matrix.m3u8",
                    groupTitle = "Movies"
                )
            ),
            downloads = listOf(
                OfflineDownloadEntity(
                    id = 5L,
                    sourceUrl = "https://cdn.example/matrix3.mp4",
                    title = "Matrix Revolutions Offline",
                    localFilePath = "/data/offline/matrix3.mp4",
                    status = "COMPLETED"
                )
            ),
            history = listOf(
                MediaHistoryEntity(
                    id = 9L,
                    title = "Matrix Animatrix",
                    sourceUri = "https://cdn.example/animatrix.mp4",
                    formatName = "MP4"
                )
            )
        )

        assertEquals(5, results.size)
        assertEquals(GlobalSearchCategory.FAVORITE, results[0].category)
        assertEquals(GlobalSearchCategory.CATALOG, results[1].category)
        assertEquals(GlobalSearchCategory.M3U, results[2].category)
        assertEquals(GlobalSearchCategory.DOWNLOAD, results[3].category)
        assertEquals(GlobalSearchCategory.HISTORY, results[4].category)
    }

    @Test
    fun abLoopAndSleepTimerModels_reportActiveStatesAndCompactBadges() {
        assertFalse(AbLoopState(startMs = 10_000L, endMs = null).isActive)
        assertTrue(AbLoopState(startMs = 10_000L, endMs = null).hasPointAOnly)
        assertFalse(AbLoopState(startMs = 10_000L, endMs = 10_200L).isActive)
        assertTrue(AbLoopState(startMs = 10_000L, endMs = 25_000L).isActive)

        assertNull(SleepTimerUiState(SleepTimerMode.OFF).compactBadge())
        assertEquals("Sleep: Ep End", SleepTimerUiState(SleepTimerMode.END_OF_EPISODE).compactBadge())
        assertEquals("Sleep 15m", SleepTimerUiState(SleepTimerMode.MIN_15, remainingMs = 900_000L).compactBadge())
        assertEquals("Sleep 45s", SleepTimerUiState(SleepTimerMode.MIN_15, remainingMs = 45_000L).compactBadge())
    }

    // -------------------------------------------------------------------------
    // 7. Robolectric Compose UI Verification for All v1.24 Overlays & Sheets
    // -------------------------------------------------------------------------

    @Test
    fun webRemoteDialog_rendersQrUrlPinAndSyncBackupButtons() {
        var started = false
        var exported = false
        var imported = false

        composeRule.setContent {
            CineStreamTheme {
                WebRemoteDialog(
                    state = WebRemoteUiState(
                        isRunning = true,
                        serverUrl = "http://192.168.1.50:8765/?pin=654321",
                        pin = "654321",
                        lastReceivedAction = "Play Video"
                    ),
                    onStartServer = { started = true },
                    onStopServer = {},
                    onExportSyncBackup = { exported = true },
                    onImportSyncBackup = { imported = true },
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        assertTrue(started)
        composeRule.onNodeWithTag("web_remote_qr_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("web_remote_url_text").assertTextContains("192.168.1.50:8765", substring = true)
        composeRule.onNodeWithTag("web_remote_pin_text").assertTextContains("654321")
        composeRule.onNodeWithTag("sync_export_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("sync_import_button").performScrollTo().performClick()
        assertTrue(exported)
        assertTrue(imported)
    }

    @Test
    fun playerV24Overlays_renderUpNextSkipIntroHold2xDualSubsAndEpgZapBanner() {
        var playNowClicked = false
        var cancelClicked = false
        var skipIntroClicked = false

        val series = JsonPlaylistItem(id = "s1", title = "Andor", url = "https://cdn.example/s1.m3u8")
        val nextEp = SeriesEpisode(
            id = "ep2",
            title = "That Would Be Me",
            episodeNumber = 2,
            seasonNumber = 1,
            url = "https://cdn.example/ep2.m3u8"
        )
        val now = System.currentTimeMillis()
        val epgBanner = ChannelZapBannerState(
            channelTitle = "Sun TV HD",
            groupTitle = "Tamil",
            channelNumber = 1,
            totalChannels = 40,
            epgNowNext = ChannelEpgNowNext(
                now = EpgProgram("suntv", "Prime Movie", "", now - 600_000L, now + 600_000L),
                next = EpgProgram("suntv", "Late News", "", now + 600_000L, now + 3_600_000L)
            )
        )

        composeRule.setContent {
            CineStreamTheme {
                androidx.compose.foundation.layout.Column {
                    UpNextCountdownCard(
                        state = UpNextCountdownState(series, nextEp, secondsRemaining = 7, totalSeconds = 10),
                        onPlayNow = { playNowClicked = true },
                        onCancel = { cancelClicked = true }
                    )
                    SkipIntroButton(onClick = { skipIntroClicked = true })
                    Hold2xSpeedPill()
                    DualSubtitleOverlay(text = "Secondary Tamil Caption")
                    ChannelZapEpgBanner(banner = epgBanner)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("up_next_countdown_card").assertIsDisplayed()
        composeRule.onNodeWithTag("up_next_play_now_button").performClick()
        composeRule.onNodeWithTag("up_next_cancel_button").performClick()
        composeRule.onNodeWithTag("skip_intro_button").performClick()
        composeRule.onNodeWithTag("hold_2x_speed_pill").assertIsDisplayed()
        composeRule.onNodeWithTag("dual_subtitle_overlay").assertIsDisplayed()
        composeRule.onNodeWithText("Secondary Tamil Caption").assertIsDisplayed()
        composeRule.onNodeWithTag("channel_zap_epg_banner").assertIsDisplayed()
        composeRule.onNodeWithText("NOW: Prime Movie").assertIsDisplayed()

        assertTrue(playNowClicked)
        assertTrue(cancelClicked)
        assertTrue(skipIntroClicked)
    }

    @Test
    fun tracksSheetV24_controlsNightModeSleepTimerSkipIntervalAndAbLoop() {
        var nightMode by mutableStateOf(false)
        var sleepMode by mutableStateOf(SleepTimerMode.OFF)
        var skipSec by mutableIntStateOf(10)
        var setAClicked = false
        var jumpedChapter: ChapterMarker? = null
        val chapters = listOf(ChapterMarker("Opening", 0L), ChapterMarker("Act II", 1_200_000L))

        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    nightModeAudioEnabled = nightMode,
                    onToggleNightModeAudio = { nightMode = it },
                    sleepTimerState = SleepTimerUiState(sleepMode),
                    onSelectSleepTimer = { sleepMode = it },
                    seekIntervalSeconds = skipSec,
                    onSelectSeekInterval = { skipSec = it },
                    abLoopState = AbLoopState(),
                    onSetAbLoopPointA = { setAClicked = true },
                    chapters = chapters,
                    onJumpToChapter = { jumpedChapter = it },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tracks_night_mode_switch").performScrollTo().performClick()
        assertTrue(nightMode)

        // Scroll outer vertical container so the horizontally-scrollable Sleep Timer row is in viewport
        composeRule.onNodeWithTag("skip_interval_chip_15").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sleep_timer_chip_min_30").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(SleepTimerMode.MIN_30, sleepMode)

        composeRule.onNodeWithTag("skip_interval_chip_15").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(15, skipSec)

        // Scroll outer vertical container so the entire A-B Loop & Chapters card is in viewport
        composeRule.onNodeWithTag("tracks_ab_loop_section").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ab_loop_set_a_button").performScrollTo().performClick()
        assertTrue(setAClicked)

        composeRule.onNodeWithTag("chapter_chip_1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("Act II", jumpedChapter?.title)
    }

    @Test
    fun subtitleSheetV24_displaysDualSubtitlesControls() {
        var clearedDual = false

        composeRule.setContent {
            CineStreamTheme {
                SubtitleSheet(
                    subtitleTracks = emptyList(),
                    subtitlesDisabled = false,
                    hasExternalSubtitles = false,
                    subtitleOffsetMs = 0L,
                    appearance = SubtitleAppearance(),
                    onDismiss = {},
                    onPickSubtitleFile = {},
                    onLoadSubtitleUrl = {},
                    onSelectTrack = {},
                    onDisableSubtitles = {},
                    onAdjustOffset = {},
                    onResetOffset = {},
                    onUpdateTextSize = {},
                    onUpdateColor = {},
                    onUpdateBg = {},
                    secondarySubtitleState = SecondarySubtitleUiState(
                        activeTrack = SecondarySubtitleTrack(
                            label = "tamil.srt",
                            sourceUri = "file:///tamil.srt",
                            cues = listOf(SubtitleCue(0L, 2_000L, "Vanakkam"))
                        )
                    ),
                    onClearSecondarySubtitle = { clearedDual = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dual_subtitle_clear_button").performScrollTo().performClick()
        assertTrue(clearedDual)
    }

    @Test
    fun downloadsAndLocalNetworkSheets_renderItemsAndTriggerActions() {
        var playedDownloadId = -1L
        val dl = OfflineDownloadEntity(
            id = 7L,
            sourceUrl = "https://cdn.example/offline.mp4",
            title = "Offline Sci-Fi Film",
            formatName = "MP4",
            localFilePath = "/data/offline/media.mp4",
            status = OfflineDownloadManager.STATUS_COMPLETED,
            progressPercent = 100,
            bytesDownloaded = 150L * 1024L * 1024L,
            totalBytes = 150L * 1024L * 1024L
        )

        composeRule.setContent {
            CineStreamTheme {
                DownloadsSheet(
                    downloads = listOf(dl),
                    onPlayDownload = { playedDownloadId = it.id },
                    onCancelDownload = {},
                    onDeleteDownload = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("download_item_7").assertIsDisplayed()
        composeRule.onNodeWithTag("download_play_7").performClick()
        assertEquals(7L, playedDownloadId)
    }

    @Test
    fun localNetworkSheet_displaysSavedServerAndBrowserItems() {
        var browsedItemName = ""
        val server = NetworkServerEntity(
            id = 3L,
            name = "Home NAS",
            serverType = LocalNetworkClient.TYPE_WEBDAV,
            baseUrl = "http://192.168.1.10:8080/media"
        )
        val item = NetworkBrowserItem(
            id = "http://192.168.1.10:8080/media/Movie.mkv",
            name = "Movie.mkv",
            isFolder = false,
            pathOrParentId = "http://192.168.1.10:8080/media/Movie.mkv",
            playbackUrl = "http://192.168.1.10:8080/media/Movie.mkv",
            format = StreamFormat.MKV
        )

        composeRule.setContent {
            CineStreamTheme {
                LocalNetworkSheet(
                    servers = listOf(server),
                    browserState = LocalNetworkUiState(
                        activeServer = server,
                        breadcrumbPath = listOf("Home NAS" to ""),
                        items = listOf(item)
                    ),
                    onAddServer = { _, _, _, _, _ -> },
                    onDeleteServer = {},
                    onOpenServer = {},
                    onBrowseItem = { browsedItemName = it.name },
                    onNavigateUp = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Movie.mkv").assertIsDisplayed()
        composeRule.onNodeWithTag("network_browser_item_${item.id.hashCode()}").performClick()
        assertEquals("Movie.mkv", browsedItemName)
    }
}
