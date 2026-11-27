package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.NetworkServerEntity
import com.example.data.OfflineDownloadEntity
import com.example.model.StreamFormat
import com.example.player.DownloadQualityOption
import com.example.player.LocalNetworkClient
import com.example.player.NetworkBrowserItem
import com.example.player.OfflineDownloadManager
import com.example.ui.player.DownloadQualitySheet
import com.example.ui.player.DownloadsSheet
import com.example.ui.player.LocalNetworkSheet
import com.example.ui.player.PlayerTopBar
import com.example.ui.player.TracksSheet
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.LocalNetworkUiState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MobilePlayerDownloadTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mobilePlayerTopBar_showsDownloadButtonInside360dpPortraitWithoutClipping_andTracksStates() {
        var downloadTriggered = 0
        var openDownloadsTriggered = 0
        var downloadState by mutableStateOf<OfflineDownloadEntity?>(null)

        composeRule.setContent {
            CineStreamTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    PlayerTopBar(
                        title = "Dune Part Two (2024) 4K HDR10+ Atmos",
                        format = StreamFormat.HLS,
                        videoHeight = 2160,
                        hasActiveSubtitles = true,
                        statsForNerdsEnabled = false,
                        hasSeriesEpisodes = true,
                        episodeCodeBadge = "S01E01",
                        hasJsonPlaylist = true,
                        hasM3uPlaylist = true,
                        isTv = false,
                        tvOverscanHorizontal = 12.dp,
                        tvOverscanVertical = 10.dp,
                        subButtonFocusRequester = remember { FocusRequester() },
                        statsButtonFocusRequester = remember { FocusRequester() },
                        episodesButtonFocusRequester = remember { FocusRequester() },
                        jsonButtonFocusRequester = remember { FocusRequester() },
                        m3uButtonFocusRequester = remember { FocusRequester() },
                        tracksButtonFocusRequester = remember { FocusRequester() },
                        onBack = {},
                        onOpenSubtitles = {},
                        onToggleStatsForNerds = {},
                        onOpenSeriesEpisodes = {},
                        onOpenJsonCatalog = {},
                        onOpenM3uQueue = {},
                        onOpenTracks = {},
                        onToggleBackgroundAudio = {},
                        onEnterPip = {},
                        onLockControls = {},
                        isFavorited = false,
                        onToggleFavorite = {},
                        canDownload = true,
                        activeDownload = downloadState,
                        onDownloadStream = { downloadTriggered++ },
                        onOpenDownloads = { openDownloadsTriggered++ }
                    )
                }
            }
        }

        // 1. Default state: Download pill sits right next to Sub inside 360dp width without horizontal clipping
        composeRule.onNodeWithTag("player_mobile_action_strip").assertIsDisplayed()
        val subBounds = composeRule.onNodeWithTag("player_subtitles_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        val downloadNode = composeRule.onNodeWithTag("player_download_button").assertIsDisplayed()
        val dlBounds = downloadNode.getUnclippedBoundsInRoot()
        assertTrue(
            "Download pill (${dlBounds.left}) should sit to the right of Sub (${subBounds.right})",
            dlBounds.left >= subBounds.right
        )
        assertTrue(
            "Download pill right edge (${dlBounds.right}) must fit within 360dp portrait width without clipping",
            dlBounds.right <= 360.dp
        )
        downloadNode.assertTextContains("Download", substring = true)
        downloadNode.performClick()
        assertEquals(1, downloadTriggered)
        assertEquals(0, openDownloadsTriggered)

        // 2. Active downloading progress state -> shows 45% and opens Downloads sheet on tap
        composeRule.runOnIdle {
            downloadState = OfflineDownloadEntity(
                id = 7L,
                title = "Dune Part Two",
                sourceUrl = "https://cdn.example/dune/master.m3u8",
                formatName = StreamFormat.HLS.name,
                localFilePath = "",
                status = OfflineDownloadManager.STATUS_DOWNLOADING,
                progressPercent = 45,
                bytesDownloaded = 150_000_000L,
                totalBytes = 330_000_000L
            )
        }
        composeRule.onNodeWithTag("player_download_button")
            .assertIsDisplayed()
            .assertTextContains("45%", substring = true)
            .performClick()
        assertEquals(1, openDownloadsTriggered)

        // 3. Completed state -> shows Saved and opens Downloads sheet on tap
        composeRule.runOnIdle {
            downloadState = downloadState!!.copy(
                status = OfflineDownloadManager.STATUS_COMPLETED,
                progressPercent = 100
            )
        }
        composeRule.onNodeWithTag("player_download_button")
            .assertIsDisplayed()
            .assertTextContains("Saved", substring = true)
            .performClick()
        assertEquals(2, openDownloadsTriggered)

        // 4. Paused state -> shows Paused 45% and resumes on tap
        composeRule.runOnIdle {
            downloadState = downloadState!!.copy(
                status = OfflineDownloadManager.STATUS_PAUSED,
                progressPercent = 45,
                errorMessage = "Paused · Waiting for network"
            )
        }
        composeRule.onNodeWithTag("player_download_button")
            .assertIsDisplayed()
            .assertTextContains("Paused 45%", substring = true)
            .performClick()
        assertEquals(2, downloadTriggered)

        // 5. Failed state -> shows Resume and resumes download on tap
        composeRule.runOnIdle {
            downloadState = downloadState!!.copy(
                status = OfflineDownloadManager.STATUS_FAILED,
                errorMessage = "Network reset"
            )
        }
        composeRule.onNodeWithTag("player_download_button")
            .assertIsDisplayed()
            .assertTextContains("Resume", substring = true)
            .performClick()
        assertEquals(3, downloadTriggered)
    }

    @Test
    fun playerTopBar_hidesDownloadButtonOnTvOrWhenLocalFile() {
        var tvMode by mutableStateOf(true)
        var canDownload by mutableStateOf(true)

        composeRule.setContent {
            CineStreamTheme {
                PlayerTopBar(
                    title = "Local Movie",
                    format = StreamFormat.MKV,
                    videoHeight = 1080,
                    hasActiveSubtitles = false,
                    statsForNerdsEnabled = false,
                    hasSeriesEpisodes = false,
                    episodeCodeBadge = null,
                    hasJsonPlaylist = false,
                    hasM3uPlaylist = false,
                    isTv = tvMode,
                    tvOverscanHorizontal = 12.dp,
                    tvOverscanVertical = 10.dp,
                    subButtonFocusRequester = remember { FocusRequester() },
                    statsButtonFocusRequester = remember { FocusRequester() },
                    episodesButtonFocusRequester = remember { FocusRequester() },
                    jsonButtonFocusRequester = remember { FocusRequester() },
                    m3uButtonFocusRequester = remember { FocusRequester() },
                    tracksButtonFocusRequester = remember { FocusRequester() },
                    onBack = {},
                    onOpenSubtitles = {},
                    onToggleStatsForNerds = {},
                    onOpenSeriesEpisodes = {},
                    onOpenJsonCatalog = {},
                    onOpenM3uQueue = {},
                    onOpenTracks = {},
                    onLockControls = {},
                    canDownload = canDownload,
                    onDownloadStream = {}
                )
            }
        }

        // Hidden on TV
        composeRule.onNodeWithTag("player_download_button").assertDoesNotExist()

        // Hidden on mobile when playing a local file (canDownload = false)
        composeRule.runOnIdle {
            tvMode = false
            canDownload = false
        }
        composeRule.onNodeWithTag("player_download_button").assertDoesNotExist()
    }

    @Test
    fun tracksSheet_andDownloadsSheet_showLiveDownloadSpeedAndControls() {
        var startCalls = 0
        var cancelledId: Long? = null
        var openCalls = 0
        var activeDownload by mutableStateOf<OfflineDownloadEntity?>(null)

        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    isTv = false,
                    canDownload = true,
                    activeDownload = activeDownload,
                    activeDownloadSpeedBps = 18_400_000L, // ~17.5 MB/s
                    onDownloadStream = { startCalls++ },
                    onCancelDownload = { cancelledId = it },
                    onOpenDownloads = { openCalls++ },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        // Idle -> clicking card starts download
        composeRule.onNodeWithTag("tracks_offline_download_section")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Save for Offline Playback", substring = true)
            .performClick()
        assertEquals(1, startCalls)

        // Downloading -> displays live speed + cancel button + queue click
        composeRule.runOnIdle {
            activeDownload = OfflineDownloadEntity(
                id = 42L,
                title = "Interstellar",
                sourceUrl = "https://cdn.example/interstellar.mp4",
                formatName = StreamFormat.MP4.name,
                localFilePath = "",
                status = OfflineDownloadManager.STATUS_DOWNLOADING,
                progressPercent = 62,
                bytesDownloaded = 200_000_000L,
                totalBytes = 320_000_000L
            )
        }
        composeRule.onNodeWithTag("tracks_offline_download_section")
            .performScrollTo()
            .assertTextContains("Downloading (62%)", substring = true)
            .assertTextContains("MB/s", substring = true)
        composeRule.onNodeWithTag("tracks_download_cancel_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(42L, cancelledId)

        composeRule.onNodeWithTag("tracks_offline_download_section").performClick()
        assertEquals(1, openCalls)
    }

    @Test
    fun downloadsSheet_displaysLiveSpeedForActiveDownload() {
        val dl = OfflineDownloadEntity(
            id = 55L,
            title = "Blade Runner 2049",
            sourceUrl = "https://cdn.example/br2049/master.m3u8",
            formatName = StreamFormat.HLS.name,
            localFilePath = "",
            status = OfflineDownloadManager.STATUS_DOWNLOADING,
            progressPercent = 34,
            bytesDownloaded = 95_000_000L,
            totalBytes = 0L
        )
        composeRule.setContent {
            CineStreamTheme {
                DownloadsSheet(
                    downloads = listOf(dl),
                    downloadSpeeds = mapOf(55L to (15L * 1024L * 1024L)), // 15.0 MB/s
                    onPlayDownload = {},
                    onCancelDownload = {},
                    onDeleteDownload = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag("download_item_55").assertIsDisplayed()
        composeRule.onNodeWithText("15.0 MB/s", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("34%", substring = true).assertIsDisplayed()
    }

    @Test
    fun localNetworkSheet_showsDownloadActionForPlayableWebDavMediaAndPreservesAuthHeaders() {
        var downloadedItem: NetworkBrowserItem? = null
        val server = NetworkServerEntity(
            id = 9L,
            name = "TorBox Cloud",
            serverType = LocalNetworkClient.TYPE_WEBDAV,
            baseUrl = "https://webdav.torbox.app",
            usernameEnc = "",
            secretEnc = ""
        )
        val folder = NetworkBrowserItem(
            id = "folder_1",
            name = "Movies",
            isFolder = true,
            pathOrParentId = "https://webdav.torbox.app/Movies/"
        )
        val video = NetworkBrowserItem(
            id = "video_1",
            name = "Inception.2010.1080p.mkv",
            isFolder = false,
            pathOrParentId = "https://webdav.torbox.app/Movies/Inception.2010.1080p.mkv",
            playbackUrl = "https://webdav.torbox.app/Movies/Inception.2010.1080p.mkv",
            format = StreamFormat.MKV,
            sizeBytes = 2_400_000_000L,
            requestHeaders = mapOf("Authorization" to "Basic dGVzdA==")
        )

        composeRule.setContent {
            CineStreamTheme {
                LocalNetworkSheet(
                    servers = listOf(server),
                    browserState = LocalNetworkUiState(
                        activeServer = server,
                        breadcrumbPath = listOf("TorBox Cloud" to "https://webdav.torbox.app/"),
                        items = listOf(folder, video)
                    ),
                    onAddServer = { _, _, _, _, _ -> },
                    onDeleteServer = {},
                    onOpenServer = {},
                    onBrowseItem = {},
                    onNavigateUp = {},
                    onDismiss = {},
                    onDownloadItem = { downloadedItem = it }
                )
            }
        }

        // Folder must NOT have a download button; playable video MUST have one
        composeRule.onNodeWithTag("network_browser_download_${folder.id.hashCode()}").assertDoesNotExist()
        composeRule.onNodeWithTag("network_browser_download_${video.id.hashCode()}")
            .assertIsDisplayed()
            .performClick()

        val captured = downloadedItem
        assertNotNull(captured)
        assertEquals(video.playbackUrl, captured!!.playbackUrl)
        assertEquals("Basic dGVzdA==", captured.requestHeaders["Authorization"])
    }

    @Test
    fun offlineDownloadManager_downloadsHlsSegmentsInParallelWithDemuxedAudioAndRetries(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val flakyAudioFailOnce = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/master.m3u8") { ex ->
            val body = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud_en",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,AUDIO="aud_en"
                video.m3u8
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/video.m3u8") { ex ->
            val manifest = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-TARGETDURATION:4")
                for (i in 0 until 12) {
                    appendLine("#EXTINF:4.0,")
                    appendLine("seg/v$i.ts")
                }
                appendLine("#EXT-X-ENDLIST")
            }.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, manifest.size.toLong())
            ex.responseBody.use { it.write(manifest) }
        }
        server.createContext("/audio.m3u8") { ex ->
            val manifest = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-TARGETDURATION:4")
                for (i in 0 until 6) {
                    appendLine("#EXTINF:4.0,")
                    appendLine("seg/a$i.aac")
                }
                appendLine("#EXT-X-ENDLIST")
            }.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, manifest.size.toLong())
            ex.responseBody.use { it.write(manifest) }
        }
        server.createContext("/seg/") { ex ->
            val cur = inFlight.incrementAndGet()
            peakInFlight.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
            try {
                // Brief simulated RTT so parallel workers clearly overlap
                Thread.sleep(70L)
                val name = ex.requestURI.path.substringAfterLast('/')
                if (name == "a2.aac" && flakyAudioFailOnce.getAndIncrement() == 0) {
                    ex.sendResponseHeaders(503, -1)
                    return@createContext
                }
                val payload = "PAYLOAD_$name".repeat(256).toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            } finally {
                inFlight.decrementAndGet()
            }
        }
        server.start()

        try {
            val manager = OfflineDownloadManager(ctx, db.offlineDownloadDao(), OkHttpClient())
            val masterUrl = "http://127.0.0.1:${server.address.port}/master.m3u8"
            manager.enqueueDownload("Parallel HLS Movie", masterUrl, StreamFormat.HLS)

            // Wait up to 8s for completion
            var finished: OfflineDownloadEntity? = null
            repeat(160) {
                val item = db.offlineDownloadDao().getBySourceUrl(masterUrl)
                if (item != null && (item.status == OfflineDownloadManager.STATUS_COMPLETED ||
                        item.status == OfflineDownloadManager.STATUS_FAILED)
                ) {
                    finished = item
                    return@repeat
                }
                delay(50L)
            }

            val done = finished
            assertNotNull("Expected HLS download to finish", done)
            assertEquals("Expected COMPLETED but got ${done!!.errorMessage}", OfflineDownloadManager.STATUS_COMPLETED, done.status)
            assertTrue(
                "Expected parallel HLS downloads (peak >= 4 in flight), got ${peakInFlight.get()}",
                peakInFlight.get() >= 4
            )
            assertEquals(2, flakyAudioFailOnce.get()) // Attempt 1 returned 503, attempt 2 retried & succeeded

            val localMaster = File(done.localFilePath)
            assertTrue(localMaster.exists())
            val masterText = localMaster.readText()
            assertTrue(masterText.contains("audio/audio.m3u8"))
            assertTrue(masterText.contains("video/video.m3u8"))

            val folder = localMaster.parentFile!!
            assertTrue(File(folder, "video/seg_0.ts").exists())
            assertTrue(File(folder, "video/seg_11.ts").exists())
            assertTrue(File(folder, "audio/seg_2.aac").exists())
            assertEquals("PAYLOAD_a2.aac".repeat(256), File(folder, "audio/seg_2.aac").readText())
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun offlineDownloadManager_downloadsLargeProgressiveFileViaParallel206ChunksBytePerfect(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // 5 MB deterministic payload (> 4 MB MIN_MULTICHUNK_BYTES threshold)
        val totalSize = 5 * 1024 * 1024
        val sourceBytes = ByteArray(totalSize) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }
        val rangeCalls = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/movie.mkv") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                rangeCalls.incrementAndGet()
                val spec = range.removePrefix("bytes=").split("-")
                val start = spec[0].toInt()
                val end = spec.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (totalSize - 1)
                val len = (end - start) + 1
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/$totalSize")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(sourceBytes, start, len) }
            } else {
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(200, totalSize.toLong())
                ex.responseBody.use { it.write(sourceBytes) }
            }
        }
        server.start()

        try {
            val manager = OfflineDownloadManager(ctx, db.offlineDownloadDao(), OkHttpClient())
            val url = "http://127.0.0.1:${server.address.port}/movie.mkv"
            manager.enqueueDownload("Big MKV", url, StreamFormat.MKV)

            var finished: OfflineDownloadEntity? = null
            repeat(160) {
                val item = db.offlineDownloadDao().getBySourceUrl(url)
                if (item != null && (item.status == OfflineDownloadManager.STATUS_COMPLETED ||
                        item.status == OfflineDownloadManager.STATUS_FAILED)
                ) {
                    finished = item
                    return@repeat
                }
                delay(50L)
            }

            val done = finished
            assertNotNull(done)
            assertEquals("Expected COMPLETED but got ${done!!.errorMessage}", OfflineDownloadManager.STATUS_COMPLETED, done.status)
            // 1 probe (bytes=0-0) + 4 parallel chunk Range requests = 5
            assertTrue("Expected multi-chunk 206 range requests (>=5), got ${rangeCalls.get()}", rangeCalls.get() >= 5)

            val savedFile = File(done.localFilePath)
            assertTrue(savedFile.exists())
            assertTrue(savedFile.name.endsWith(".mkv"))
            assertEquals(totalSize.toLong(), savedFile.length())
            assertArrayEquals(sourceBytes, savedFile.readBytes())
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun downloadQualitySheet_listsQualitiesAndSelectsChosenVariant() {
        val options = listOf(
            DownloadQualityOption(
                id = "q_2160",
                label = "2160p 4K UHD",
                detail = "14.5 Mbps · ~10.2 GB · Audio: English",
                height = 2160,
                bandwidthBps = 14_500_000L,
                variantPlaylistUrl = "https://cdn.example/2160p.m3u8",
                streamInfLine = "#EXT-X-STREAM-INF:BANDWIDTH=14500000,RESOLUTION=3840x2160",
                isRecommended = false
            ),
            DownloadQualityOption(
                id = "q_1080",
                label = "1080p Full HD",
                detail = "5.0 Mbps · ~3.5 GB · Audio: English",
                height = 1080,
                bandwidthBps = 5_000_000L,
                variantPlaylistUrl = "https://cdn.example/1080p.m3u8",
                streamInfLine = "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080",
                isRecommended = true
            ),
            DownloadQualityOption(
                id = "q_720",
                label = "720p HD",
                detail = "2.5 Mbps · ~1.8 GB · Audio: English",
                height = 720,
                bandwidthBps = 2_500_000L,
                variantPlaylistUrl = "https://cdn.example/720p.m3u8",
                streamInfLine = "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720",
                isRecommended = false
            )
        )
        var picked: DownloadQualityOption? = null
        composeRule.setContent {
            CineStreamTheme {
                DownloadQualitySheet(
                    title = "Interstellar",
                    isLoading = false,
                    options = options,
                    onSelectQuality = { picked = it },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag("download_quality_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("download_quality_option_0").assertIsDisplayed().assertTextContains("2160p 4K UHD", substring = true)
        composeRule.onNodeWithTag("download_quality_option_1").assertIsDisplayed().assertTextContains("1080p Full HD", substring = true)
        composeRule.onNodeWithTag("download_quality_option_2").assertIsDisplayed().assertTextContains("720p HD", substring = true)
        composeRule.onNodeWithTag("download_quality_option_2").performClick()
        assertEquals("q_720", picked?.id)
    }

    @Test
    fun downloadsSheet_displaysPauseAndResumeControlsForActiveAndPausedDownloads() {
        var pausedId: Long? = null
        var resumedId: Long? = null
        val active = OfflineDownloadEntity(
            id = 61L,
            title = "Active Movie",
            sourceUrl = "https://cdn.example/active.m3u8",
            formatName = StreamFormat.HLS.name,
            localFilePath = "",
            status = OfflineDownloadManager.STATUS_DOWNLOADING,
            progressPercent = 40,
            bytesDownloaded = 120_000_000L,
            totalBytes = 300_000_000L
        )
        val paused = OfflineDownloadEntity(
            id = 62L,
            title = "Paused Movie",
            sourceUrl = "https://cdn.example/paused.mp4",
            formatName = StreamFormat.MP4.name,
            localFilePath = "",
            status = OfflineDownloadManager.STATUS_PAUSED,
            progressPercent = 58,
            bytesDownloaded = 180_000_000L,
            totalBytes = 310_000_000L,
            errorMessage = "Paused · Waiting for network"
        )
        composeRule.setContent {
            CineStreamTheme {
                DownloadsSheet(
                    downloads = listOf(active, paused),
                    downloadSpeeds = mapOf(61L to 10_000_000L),
                    onPlayDownload = {},
                    onPauseDownload = { pausedId = it },
                    onResumeDownload = { resumedId = it },
                    onCancelDownload = {},
                    onDeleteDownload = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag("download_pause_61").assertIsDisplayed().performClick()
        assertEquals(61L, pausedId)
        composeRule.onNodeWithTag("download_resume_62").assertIsDisplayed().performClick()
        assertEquals(62L, resumedId)
    }

    @Test
    fun offlineDownloadManager_resumesHlsAfterNetworkOffAndSwitchWithoutRedownloadingSegments(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val segmentHits = ConcurrentHashMap<String, AtomicInteger>()
        val networkOnlineOnServer = AtomicBoolean(true)
        val servedBeforeDrop = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/resume.m3u8") { ex ->
            val manifest = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-TARGETDURATION:4")
                for (i in 0 until 10) {
                    appendLine("#EXTINF:4.0,")
                    appendLine("seg/s$i.ts")
                }
                appendLine("#EXT-X-ENDLIST")
            }.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, manifest.size.toLong())
            ex.responseBody.use { it.write(manifest) }
        }
        server.createContext("/seg/") { ex ->
            val name = ex.requestURI.path.substringAfterLast('/')
            if (!networkOnlineOnServer.get()) {
                // Simulate hard network drop by abruptly closing connection
                ex.close()
                return@createContext
            }
            val segIdx = name.removePrefix("s").removeSuffix(".ts").toIntOrNull() ?: 0
            // Delay higher segments slightly so lower segments finish cleanly before network drop
            if (segIdx >= 4) {
                Thread.sleep(220L)
            }
            if (!networkOnlineOnServer.get()) {
                ex.close()
                return@createContext
            }
            segmentHits.computeIfAbsent(name) { AtomicInteger(0) }.incrementAndGet()
            val payload = "RESUMABLE_$name".repeat(256).toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
            servedBeforeDrop.incrementAndGet()
        }
        server.start()

        try {
            val manager = OfflineDownloadManager(ctx, db.offlineDownloadDao(), OkHttpClient())
            val url = "http://127.0.0.1:${server.address.port}/resume.m3u8"
            manager.enqueueDownload("Resumable HLS", url, StreamFormat.HLS)

            // Wait until segments s0..s3 have completed cleanly on disk, then simulate turning network OFF
            repeat(120) {
                if ((0..3).all { (segmentHits["s$it.ts"]?.get() ?: 0) >= 1 }) return@repeat
                delay(25L)
            }
            // Brief settle so atomic .part -> seg_0..3.ts renames complete before simulating hard network loss
            delay(100L)
            assertTrue(
                "Expected s0..s3 served before network off",
                (0..3).all { (segmentHits["s$it.ts"]?.get() ?: 0) >= 1 }
            )

            networkOnlineOnServer.set(false)
            manager.onNetworkStateChanged(connected = false, switchedInterface = false)

            // Wait until manager transitions gracefully into STATUS_PAUSED without losing saved segments
            var pausedEntity: OfflineDownloadEntity? = null
            repeat(160) {
                val item = db.offlineDownloadDao().getBySourceUrl(url)
                if (item != null && item.status == OfflineDownloadManager.STATUS_PAUSED) {
                    pausedEntity = item
                    return@repeat
                }
                delay(40L)
            }
            assertNotNull("Expected download to enter STATUS_PAUSED on network loss", pausedEntity)
            assertTrue("Expected saved bytes > 0 while paused", pausedEntity!!.bytesDownloaded > 0L)

            // Simulate switching to another network (e.g., Mobile -> Wi-Fi)
            networkOnlineOnServer.set(true)
            manager.onNetworkStateChanged(connected = true, switchedInterface = true)

            // Wait for automatic resume to complete all 10 segments
            var completedEntity: OfflineDownloadEntity? = null
            repeat(200) {
                val item = db.offlineDownloadDao().getBySourceUrl(url)
                if (item != null && (item.status == OfflineDownloadManager.STATUS_COMPLETED ||
                        item.status == OfflineDownloadManager.STATUS_FAILED)
                ) {
                    completedEntity = item
                    return@repeat
                }
                delay(50L)
            }
            val completed = completedEntity
            assertNotNull("Expected download to auto-resume and finish", completed)
            assertEquals(
                "Expected COMPLETED after network switch, got ${completed!!.status} (${completed.errorMessage})",
                OfflineDownloadManager.STATUS_COMPLETED,
                completed.status
            )

            // CRITICAL ASSERTION: Segments already saved before network drop (s0..s3) were NEVER re-downloaded!
            for (i in 0..3) {
                val segName = "s$i.ts"
                assertEquals(
                    "Already-saved segment $segName must NEVER be re-downloaded after resume",
                    1,
                    segmentHits[segName]?.get() ?: 0
                )
            }
            val localMaster = File(completed.localFilePath)
            assertTrue(localMaster.exists())
            val folder = localMaster.parentFile!!
            for (i in 0 until 10) {
                assertEquals("RESUMABLE_s$i.ts".repeat(256), File(folder, "seg_$i.ts").readText())
            }
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun offlineDownloadManager_resumesProgressiveMultiChunkAfterNetworkDropBytePerfect(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val totalSize = 5 * 1024 * 1024
        val sourceBytes = ByteArray(totalSize) { idx -> ((idx * 19 + 7) and 0xFF).toByte() }
        val dropEnabled = AtomicBoolean(true)
        val totalBytesSentOverWire = AtomicInteger(0)
        val firstPassPartialSent = AtomicBoolean(false)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/resumable.mkv") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=").split("-")
                val start = spec[0].toInt()
                val end = spec.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (totalSize - 1)
                val len = (end - start) + 1
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/$totalSize")
                ex.sendResponseHeaders(206, len.toLong())
                if (len == 1) {
                    ex.responseBody.use { it.write(sourceBytes, start, 1) }
                    return@createContext
                }
                if (dropEnabled.get()) {
                    // Send first 300 KB of each chunk cleanly, then simulate connection drop
                    val partial = minOf(len, 300 * 1024)
                    ex.responseBody.write(sourceBytes, start, partial)
                    ex.responseBody.flush()
                    totalBytesSentOverWire.addAndGet(partial)
                    firstPassPartialSent.set(true)
                    Thread.sleep(60L)
                    ex.close()
                } else {
                    ex.responseBody.use { out ->
                        out.write(sourceBytes, start, len)
                    }
                    totalBytesSentOverWire.addAndGet(len)
                }
            } else {
                ex.sendResponseHeaders(200, totalSize.toLong())
                ex.responseBody.use { it.write(sourceBytes) }
            }
        }
        server.start()

        try {
            // Pre-warm Room in-memory schema on cold JVM before polling network transfer
            db.offlineDownloadDao().getAllOnce()
            val manager = OfflineDownloadManager(ctx, db.offlineDownloadDao(), OkHttpClient())
            val url = "http://127.0.0.1:${server.address.port}/resumable.mkv"
            manager.enqueueDownload("Resumable MKV", url, StreamFormat.MKV)

            repeat(300) {
                if (firstPassPartialSent.get()) return@repeat
                delay(40L)
            }
            assertTrue("Expected partial chunk bytes sent before drop", firstPassPartialSent.get())

            // Simulate network going offline mid-transfer
            manager.onNetworkStateChanged(connected = false, switchedInterface = false)

            var pausedEntity: OfflineDownloadEntity? = null
            repeat(160) {
                val item = db.offlineDownloadDao().getBySourceUrl(url)
                if (item != null && item.status == OfflineDownloadManager.STATUS_PAUSED) {
                    pausedEntity = item
                    return@repeat
                }
                delay(40L)
            }
            assertNotNull("Expected progressive download to pause on network off", pausedEntity)
            assertTrue("Expected saved partial bytes > 0", pausedEntity!!.bytesDownloaded > 0L)

            // Bring network back on a new interface (e.g., Wi-Fi -> Mobile)
            dropEnabled.set(false)
            manager.onNetworkStateChanged(connected = true, switchedInterface = true)

            var completedEntity: OfflineDownloadEntity? = null
            repeat(200) {
                val item = db.offlineDownloadDao().getBySourceUrl(url)
                if (item != null && (item.status == OfflineDownloadManager.STATUS_COMPLETED ||
                        item.status == OfflineDownloadManager.STATUS_FAILED)
                ) {
                    completedEntity = item
                    return@repeat
                }
                delay(50L)
            }
            val completedProg = completedEntity
            assertNotNull("Expected progressive download to auto-resume and finish", completedProg)
            assertEquals(OfflineDownloadManager.STATUS_COMPLETED, completedProg!!.status)

            val savedFile = File(completedProg.localFilePath)
            assertEquals(totalSize.toLong(), savedFile.length())
            assertArrayEquals(sourceBytes, savedFile.readBytes())
            // Verify we did NOT re-download the entire 5 MB file from byte 0 (wire bytes stay close to 5 MB, well under 6.5 MB)
            assertTrue(
                "Expected resumable wire transfer close to $totalSize bytes without full restart, got ${totalBytesSentOverWire.get()}",
                totalBytesSentOverWire.get() < totalSize + (1500 * 1024)
            )
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun offlineDownloadManager_parsesMultiQualityHlsAndDownloadsSelectedQuality(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val requested720 = AtomicInteger(0)
        val requested1080 = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/multi.m3u8") { ex ->
            val master = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160
                2160p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
                1080p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
                720p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480
                480p.m3u8
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, master.size.toLong())
            ex.responseBody.use { it.write(master) }
        }
        server.createContext("/1080p.m3u8") { ex ->
            requested1080.incrementAndGet()
            val body = "#EXTM3U\n#EXTINF:4.0,\nseg1080.ts\n#EXT-X-ENDLIST\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/720p.m3u8") { ex ->
            requested720.incrementAndGet()
            val body = "#EXTM3U\n#EXTINF:4.0,\nseg720.ts\n#EXT-X-ENDLIST\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/seg720.ts") { ex ->
            val data = "QUALITY_720P_DATA".repeat(128).toByteArray()
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }
        server.start()

        try {
            val manager = OfflineDownloadManager(ctx, db.offlineDownloadDao(), OkHttpClient())
            val masterUrl = "http://127.0.0.1:${server.address.port}/multi.m3u8"
            val qualities = manager.inspectHlsQualities(masterUrl, durationMs = 3_600_000L)
            assertEquals(4, qualities.size)
            assertEquals(2160, qualities[0].height)
            assertEquals(1080, qualities[1].height)
            assertTrue("1080p should be marked recommended", qualities[1].isRecommended)
            assertEquals(720, qualities[2].height)
            assertEquals(480, qualities[3].height)
            assertTrue("Estimated size should appear in detail", qualities[2].detail.contains("~"))

            // User explicitly chooses 720p HD
            val chosen720 = qualities[2]
            manager.enqueueDownload("Multi Quality Movie", masterUrl, StreamFormat.HLS, selectedQuality = chosen720)

            var done: OfflineDownloadEntity? = null
            repeat(120) {
                val item = db.offlineDownloadDao().getBySourceUrl(masterUrl)
                if (item != null && (item.status == OfflineDownloadManager.STATUS_COMPLETED ||
                        item.status == OfflineDownloadManager.STATUS_FAILED)
                ) {
                    done = item
                    return@repeat
                }
                delay(40L)
            }
            val finishedQuality = done
            assertNotNull(finishedQuality)
            assertEquals(OfflineDownloadManager.STATUS_COMPLETED, finishedQuality!!.status)
            assertTrue("Title should include selected quality", finishedQuality.title.contains("720p"))
            assertEquals(1, requested720.get())
            assertEquals(0, requested1080.get())
            val savedSeg = File(File(finishedQuality.localFilePath).parentFile, "seg_0.ts")
            assertEquals("QUALITY_720P_DATA".repeat(128), savedSeg.readText())
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun offlineDownloadManager_parsesMultiAudioCombinationsWithSizesAndDownloadsChosenAudio(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val requestedUrls = ConcurrentHashMap<String, AtomicInteger>()
        fun bump(path: String) {
            requestedUrls.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet()
        }

        // 10 segments * 360.0s each = 3600s (1 hour) so #EXTINF auto-duration probe produces clear GB/MB sizes
        val mediaPlaylistBody = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:360")
            appendLine("#EXTINF:3600.0,")
            appendLine("video_720_seg.ts")
            appendLine("#EXT-X-ENDLIST")
        }.toByteArray(Charsets.UTF_8)

        val audioHiPlaylistBody = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:3600
            #EXTINF:3600.0,
            audio_hi_seg.aac
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/multiaudio_master") { ex ->
            bump("/multiaudio_master")
            val master = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",CHANNELS="2",DEFAULT=YES,AUTOSELECT=YES,URI="audio_en.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Hindi",LANGUAGE="hi",CHANNELS="6",DEFAULT=NO,AUTOSELECT=YES,URI="audio_hi.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Tamil",LANGUAGE="ta",CHANNELS="2",DEFAULT=NO,AUTOSELECT=YES,URI="audio_ta.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
                1080p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2400000,RESOLUTION=1280x720,AUDIO="aud"
                720p.m3u8
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            ex.sendResponseHeaders(200, master.size.toLong())
            ex.responseBody.use { it.write(master) }
        }
        server.createContext("/1080p.m3u8") { ex ->
            bump("/1080p.m3u8")
            val body = "#EXTM3U\n#EXTINF:3600.0,\nvideo_1080_seg.ts\n#EXT-X-ENDLIST\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/720p.m3u8") { ex ->
            bump("/720p.m3u8")
            ex.sendResponseHeaders(200, mediaPlaylistBody.size.toLong())
            ex.responseBody.use { it.write(mediaPlaylistBody) }
        }
        server.createContext("/video_720_seg.ts") { ex ->
            bump("/video_720_seg.ts")
            val data = "VIDEO_720P_BYTES".repeat(64).toByteArray()
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }
        server.createContext("/audio_en.m3u8") { ex ->
            bump("/audio_en.m3u8")
            val body = "#EXTM3U\n#EXTINF:3600.0,\naudio_en_seg.aac\n#EXT-X-ENDLIST\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/audio_hi.m3u8") { ex ->
            bump("/audio_hi.m3u8")
            ex.sendResponseHeaders(200, audioHiPlaylistBody.size.toLong())
            ex.responseBody.use { it.write(audioHiPlaylistBody) }
        }
        server.createContext("/audio_hi_seg.aac") { ex ->
            bump("/audio_hi_seg.aac")
            val data = "HINDI_5_1_AUDIO_PAYLOAD".repeat(64).toByteArray()
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }
        server.createContext("/audio_ta.m3u8") { ex ->
            bump("/audio_ta.m3u8")
            val body = "#EXTM3U\n#EXTINF:3600.0,\naudio_ta_seg.aac\n#EXT-X-ENDLIST\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            db.offlineDownloadDao().getAllOnce()
            val manager = OfflineDownloadManager(ctx, db.offlineDownloadDao(), OkHttpClient())
            // Extensionless URL + durationMs = 0L exercises both bounded probe and #EXTINF duration calculation
            val masterUrl = "http://127.0.0.1:${server.address.port}/multiaudio_master"
            val options = manager.inspectHlsQualities(masterUrl, durationMs = 0L)

            // 2 video qualities x 3 audio tracks = 6 selectable combinations
            assertEquals(6, options.size)
            assertTrue(options.all { it.hasExplicitAudioChoice })
            assertTrue("Expected non-zero auto-probed estimated sizes", options.all { it.estimatedBytes > 0L })

            val opt720En = options.first { it.height == 720 && it.audioLanguage == "en" }
            val opt720Hi = options.first { it.height == 720 && it.audioLanguage == "hi" }
            val opt1080En = options.first { it.height == 1080 && it.audioLanguage == "en" }
            val opt1080Hi = options.first { it.height == 1080 && it.audioLanguage == "hi" }

            assertTrue("1080p default English should be recommended", opt1080En.isRecommended)
            assertTrue("Non-default Hindi 1080p should not be recommended", !opt1080Hi.isRecommended)
            assertTrue("Hindi 5.1 label should reflect surround channels", opt720Hi.audioName.contains("Hindi") && opt720Hi.audioName.contains("5.1"))
            assertTrue("5.1 audio combo should estimate larger size than 2.0 at same quality", opt720Hi.estimatedBytes > opt720En.estimatedBytes)
            assertTrue("1080p should estimate larger size than 720p", opt1080En.estimatedBytes > opt720En.estimatedBytes)

            // Reset counters after inspection probe before downloading chosen (720p + Hindi 5.1)
            requestedUrls.clear()

            manager.enqueueDownload(
                title = "Cinema Epic",
                sourceUrl = masterUrl,
                format = StreamFormat.AUTO,
                selectedQuality = opt720Hi
            )

            var done: OfflineDownloadEntity? = null
            repeat(150) {
                val item = db.offlineDownloadDao().getBySourceUrl(masterUrl)
                if (item != null && (item.status == OfflineDownloadManager.STATUS_COMPLETED ||
                        item.status == OfflineDownloadManager.STATUS_FAILED)
                ) {
                    done = item
                    return@repeat
                }
                delay(40L)
            }
            val completed = done
            assertNotNull("Expected multi-audio HLS download to complete", completed)
            assertEquals(OfflineDownloadManager.STATUS_COMPLETED, completed!!.status)
            assertTrue("Title should include chosen resolution", completed.title.contains("720p"))
            assertTrue("Title should include chosen audio track", completed.title.contains("Hindi"))

            // Verify ONLY 720p video and Hindi audio were fetched — NOT 1080p, English, or Tamil
            assertEquals(1, requestedUrls["/720p.m3u8"]?.get() ?: 0)
            assertEquals(1, requestedUrls["/audio_hi.m3u8"]?.get() ?: 0)
            assertEquals(0, requestedUrls["/1080p.m3u8"]?.get() ?: 0)
            assertEquals(0, requestedUrls["/audio_en.m3u8"]?.get() ?: 0)
            assertEquals(0, requestedUrls["/audio_ta.m3u8"]?.get() ?: 0)

            val localMasterFile = File(completed.localFilePath)
            assertTrue(localMasterFile.exists())
            val localMasterContent = localMasterFile.readText()
            assertTrue("Local master manifest should name Hindi 5.1 audio", localMasterContent.contains("NAME=\"Hindi 5.1\""))
            assertTrue("Local master manifest should tag LANGUAGE=\"hi\"", localMasterContent.contains("LANGUAGE=\"hi\""))
            val savedAudioSeg = File(File(localMasterFile.parentFile, "audio"), "seg_0.aac")
            assertTrue("Chosen Hindi audio segment must exist on disk", savedAudioSeg.exists())
            assertEquals("HINDI_5_1_AUDIO_PAYLOAD".repeat(64), savedAudioSeg.readText())
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun offlineDownloadManager_singleQualityMultiAudioStillProducesSelectableOptions(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val requestedSpanish = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/single_res_dual_audio.m3u8") { ex ->
            // Muxed English track (no URI) + external Spanish track (URI)
            val master = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",NAME="Spanish",LANGUAGE="es",DEFAULT=NO,AUTOSELECT=YES,URI="audio_es.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,AUDIO="a1"
                only_1080p.m3u8
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, master.size.toLong())
            ex.responseBody.use { it.write(master) }
        }
        server.createContext("/only_1080p.m3u8") { ex ->
            val body = "#EXTM3U\n#EXTINF:1200.0,\nv0.ts\n#EXT-X-ENDLIST\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v0.ts") { ex ->
            val data = "SINGLE_RES_VIDEO".repeat(32).toByteArray()
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }
        server.createContext("/audio_es.m3u8") { ex ->
            requestedSpanish.incrementAndGet()
            val body = "#EXTM3U\n#EXTINF:1200.0,\na_es.aac\n#EXT-X-ENDLIST\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/a_es.aac") { ex ->
            val data = "SPANISH_AUDIO_DATA".repeat(32).toByteArray()
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }
        server.start()

        try {
            db.offlineDownloadDao().getAllOnce()
            val manager = OfflineDownloadManager(ctx, db.offlineDownloadDao(), OkHttpClient())
            val masterUrl = "http://127.0.0.1:${server.address.port}/single_res_dual_audio.m3u8"
            val options = manager.inspectHlsQualities(masterUrl, durationMs = 1_200_000L)

            assertEquals("1 quality x 2 audios must produce 2 selectable options", 2, options.size)
            val muxedEn = options.first { it.audioLanguage == "en" }
            val extEs = options.first { it.audioLanguage == "es" }
            assertEquals(null, muxedEn.audioPlaylistUrl)
            assertNotNull(extEs.audioPlaylistUrl)

            manager.enqueueDownload("Dual Audio Film", masterUrl, StreamFormat.HLS, selectedQuality = extEs)

            var done: OfflineDownloadEntity? = null
            repeat(120) {
                val item = db.offlineDownloadDao().getBySourceUrl(masterUrl)
                if (item != null && (item.status == OfflineDownloadManager.STATUS_COMPLETED ||
                        item.status == OfflineDownloadManager.STATUS_FAILED)
                ) {
                    done = item
                    return@repeat
                }
                delay(40L)
            }
            assertNotNull(done)
            assertEquals(OfflineDownloadManager.STATUS_COMPLETED, done!!.status)
            assertEquals(1, requestedSpanish.get())
            assertTrue(done!!.title.contains("Spanish"))
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun downloadQualitySheet_filtersAudioTracksAndDisplaysQualityAudioSizeCombinations() {
        val options = listOf(
            DownloadQualityOption(
                id = "1080_en",
                label = "1080p Full HD · English 2.0",
                detail = "5.0 Mbps · Audio: English 2.0 · ~2.25 GB",
                height = 1080,
                bandwidthBps = 5_000_000L,
                estimatedBytes = 2_415_919_104L,
                variantPlaylistUrl = "https://cdn.example.com/1080p.m3u8",
                streamInfLine = "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080",
                audioPlaylistUrl = "https://cdn.example.com/en.m3u8",
                audioName = "English 2.0",
                audioLanguage = "en",
                audioChannels = "2",
                audioBitrateBps = 128_000L,
                hasExplicitAudioChoice = true,
                isRecommended = true
            ),
            DownloadQualityOption(
                id = "1080_hi",
                label = "1080p Full HD · Hindi 5.1",
                detail = "5.3 Mbps · Audio: Hindi 5.1 · ~2.38 GB",
                height = 1080,
                bandwidthBps = 5_256_000L,
                estimatedBytes = 2_555_505_664L,
                variantPlaylistUrl = "https://cdn.example.com/1080p.m3u8",
                streamInfLine = "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080",
                audioPlaylistUrl = "https://cdn.example.com/hi.m3u8",
                audioName = "Hindi 5.1",
                audioLanguage = "hi",
                audioChannels = "6",
                audioBitrateBps = 384_000L,
                hasExplicitAudioChoice = true,
                isRecommended = false
            ),
            DownloadQualityOption(
                id = "720_hi",
                label = "720p HD · Hindi 5.1",
                detail = "2.7 Mbps · Audio: Hindi 5.1 · ~1.20 GB",
                height = 720,
                bandwidthBps = 2_656_000L,
                estimatedBytes = 1_288_490_188L,
                variantPlaylistUrl = "https://cdn.example.com/720p.m3u8",
                streamInfLine = "#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720",
                audioPlaylistUrl = "https://cdn.example.com/hi.m3u8",
                audioName = "Hindi 5.1",
                audioLanguage = "hi",
                audioChannels = "6",
                audioBitrateBps = 384_000L,
                hasExplicitAudioChoice = true,
                isRecommended = false
            )
        )

        var chosen: DownloadQualityOption? = null
        composeRule.setContent {
            CineStreamTheme {
                DownloadQualitySheet(
                    title = "Cinema Epic",
                    isLoading = false,
                    options = options,
                    onSelectQuality = { chosen = it },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("Choose Quality & Audio").assertIsDisplayed()
        composeRule.onNodeWithTag("download_audio_filter_all").assertIsDisplayed()
        composeRule.onNodeWithTag("download_audio_filter_0").assertIsDisplayed()
        composeRule.onNodeWithTag("download_audio_filter_1").assertIsDisplayed()

        // Filter to Hindi 5.1 (index 1) -> shows 1080p Hindi 5.1 (idx 0) and 720p Hindi 5.1 (idx 1)
        composeRule.onNodeWithTag("download_audio_filter_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("download_quality_option_1").assertIsDisplayed()
        composeRule.onNodeWithTag("download_quality_option_1").performClick()

        assertNotNull(chosen)
        assertEquals("720_hi", chosen!!.id)
        assertEquals("Hindi 5.1", chosen!!.audioName)
        assertEquals(720, chosen!!.height)
    }

    @Test
    fun offlineDownloadManager_constantsSpeedFormattingAndVersion35Metadata() {
        assertEquals(4000, OfflineDownloadManager.MAX_HLS_SEGMENTS)
        assertEquals(8, OfflineDownloadManager.HLS_PARALLEL_CONCURRENCY)
        assertEquals(4, OfflineDownloadManager.PROGRESSIVE_PARALLEL_CHUNKS)
        assertEquals("", OfflineDownloadManager.formatSpeed(0L))
        assertEquals("512 KB/s", OfflineDownloadManager.formatSpeed(512L * 1024L))
        assertEquals("12.5 MB/s", OfflineDownloadManager.formatSpeed((12.5 * 1024 * 1024).toLong()))
        assertEquals(35, BuildConfig.VERSION_CODE)
        assertEquals("1.35", BuildConfig.VERSION_NAME)
    }
}
