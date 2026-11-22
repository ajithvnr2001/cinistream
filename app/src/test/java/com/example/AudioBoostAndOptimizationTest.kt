package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.player.AudioBoostMath
import com.example.ui.player.TracksSheet
import com.example.ui.player.VerticalVolumeBoostHud
import com.example.ui.player.VolumeBoostDialog
import com.example.ui.theme.CineStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioBoostAndOptimizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun audioBoostMathMapsZeroToTwoHundredPercentAccurately() {
        assertEquals(0, AudioBoostMath.clampPercent(-25))
        assertEquals(200, AudioBoostMath.clampPercent(320))

        assertEquals(0.0f, AudioBoostMath.computePlayerVolume(0), 0.001f)
        assertEquals(0.5f, AudioBoostMath.computePlayerVolume(50), 0.001f)
        assertEquals(1.0f, AudioBoostMath.computePlayerVolume(100), 0.001f)
        assertEquals(1.0f, AudioBoostMath.computePlayerVolume(150), 0.001f)
        assertEquals(1.0f, AudioBoostMath.computePlayerVolume(200), 0.001f)

        assertEquals(0, AudioBoostMath.computeTargetGainMb(0))
        assertEquals(0, AudioBoostMath.computeTargetGainMb(100))
        assertEquals(500, AudioBoostMath.computeTargetGainMb(125))
        assertEquals(1000, AudioBoostMath.computeTargetGainMb(150))
        assertEquals(2000, AudioBoostMath.computeTargetGainMb(200))

        assertEquals("Muted", AudioBoostMath.formatShortLabel(0))
        assertEquals("100%", AudioBoostMath.formatShortLabel(100))
        assertEquals("Boost 200%", AudioBoostMath.formatShortLabel(200))
        assertEquals("+20.0 dB", AudioBoostMath.formatGainDb(200))
    }

    @Test
    fun memorySafeTargetBufferBytesClampsCorrectlyForTvAndLargeHeap() {
        // 80 MB constrained TV heap -> 16 MB buffer cap (20%), 0 ms back-buffer
        assertEquals(
            16 * 1024 * 1024,
            com.example.player.CinePlayerManager.computeSafeTargetBufferBytes(80L * 1024L * 1024L)
        )
        assertEquals(
            0,
            com.example.player.CinePlayerManager.computeSafeBackBufferMs(80L * 1024L * 1024L)
        )
        // Tiny 48 MB heap -> clamped to 12 MB minimum
        assertEquals(
            com.example.player.CinePlayerManager.MIN_TARGET_BUFFER_BYTES,
            com.example.player.CinePlayerManager.computeSafeTargetBufferBytes(48L * 1024L * 1024L)
        )
        // 384 MB largeHeap TV -> clamped to 40 MB maximum, 5s back-buffer
        assertEquals(
            com.example.player.CinePlayerManager.MAX_TARGET_BUFFER_BYTES,
            com.example.player.CinePlayerManager.computeSafeTargetBufferBytes(384L * 1024L * 1024L)
        )
        assertEquals(
            com.example.player.CinePlayerManager.SAFE_LARGE_HEAP_BACK_BUFFER_MS,
            com.example.player.CinePlayerManager.computeSafeBackBufferMs(384L * 1024L * 1024L)
        )
        assertTrue(com.example.player.CinePlayerManager.MIN_BUFFER_MS >= 20_000)
        assertTrue(com.example.player.CinePlayerManager.MAX_BUFFER_MS >= 45_000)
        assertTrue(com.example.player.CinePlayerManager.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS >= 6_000)

        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val appInfo = ctx.applicationInfo
        assertTrue(
            "AndroidManifest must declare android:largeHeap=\"true\"",
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0
        )

        val app = ctx as? CineStreamApplication
        if (app != null) {
            assertTrue(
                "Shared OkHttpClient must enable HTTP/2 multiplexing for Android TV HLS streaming",
                app.sharedOkHttpClient.protocols.contains(okhttp3.Protocol.HTTP_2)
            )
        }
    }

    @Test
    fun tracksSheetSoundBoosterPresetsAndStatsToggleWorkProperly() {
        val currentVolumeState = androidx.compose.runtime.mutableIntStateOf(100)
        val statsEnabledState = androidx.compose.runtime.mutableStateOf(false)

        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    volumePercent = currentVolumeState.intValue,
                    onSetVolumePercent = { currentVolumeState.intValue = it },
                    statsForNerdsEnabled = statsEnabledState.value,
                    onToggleStatsForNerds = { statsEnabledState.value = !statsEnabledState.value },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(180L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tracks_stats_for_nerds_toggle").performClick()
        composeRule.waitForIdle()
        assertTrue("Clicking Stats for Nerds toggle should enable telemetry HUD", statsEnabledState.value)

        composeRule.onNodeWithTag("volume_preset_200").performClick()
        composeRule.waitForIdle()
        assertEquals(200, currentVolumeState.intValue)

        composeRule.onNodeWithTag("volume_boost_minus_button").performClick()
        composeRule.waitForIdle()
        assertEquals(190, currentVolumeState.intValue)

        composeRule.onNodeWithTag("volume_boost_plus_button").performClick()
        composeRule.waitForIdle()
        assertEquals(200, currentVolumeState.intValue)

        composeRule.onNodeWithTag("volume_preset_0").performClick()
        composeRule.waitForIdle()
        assertEquals(0, currentVolumeState.intValue)
    }

    @Test
    fun verticalVolumeHudAndDialogRenderBoostAndDismissCleanly() {
        var dismissed = false
        var volume = 180

        composeRule.setContent {
            CineStreamTheme {
                androidx.compose.foundation.layout.Column {
                    VerticalVolumeBoostHud(volumePercent = volume)
                    VolumeBoostDialog(
                        volumePercent = volume,
                        onSetVolumePercent = { volume = it },
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("BOOST").assertIsDisplayed()
        composeRule.onNodeWithTag("volume_preset_200").performClick()
        assertEquals(200, volume)
        composeRule.onNodeWithTag("volume_dialog_done_button").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun fastDnsResolverCachesOrdersIpv4FirstAndPreservesIpv6Fallback() {
        var nowMs = 1_000L
        var delegateCalls = 0
        val ipv6 = java.net.InetAddress.getByName("2606:4700::1")
        val ipv4 = java.net.InetAddress.getByName("104.21.10.2")

        val fakeDelegate = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                delegateCalls++
                return when (hostname) {
                    "dual.workers.dev" -> listOf(ipv6, ipv4)
                    "v6only.workers.dev" -> listOf(ipv6)
                    else -> throw java.net.UnknownHostException("Unknown $hostname")
                }
            }
        }

        val resolver = com.example.player.FastDnsResolver(
            delegate = fakeDelegate,
            ttlMs = 60_000L,
            clockMs = { nowMs }
        )

        val first = resolver.lookup("dual.workers.dev")
        assertEquals(2, first.size)
        assertTrue("IPv4 must be sorted first on dual-stack", first[0] is java.net.Inet4Address)
        assertTrue("IPv6 must remain available as fallback", first[1] is java.net.Inet6Address)
        assertEquals(1, delegateCalls)

        // Second lookup within TTL hits cache
        nowMs += 30_000L
        val second = resolver.lookup("dual.workers.dev")
        assertEquals(first, second)
        assertEquals(1, delegateCalls)

        // After TTL expires, delegate is called again
        nowMs += 40_000L
        resolver.lookup("dual.workers.dev")
        assertEquals(2, delegateCalls)

        // IPv6-only hosts continue to resolve cleanly
        val v6Result = resolver.lookup("v6only.workers.dev")
        assertEquals(listOf(ipv6), v6Result)

        // Failed lookup throws and is never cached
        var threw = false
        try {
            resolver.lookup("missing.invalid")
        } catch (_: java.net.UnknownHostException) {
            threw = true
        }
        assertTrue(threw)

        resolver.clear()
        assertEquals(0, resolver.cachedHostCount())
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun fastStreamingLoadErrorPolicyRetriesTransientDropsQuicklyAndFailsFastOn404() {
        val policy = com.example.player.FastStreamingLoadErrorHandlingPolicy()
        val dataSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://cdn.example.com/seg1.m4s"))
        val loadEvent = androidx.media3.exoplayer.source.LoadEventInfo(1L, dataSpec, dataSpec.uri, emptyMap(), 0L, 0L, 0L)
        val mediaLoad = androidx.media3.exoplayer.source.MediaLoadData(androidx.media3.common.C.DATA_TYPE_MEDIA)

        val ioError1 = androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            loadEvent, mediaLoad, java.io.IOException("Wi-Fi drop"), 1
        )
        val ioError2 = androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            loadEvent, mediaLoad, java.io.IOException("Wi-Fi drop"), 2
        )
        assertEquals(500L, policy.getRetryDelayMsFor(ioError1))
        assertEquals(1_200L, policy.getRetryDelayMsFor(ioError2))

        val http404 = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
            404, "Not Found", null, emptyMap(), dataSpec, ByteArray(0)
        )
        val fatal404Info = androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            loadEvent, mediaLoad, http404, 1
        )
        assertEquals(
            "HTTP 404 must fail fast so Multi-CDN failover triggers immediately",
            androidx.media3.common.C.TIME_UNSET,
            policy.getRetryDelayMsFor(fatal404Info)
        )

        val http503 = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
            503, "Service Unavailable", null, emptyMap(), dataSpec, ByteArray(0)
        )
        val transient503Info = androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            loadEvent, mediaLoad, http503, 1
        )
        assertEquals(500L, policy.getRetryDelayMsFor(transient503Info))
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun tvRamDirectEmmcBypassOriginExtractionAndAdaptiveTransportWorkSafely() {
        // Android TV & 384 MB constrained heaps bypass slow eMMC disk writes completely
        assertTrue(com.example.player.MediaEdgeCache.shouldBypassDiskCacheForDevice(isTv = true, isLowRam = false, maxHeapBytes = 512L * 1024L * 1024L))
        assertTrue(com.example.player.MediaEdgeCache.shouldBypassDiskCacheForDevice(isTv = false, isLowRam = true, maxHeapBytes = 512L * 1024L * 1024L))
        assertTrue(com.example.player.MediaEdgeCache.shouldBypassDiskCacheForDevice(isTv = false, isLowRam = false, maxHeapBytes = 384L * 1024L * 1024L))
        // Modern 64-bit phones keep buffered UFS edge disk cache enabled
        assertTrue(!com.example.player.MediaEdgeCache.shouldBypassDiskCacheForDevice(isTv = false, isLowRam = false, maxHeapBytes = 512L * 1024L * 1024L))

        // Smart initial Wi-Fi bitrate estimates start frame 1 cleanly in HD
        assertEquals(6_000_000L, com.example.player.CinePlayerManager.computeInitialWifiBitrateEstimate(true))
        assertEquals(10_000_000L, com.example.player.CinePlayerManager.computeInitialWifiBitrateEstimate(false))

        // Origin root extraction strips tokenized paths & ignores local URIs
        assertEquals(
            "https://multicloud-s3-index.ajithvnr2001.workers.dev/",
            com.example.player.StreamingNetworkEngine.extractOriginRootUrl(
                "https://multicloud-s3-index.ajithvnr2001.workers.dev/pub/movie/master.m3u8?token=abc"
            )
        )
        assertEquals(
            null,
            com.example.player.StreamingNetworkEngine.extractOriginRootUrl("content://media/external/video/42")
        )

        // AdaptiveHttpDataSourceFactory falls back cleanly to HTTP/2 OkHttp when Cronet is unavailable
        val okHttpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttp3.OkHttpClient())
        val adaptive = com.example.player.AdaptiveHttpDataSourceFactory(
            okHttpFactory = okHttpFactory,
            transferListener = null,
            cronetEngineProvider = { null }
        )
        adaptive.setDefaultRequestProperties(mapOf("Referer" to "https://example.com"))
        assertEquals(
            com.example.player.StreamingNetworkEngine.TRANSPORT_HTTP2_OKHTTP,
            adaptive.activeTransportLabel()
        )
        assertTrue(adaptive.createDataSource() is androidx.media3.datasource.okhttp.OkHttpDataSource)
    }

    @Test
    fun hlsSegmentIndexParsesRelativeSegmentsSkipsMasterAndByteRangeAndDetectsWindow() {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.000,
            seg_000.m4s
            #EXTINF:6.000,
            seg_001.m4s?token=abc
            #EXTINF:6.000,
            ../audio/seg_002.m4s
            #EXTINF:6.000,
            https://cdn.example.com/abs/seg_003.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val index = com.example.player.HlsSegmentIndex()
        val initial = index.indexPlaylist(
            playlistUrl = "https://workers.dev/pub/movie/v1080/index.m3u8",
            content = mediaPlaylist,
            initialPrefetchCount = 3
        )
        assertEquals(
            listOf(
                "https://workers.dev/pub/movie/v1080/seg_000.m4s",
                "https://workers.dev/pub/movie/v1080/seg_001.m4s?token=abc",
                "https://workers.dev/pub/movie/audio/seg_002.m4s"
            ),
            initial
        )
        assertTrue(index.isIndexedSegment("https://workers.dev/pub/movie/v1080/seg_000.m4s"))
        assertEquals(
            listOf(
                "https://workers.dev/pub/movie/v1080/seg_001.m4s?token=abc",
                "https://workers.dev/pub/movie/audio/seg_002.m4s",
                "https://cdn.example.com/abs/seg_003.m4s"
            ),
            index.getNextSegments("https://workers.dev/pub/movie/v1080/seg_000.m4s", 4)
        )
        assertTrue(
            index.isOutsideActiveWindow(
                currentUrl = "https://workers.dev/pub/movie/audio/seg_002.m4s",
                candidateUrl = "https://workers.dev/pub/movie/v1080/seg_000.m4s",
                windowSize = 3
            )
        )

        // Master playlist and #EXT-X-BYTERANGE playlists must be safely skipped
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            v1080/index.m3u8
        """.trimIndent()
        assertTrue(
            com.example.player.HlsSegmentIndex.parseMediaPlaylist(
                "https://workers.dev/master.m3u8",
                masterPlaylist
            ).isEmpty()
        )

        val byteRangePlaylist = """
            #EXTM3U
            #EXT-X-BYTERANGE:1000@0
            single.mp4
        """.trimIndent()
        assertTrue(
            com.example.player.HlsSegmentIndex.parseMediaPlaylist(
                "https://workers.dev/br.m3u8",
                byteRangePlaylist
            ).isEmpty()
        )
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun parallelHlsPrefetchManagerWarmsSegmentsInParallelServesFromRamAndBoundsMemory() {
        val baseUrl = "https://cdn.workers.dev/hls/"
        val playlistUrl = "${baseUrl}index.m3u8"
        val playlistBody = """
            #EXTM3U
            #EXTINF:4.0,
            seg0.m4s
            #EXTINF:4.0,
            seg1.m4s
            #EXTINF:4.0,
            seg2.m4s
            #EXTINF:4.0,
            seg3.m4s
        """.trimIndent().toByteArray()

        val seg0 = ByteArray(1024) { 1 }
        val seg1 = ByteArray(1024) { 2 }
        val seg2 = ByteArray(1024) { 3 }
        val seg3 = ByteArray(1024) { 4 }

        val resources = mapOf(
            playlistUrl to playlistBody,
            "${baseUrl}seg0.m4s" to seg0,
            "${baseUrl}seg1.m4s" to seg1,
            "${baseUrl}seg2.m4s" to seg2,
            "${baseUrl}seg3.m4s" to seg3
        )
        val openCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        val fakeUpstreamFactory = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var data: ByteArray = ByteArray(0)
                private var offset = 0
                private var remaining = 0
                private var currentUri: android.net.Uri? = null

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val key = dataSpec.uri.toString()
                    openCounts.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
                    data = resources[key] ?: throw java.io.IOException("404 $key")
                    offset = dataSpec.position.toInt().coerceAtMost(data.size)
                    remaining = (data.size - offset).coerceAtLeast(0)
                    currentUri = dataSpec.uri
                    return remaining.toLong()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return androidx.media3.common.C.RESULT_END_OF_INPUT
                    val n = minOf(length, remaining)
                    System.arraycopy(data, this.offset, buffer, offset, n)
                    this.offset += n
                    remaining -= n
                    return n
                }

                override fun getUri(): android.net.Uri? = currentUri
                override fun close() {}
            }
        }

        val config = com.example.player.ParallelPrefetchConfig(
            hlsPrefetchAheadCount = 3,
            hlsMaxCacheBytes = 2560, // fits at most 2 segments (2048 B) -> enforces strict RAM cap
            hlsMaxSingleSegmentBytes = 4096,
            progressiveChunkBytes = 4096,
            progressiveLookaheadChunks = 2,
            workerThreads = 3
        )
        val manager = com.example.player.ParallelHlsPrefetchManager(config, fakeUpstreamFactory)
        try {
            val prefetchFactory = manager.createDataSourceFactory()

            // 1. Reading the .m3u8 manifest tees it and warms seg0..seg2 in parallel
            val manifestDs = prefetchFactory.createDataSource()
            manifestDs.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(playlistUrl)))
            val manifestBuf = ByteArray(4096)
            while (manifestDs.read(manifestBuf, 0, manifestBuf.size) != androidx.media3.common.C.RESULT_END_OF_INPUT) {
                // drain
            }
            manifestDs.close()

            // Wait briefly for background workers to complete initial warm-up
            Thread.sleep(250L)

            // Cache must never exceed hlsMaxCacheBytes
            assertTrue(
                "Prefetch RAM must stay <= hlsMaxCacheBytes",
                manager.snapshotStats().cachedBytes <= config.hlsMaxCacheBytes.toLong()
            )

            // 2. Reading seg1 via DataSource hits RAM cache and triggers seg3 prefetch
            val segDs = prefetchFactory.createDataSource()
            val len = segDs.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("${baseUrl}seg1.m4s")))
            assertEquals(1024L, len)
            val out = ByteArray(1024)
            val read = segDs.read(out, 0, out.size)
            segDs.close()
            assertEquals(1024, read)
            assertEquals(2.toByte(), out[0])

            Thread.sleep(200L)
            assertTrue("seg3 must be prefetched in background after reading seg1", (openCounts["${baseUrl}seg3.m4s"]?.get() ?: 0) >= 1)
            assertTrue("Prefetch hits should be recorded", manager.snapshotStats().cacheHits >= 1L)

            manager.resetSession()
            assertEquals(0L, manager.snapshotStats().cachedBytes)
            assertEquals(0L, manager.snapshotStats().cacheHits)
        } finally {
            manager.release()
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun quicThroughputWatchdogDemotesAfterThreeSlowSegmentsAndTransportSelectorWorks() {
        val watchdog = com.example.player.QuicThroughputWatchdog()

        // Tiny < 256 KB manifests/init chunks never trip watchdog
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 32_000L, loadDurationMs = 5_000L))
        assertTrue(!watchdog.isDemoted)

        // Two slow 512 KB chunks (~800 kbps) followed by a fast chunk (~16 Mbps) resets streak
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 1_000_000L, loadDurationMs = 500L))
        assertTrue(!watchdog.isDemoted)

        // Three consecutive slow chunks (~819 kbps < 2 Mbps) trip automatic demotion to HTTP/2 TCP
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(watchdog.isDemoted)

        // Reset restores AUTO HTTP/3 eligibility
        watchdog.reset()
        assertTrue(!watchdog.isDemoted)

        // v1.18: 1 ms RAM prefetch hit between slow network chunks must NOT reset slow streak!
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 1L)) // RAM hit ignored
        assertTrue(!watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(watchdog.recordCompletedSegment(bytesLoaded = 512_000L, loadDurationMs = 5_000L))
        assertTrue(watchdog.isDemoted)

        // v1.18: Pure BandwidthMeter network samples & rebuffer stalls trip fast demotion
        watchdog.reset()
        assertTrue(!watchdog.recordNetworkSample(measuredBps = 507_000L, totalBytesLoaded = 128_000L))
        assertTrue(!watchdog.recordNetworkSample(measuredBps = 620_000L, totalBytesLoaded = 128_000L))
        assertTrue(watchdog.recordNetworkSample(measuredBps = 550_000L, totalBytesLoaded = 128_000L))
        assertTrue(watchdog.isDemoted)

        watchdog.reset()
        assertTrue(!watchdog.recordRebufferStall(currentNetworkBps = 507_000L))
        assertTrue(watchdog.recordRebufferStall(currentNetworkBps = 507_000L))
        assertTrue(watchdog.isDemoted)

        // Verify TracksSheet Network Engine selector updates transport mode
        var selectedMode = com.example.player.NetworkTransportMode.AUTO
        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    preferredTransportMode = selectedMode,
                    activeTransportLabel = com.example.player.StreamingNetworkEngine.TRANSPORT_HTTP2_OKHTTP,
                    onSelectTransportMode = { selectedMode = it },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(180L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tracks_transport_mode_http2_tcp")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(com.example.player.NetworkTransportMode.HTTP2_TCP, selectedMode)
    }

    @Test
    fun streamingPrefetchEntryHandsOffLiveBytesWithoutBlockingAndTvConfigSupports12MbVbrSegments() {
        val tvConfig = com.example.player.ParallelPrefetchConfig.forDevice(isConstrainedOrTv = true)
        assertEquals(2, tvConfig.hlsPrefetchAheadCount)
        assertEquals(12 * 1024 * 1024, tvConfig.hlsMaxSingleSegmentBytes)
        assertEquals(14 * 1024 * 1024, tvConfig.hlsMaxCacheBytes)
        assertTrue(com.example.player.HlsSegmentIndex.isLikelyAudioUrl("https://cdn.example.com/audio/seg_01.m4s"))
        assertTrue(!com.example.player.HlsSegmentIndex.isLikelyAudioUrl("https://cdn.example.com/v1080/seg_01.m4s"))

        val entry = com.example.player.StreamingPrefetchEntry("https://cdn.example.com/v1080/seg_01.m4s")
        val producer = Thread {
            entry.markStarted()
            entry.setHeaders(
                uri = android.net.Uri.parse("https://cdn.example.com/v1080/seg_01.m4s"),
                headers = emptyMap(),
                contentLength = 4096L
            )
            for (i in 0 until 4) {
                entry.appendChunk(ByteArray(1024) { (i + 10).toByte() }, 1024)
                Thread.sleep(25L)
            }
            entry.markCompleted()
        }
        producer.start()

        // Wait briefly until started, then claim live while producer is still streaming
        Thread.sleep(20L)
        val headers = entry.tryClaimAndAwaitHeaders(headersTimeoutMs = 1_500L)
        assertTrue("Consumer should attach live before producer completes", headers != null)
        assertEquals(4096L, headers!!.contentLength)

        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (true) {
            val r = entry.readNext(buf, 0, buf.size, chunkTimeoutMs = 2_000L)
            if (r == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, r)
        }
        producer.join(2_000L)
        val bytes = out.toByteArray()
        assertEquals(4096, bytes.size)
        assertEquals(10.toByte(), bytes[0])
        assertEquals(11.toByte(), bytes[1024])
        assertEquals(12.toByte(), bytes[2048])
        assertEquals(13.toByte(), bytes[3072])
        entry.releaseAfterConsumerClose()
    }
}
