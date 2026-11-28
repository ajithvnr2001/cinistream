package com.example

import android.content.Context
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.NetworkServerEntity
import com.example.model.ExternalSubtitle
import com.example.model.StreamFormat
import com.example.player.CredentialVault
import com.example.player.LocalNetworkClient
import com.example.player.NetworkBrowserItem
import com.example.ui.player.LocalNetworkSheet
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.LocalNetworkUiState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiAccountWebDavTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun roomDatabaseV4_storesUpdatesAndDeletesMultipleAccountsOnSameWebDavUrl(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.networkServerDao()
            val id1 = dao.upsert(
                NetworkServerEntity(
                    name = "TorBox Personal",
                    serverType = LocalNetworkClient.TYPE_WEBDAV,
                    baseUrl = "https://webdav.torbox.app",
                    usernameEnc = CredentialVault.encrypt("alice@example.test"),
                    secretEnc = CredentialVault.encrypt("synthetic-pass-alpha")
                )
            )
            val id2 = dao.upsert(
                NetworkServerEntity(
                    name = "TorBox Family",
                    serverType = LocalNetworkClient.TYPE_WEBDAV,
                    baseUrl = "https://webdav.torbox.app",
                    usernameEnc = CredentialVault.encrypt("bob@example.test"),
                    secretEnc = CredentialVault.encrypt("synthetic-pass-beta")
                )
            )

            assertTrue(id1 > 0L)
            assertTrue(id2 > 0L)
            assertTrue(id1 != id2)

            val saved = dao.observeServers().first()
            assertEquals(2, saved.size)
            assertEquals(
                setOf("alice@example.test", "bob@example.test"),
                saved.map { CredentialVault.decrypt(it.usernameEnc) }.toSet()
            )

            // Update account #2 label without replacing account #1
            val target = saved.first { it.id == id2 }
            dao.update(target.copy(name = "TorBox 4K"))
            val afterUpdate = dao.observeServers().first()
            assertEquals(2, afterUpdate.size)
            assertEquals("TorBox 4K", afterUpdate.first { it.id == id2 }.name)

            // Delete account #1 and verify account #2 remains intact
            dao.deleteById(id1)
            val afterDelete = dao.observeServers().first()
            assertEquals(1, afterDelete.size)
            assertEquals(id2, afterDelete[0].id)
        } finally {
            db.close()
        }
    }

    @Test
    fun localNetworkClient_normalizesUrlsPreservesSpecialCharPasswordsAndResolvesTrickyHrefs() {
        // Strict HTTP(S) allowlist & smart defaults
        assertEquals("https://webdav.torbox.app", LocalNetworkClient.normalizeBaseUrl("https://webdav.torbox.app/"))
        assertEquals("https://webdav.torbox.app", LocalNetworkClient.normalizeBaseUrl("webdav.torbox.app"))
        assertEquals("http://192.168.1.5:8080/dav", LocalNetworkClient.normalizeBaseUrl("192.168.1.5:8080/dav/"))
        assertEquals("", LocalNetworkClient.normalizeBaseUrl("file:///etc/passwd"))
        assertEquals("", LocalNetworkClient.normalizeBaseUrl("javascript:alert(1)"))
        assertEquals("", LocalNetworkClient.normalizeBaseUrl("ftp://nas.local/media"))

        // Same-origin enforcement
        assertTrue(LocalNetworkClient.isSameOrigin("https://webdav.torbox.app/a", "https://webdav.torbox.app/b/"))
        assertFalse(LocalNetworkClient.isSameOrigin("https://webdav.torbox.app/a", "https://cdn.storage.example/file.mkv"))
        assertFalse(LocalNetworkClient.isSameOrigin("https://webdav.torbox.app/a", "http://webdav.torbox.app/a"))
        assertFalse(LocalNetworkClient.isSameOrigin("http://127.0.0.1:8080/a", "http://127.0.0.1:8081/a"))

        // Byte-for-byte UTF-8 Basic Auth preservation with special punctuation & edge whitespace
        val syntheticUser = "user+test@example.test"
        val syntheticPass = " !?^$)/;{([#&+=~ "
        val header = LocalNetworkClient.buildBasicAuthHeader(syntheticUser, syntheticPass)
        assertNotNull(header)
        assertTrue(header!!.startsWith("Basic "))
        val decoded = String(
            Base64.decode(header.removePrefix("Basic "), Base64.DEFAULT),
            Charsets.UTF_8
        )
        assertEquals("$syntheticUser:$syntheticPass", decoded)

        // Tricky WebDAV href resolution (unencoded spaces, brackets, #, non-ASCII, pre-encoded %20)
        val resolvedBracket = LocalNetworkClient.resolveWebDavHref(
            "https://webdav.torbox.app/Movies/",
            "/Movies/Dune Part Two [2024] {HDR} #1.mkv"
        )
        assertEquals(
            "https://webdav.torbox.app/Movies/Dune%20Part%20Two%20%5B2024%5D%20%7BHDR%7D%20%231.mkv",
            resolvedBracket
        )
        val resolvedPreEncoded = LocalNetworkClient.resolveWebDavHref(
            "https://webdav.torbox.app/Movies/",
            "/Movies/Already%20Encoded.mp4"
        )
        assertEquals("https://webdav.torbox.app/Movies/Already%20Encoded.mp4", resolvedPreEncoded)

        // File size formatting
        assertEquals("", LocalNetworkClient.formatFileSize(0L))
        assertEquals("512 B", LocalNetworkClient.formatFileSize(512L))
        assertEquals("250 KB", LocalNetworkClient.formatFileSize(250L * 1024L))
        assertEquals("1.50 GB", LocalNetworkClient.formatFileSize(1_610_612_736L))
    }

    @Test
    fun localNetworkClient_propfindFollowsSameOriginRedirectParsesMediaSubtitlesAndStripsCrossOriginAuth() = runBlocking {
        val capturedMethodAfterRedirect = AtomicReference<String?>(null)
        val capturedDepthAfterRedirect = AtomicReference<String?>(null)
        val capturedAuthAfterRedirect = AtomicReference<String?>(null)
        val crossOriginReceivedAuth = AtomicReference<String?>("UNSET")

        val syntheticUser = "tester@example.test"
        val syntheticPass = "Synthetic?Pass^123/{}("
        val expectedAuth = LocalNetworkClient.buildBasicAuthHeader(syntheticUser, syntheticPass)!!

        // Secondary server on a different port to prove cross-origin redirects NEVER leak Authorization
        val externalServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        externalServer.createContext("/public/") { exchange ->
            crossOriginReceivedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/public/</D:href>
                    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/public/PublicClip.mp4</D:href>
                    <D:propstat><D:prop><D:displayname>PublicClip.mp4</D:displayname><D:getcontentlength>1048576</D:getcontentlength></D:prop></D:propstat>
                  </D:response>
                </D:multistatus>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
            exchange.sendResponseHeaders(207, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        externalServer.start()

        val primaryServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        primaryServer.createContext("/redirect/") { exchange ->
            exchange.responseHeaders.add("Location", "/dav/")
            exchange.sendResponseHeaders(301, -1)
            exchange.close()
        }
        primaryServer.createContext("/cross/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${externalServer.address.port}/public/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        primaryServer.createContext("/dav/") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            if (auth != expectedAuth) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            capturedMethodAfterRedirect.set(exchange.requestMethod)
            capturedDepthAfterRedirect.set(exchange.requestHeaders.getFirst("Depth"))
            capturedAuthAfterRedirect.set(auth)

            val xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/dav/</D:href>
                    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/Movies [4K]/</D:href>
                    <D:propstat>
                      <D:prop><D:displayname>Movies [4K]</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                    <D:propstat>
                      <D:prop><D:displayname/><D:getcontentlength/></D:prop>
                      <D:status>HTTP/1.1 404 Not Found</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/Dune Part Two [2024].mkv</D:href>
                    <D:propstat>
                      <D:prop><D:displayname>Dune Part Two [2024].mkv</D:displayname><D:getcontentlength>4800000000</D:getcontentlength></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/Dune Part Two [2024].srt</D:href>
                    <D:propstat>
                      <D:prop><D:displayname>Dune Part Two [2024].srt</D:displayname><D:getcontentlength>52000</D:getcontentlength></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/LiveStream.m3u8</D:href>
                    <D:propstat>
                      <D:prop><D:displayname>LiveStream.m3u8</D:displayname><D:getcontentlength>2048</D:getcontentlength></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/Score.flac</D:href>
                    <D:propstat>
                      <D:prop><D:displayname>Score.flac</D:displayname><D:getcontentlength>35000000</D:getcontentlength></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/opaque_video_id</D:href>
                    <D:propstat>
                      <D:prop><D:displayname>Trailer</D:displayname><D:getcontenttype>video/mp4</D:getcontenttype><D:getcontentlength>12000000</D:getcontentlength></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/README.nfo</D:href>
                    <D:propstat>
                      <D:prop><D:displayname>README.nfo</D:displayname><D:getcontenttype>text/plain</D:getcontenttype></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
            exchange.sendResponseHeaders(207, xml.size.toLong())
            exchange.responseBody.use { it.write(xml) }
        }
        primaryServer.start()

        try {
            val client = LocalNetworkClient(OkHttpClient())
            val port = primaryServer.address.port

            // 1. Same-origin 301 redirect preserves PROPFIND, Depth: 1, and Authorization
            val serverEntity = NetworkServerEntity(
                id = 1L,
                name = "Test WebDAV",
                serverType = LocalNetworkClient.TYPE_WEBDAV,
                baseUrl = "http://127.0.0.1:$port/redirect",
                usernameEnc = CredentialVault.encrypt(syntheticUser),
                secretEnc = CredentialVault.encrypt(syntheticPass)
            )
            val browseResult = client.browse(serverEntity)
            assertNull(browseResult.errorMessage)
            assertEquals("PROPFIND", capturedMethodAfterRedirect.get())
            assertEquals("1", capturedDepthAfterRedirect.get())
            assertEquals(expectedAuth, capturedAuthAfterRedirect.get())

            // Expect: 1 folder + 4 playable files (MKV, HLS, FLAC, video/mp4); README.nfo & self & standalone .srt filtered out
            assertEquals(5, browseResult.items.size)
            assertTrue(browseResult.items[0].isFolder)
            assertEquals("Movies [4K]", browseResult.items[0].name)

            val duneItem = browseResult.items.first { it.name.contains("Dune") }
            assertEquals(StreamFormat.MKV, duneItem.format)
            assertEquals(4_800_000_000L, duneItem.sizeBytes)
            assertEquals(expectedAuth, duneItem.requestHeaders["Authorization"])
            assertEquals(1, duneItem.siblingSubtitles.size)
            assertTrue(duneItem.siblingSubtitles[0].uri.endsWith(".srt"))

            val hlsItem = browseResult.items.first { it.name == "LiveStream.m3u8" }
            assertEquals(StreamFormat.M3U8, hlsItem.format)

            val mimeVideo = browseResult.items.first { it.name == "Trailer" }
            assertEquals(StreamFormat.MP4, mimeVideo.format)

            // 2. testConnection returns readable summary on success & 401 message on bad password
            val okTest = client.testConnection(
                baseUrl = "http://127.0.0.1:$port/dav/",
                serverType = LocalNetworkClient.TYPE_WEBDAV,
                username = syntheticUser,
                secret = syntheticPass
            )
            assertTrue(okTest.isSuccess)
            assertEquals(5, okTest.itemCount)
            assertTrue(okTest.message.contains("1 folder"))
            assertTrue(okTest.message.contains("4 playable files"))

            val badAuthTest = client.testConnection(
                baseUrl = "http://127.0.0.1:$port/dav/",
                serverType = LocalNetworkClient.TYPE_WEBDAV,
                username = syntheticUser,
                secret = "wrong-password"
            )
            assertFalse(badAuthTest.isSuccess)
            assertTrue(badAuthTest.message.contains("401"))

            // 3. Cross-origin 302 redirect MUST strip Authorization from both request and resulting item headers
            val crossEntity = NetworkServerEntity(
                id = 2L,
                name = "Cross Redirect",
                serverType = LocalNetworkClient.TYPE_WEBDAV,
                baseUrl = "http://127.0.0.1:$port/cross",
                usernameEnc = CredentialVault.encrypt(syntheticUser),
                secretEnc = CredentialVault.encrypt(syntheticPass)
            )
            val crossResult = client.browse(crossEntity)
            assertNull(crossResult.errorMessage)
            assertNull("Authorization header leaked across origins!", crossOriginReceivedAuth.get())
            assertEquals(1, crossResult.items.size)
            assertNull(crossResult.items[0].requestHeaders["Authorization"])
        } finally {
            primaryServer.stop(0)
            externalServer.stop(0)
        }
    }

    @Test
    fun localNetworkSheet_supportsTorBoxPresetMultipleAccountsEditingAndQuickSwitching() {
        val acc1 = NetworkServerEntity(
            id = 1L,
            name = "TorBox Personal",
            serverType = LocalNetworkClient.TYPE_WEBDAV,
            baseUrl = "https://webdav.torbox.app",
            usernameEnc = CredentialVault.encrypt("alice@example.test"),
            secretEnc = CredentialVault.encrypt("synthetic-1")
        )
        val acc2 = NetworkServerEntity(
            id = 2L,
            name = "TorBox Family",
            serverType = LocalNetworkClient.TYPE_WEBDAV,
            baseUrl = "https://webdav.torbox.app",
            usernameEnc = CredentialVault.encrypt("bob@example.test"),
            secretEnc = CredentialVault.encrypt("synthetic-2")
        )

        var addedUrl = ""
        var updatedId = -1L

        composeRule.setContent {
            CineStreamTheme {
                LocalNetworkSheet(
                    servers = listOf(acc1, acc2),
                    browserState = LocalNetworkUiState(),
                    onAddServer = { _, _, baseUrl, _, _ -> addedUrl = baseUrl },
                    onUpdateServer = { id, _, _, _, _, _ -> updatedId = id },
                    onTestConnection = { _, _, _, _ -> },
                    onClearConnectionTest = {},
                    onDeleteServer = {},
                    onOpenServer = {},
                    onBrowseItem = {},
                    onNavigateUp = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Both accounts on the same TorBox URL are listed with distinct usernames
        composeRule.onNodeWithTag("saved_network_server_1").assertIsDisplayed()
        composeRule.onNodeWithTag("saved_network_server_2").assertIsDisplayed()
        composeRule.onNodeWithText("WEBDAV · alice@example.test").assertIsDisplayed()
        composeRule.onNodeWithText("WEBDAV · bob@example.test").assertIsDisplayed()

        // Edit account #2 pre-populates form and triggers onUpdateServer
        composeRule.onNodeWithTag("edit_network_server_2").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("network_server_name_input").assertTextContains("TorBox Family")
        composeRule.onNodeWithTag("network_server_username_input").assertTextContains("bob@example.test")
        composeRule.onNodeWithTag("network_save_connect_button").performScrollTo().performClick()
        assertEquals(2L, updatedId)

        // Add new account using TorBox 1-tap preset fills official URL and saves
        composeRule.onNodeWithTag("local_network_add_tab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("network_preset_torbox").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("network_server_url_input").assertTextContains("https://webdav.torbox.app/")
        composeRule.onNodeWithTag("network_save_connect_button").performScrollTo().performClick()
        assertEquals(LocalNetworkClient.TORBOX_WEBDAV_URL, addedUrl)
    }

    @Test
    fun localNetworkSheet_activeBrowserSwitchesAccountsRefreshesAndPlaysWithSubtitles() {
        val acc1 = NetworkServerEntity(
            id = 1L,
            name = "TorBox #1",
            serverType = LocalNetworkClient.TYPE_WEBDAV,
            baseUrl = "https://webdav.torbox.app"
        )
        val acc2 = NetworkServerEntity(
            id = 2L,
            name = "TorBox #2",
            serverType = LocalNetworkClient.TYPE_WEBDAV,
            baseUrl = "https://webdav.torbox.app"
        )
        val playable = NetworkBrowserItem(
            id = "https://webdav.torbox.app/Movies/Arrival.mkv",
            name = "Arrival.mkv",
            isFolder = false,
            pathOrParentId = "https://webdav.torbox.app/Movies/Arrival.mkv",
            playbackUrl = "https://webdav.torbox.app/Movies/Arrival.mkv",
            format = StreamFormat.MKV,
            sizeBytes = 3_221_225_472L,
            requestHeaders = mapOf("Authorization" to "Basic c3ludGhldGlj"),
            siblingSubtitles = listOf(
                ExternalSubtitle(
                    id = "sub1",
                    label = "Arrival",
                    uri = "https://webdav.torbox.app/Movies/Arrival.srt",
                    mimeType = "application/x-subrip",
                    formatBadge = "SRT",
                    isLocalFile = false
                )
            )
        )

        var switchedToId = -1L
        var refreshed = false
        var playedItem: NetworkBrowserItem? = null

        composeRule.setContent {
            CineStreamTheme {
                LocalNetworkSheet(
                    servers = listOf(acc1, acc2),
                    browserState = LocalNetworkUiState(
                        activeServer = acc1,
                        breadcrumbPath = listOf("TorBox #1" to ""),
                        items = listOf(playable)
                    ),
                    onAddServer = { _, _, _, _, _ -> },
                    onDeleteServer = {},
                    onOpenServer = { switchedToId = it.id },
                    onBrowseItem = { playedItem = it },
                    onNavigateUp = {},
                    onRefreshFolder = { refreshed = true },
                    onCloseActiveServer = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("webdav_account_switcher_row").assertIsDisplayed()
        composeRule.onNodeWithTag("switch_network_account_2").performClick()
        assertEquals(2L, switchedToId)

        composeRule.onNodeWithTag("network_browser_refresh_button").performClick()
        assertTrue(refreshed)

        val itemCard = composeRule.onNodeWithTag("network_browser_item_${playable.id.hashCode()}")
        itemCard.assertIsDisplayed()
        itemCard.assertTextContains("3.00 GB", substring = true)
        itemCard.assertTextContains("+1 sub", substring = true)
        itemCard.performClick()
        val selected = playedItem
        assertNotNull(selected)
        assertEquals("Basic c3ludGhldGlj", selected!!.requestHeaders["Authorization"])
        assertEquals(1, selected.siblingSubtitles.size)
    }

    @Test
    fun buildConfig_matchesVersion35() {
        assertEquals(39, BuildConfig.VERSION_CODE)
        assertEquals("1.39", BuildConfig.VERSION_NAME)
    }
}
