package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
