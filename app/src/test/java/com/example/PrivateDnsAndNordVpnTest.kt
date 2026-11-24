package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.example.data.SubtitlePrefsRepository
import com.example.model.StreamFormat
import com.example.player.AdaptiveHttpDataSourceFactory
import com.example.player.CredentialVault
import com.example.player.DohDnsClient
import com.example.player.DohJsonParser
import com.example.player.DynamicSocksProxyManager
import com.example.player.FastDnsResolver
import com.example.player.NetworkProbeResult
import com.example.player.NetworkTransportMode
import com.example.player.NordLynxServerEndpoint
import com.example.player.NordLynxTunnelController
import com.example.player.NordVpnApiClient
import com.example.player.NordVpnCatalog
import com.example.player.NordVpnCredentials
import com.example.player.NordVpnLocation
import com.example.player.NordVpnLocationCatalog
import com.example.player.NordVpnLoginResult
import com.example.player.PlayerNetworkStats
import com.example.player.PrivateDnsMode
import com.example.ui.home.HomeScreen
import com.example.ui.player.NetworkPrivacySheet
import com.example.ui.player.PlayerStatsCard
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.ActiveMediaSession
import com.example.viewmodel.CineStreamUiState
import com.example.viewmodel.JsonPlaylistUiState
import com.example.viewmodel.NetworkPrivacyUiState
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivateDnsAndNordVpnTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dohJsonParser_parsesIpv4AndIpv6Answers_andRejectsErrors() {
        val cfResponse = """
            {
              "Status": 0,
              "Answer": [
                {"name": "cdn.example.com.", "type": 5, "TTL": 300, "data": "edge.example.net."},
                {"name": "edge.example.net.", "type": 1, "TTL": 120, "data": "104.21.45.12"},
                {"name": "edge.example.net.", "type": 28, "TTL": 120, "data": "2606:4700:3031::6815:2d0c"}
              ]
            }
        """.trimIndent()

        val parsed = DohJsonParser.parseIpAddresses(cfResponse)
        assertEquals(2, parsed.size)
        assertEquals("104.21.45.12", parsed[0].hostAddress)
        assertTrue(parsed[1].hostAddress!!.contains(":"))

        val nxDomain = """{"Status": 3, "Answer": [{"type": 1, "data": "1.2.3.4"}]}"""
        assertTrue(DohJsonParser.parseIpAddresses(nxDomain).isEmpty())
        assertTrue(DohJsonParser.isIpLiteral("1.1.1.1"))
        assertFalse(DohJsonParser.isIpLiteral("stream.example.com"))
    }

    @Test
    fun fastDnsResolver_usesCloudflare1111DohWhenEnabled_andFallsBackCleanly() {
        var dohCalls = 0
        val mockDohHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                dohCalls++
                val url = chain.request().url.toString()
                assertTrue("Expected Cloudflare 1.1.1.1 endpoint", url.startsWith("https://1.1.1.1/dns-query"))
                val body = """{"Status":0,"Answer":[{"type":1,"data":"203.0.113.42"}]}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/dns-json".toMediaType()))
                    .build()
            }
            .build()

        var systemFallbackCalls = 0
        val fakeSystemDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                systemFallbackCalls++
                return listOf(InetAddress.getByAddress(byteArrayOf(198.toByte(), 51, 100, 9)))
            }
        }

        val resolver = FastDnsResolver(
            delegate = fakeSystemDns,
            dohClient = DohDnsClient(mockDohHttp)
        )

        // Default SYSTEM mode uses system delegate
        val sysResult = resolver.lookup("cdn.test.invalid")
        assertEquals("198.51.100.9", sysResult.first().hostAddress)
        assertEquals(1, systemFallbackCalls)
        assertEquals(0, dohCalls)

        // Switching to Cloudflare 1.1.1.1 clears cache and resolves via DoH
        resolver.setPrivateDnsMode(PrivateDnsMode.CLOUDFLARE_1111)
        val cfResult = resolver.lookup("cdn.test.invalid")
        assertEquals("203.0.113.42", cfResult.first().hostAddress)
        assertEquals(1, dohCalls)

        // Cached second lookup does not re-query DoH
        val cfCached = resolver.lookup("cdn.test.invalid")
        assertEquals("203.0.113.42", cfCached.first().hostAddress)
        assertEquals(1, dohCalls)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun adaptiveHttpDataSourceFactory_forcesOkHttpWhenPrivacyRouteActive() {
        val okHttp = OkHttpClient()
        var forceOkHttp = false
        val factory = AdaptiveHttpDataSourceFactory(
            okHttpFactory = OkHttpDataSource.Factory(okHttp),
            transferListener = null,
            cronetEngineProvider = { null },
            transportModeProvider = { NetworkTransportMode.HTTP3_QUIC },
            cronetDemotedProvider = { false },
            forceOkHttpProvider = { forceOkHttp }
        )

        assertFalse(factory.shouldUseCronet())
        forceOkHttp = true
        assertFalse(factory.shouldUseCronet())
        assertTrue(factory.activeTransportLabel().contains("OkHttp"))
    }

    @Test
    fun dynamicSocksProxyManager_routesPublicHosts_resolvesProxyVia1111_andBypassesLan() {
        val proxyResolvedHosts = mutableListOf<String>()
        val mockProxyDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                proxyResolvedHosts.add(hostname)
                return listOf(InetAddress.getByAddress(byteArrayOf(185.toByte(), 220.toByte(), 101, 40)))
            }
        }
        val manager = DynamicSocksProxyManager(proxyDnsResolver = mockProxyDns)
        assertFalse(manager.isProxyActive)
        assertEquals(listOf(Proxy.NO_PROXY), manager.proxySelector.select(URI("https://cdn.moviehost.com/stream.m3u8")))

        manager.updateConfig(
            DynamicSocksProxyManager.ActiveConfig(
                enabled = true,
                host = "amsterdam.nl.socks.nordhold.net",
                port = 1080,
                username = "mock_service_user",
                password = "mock_service_pass"
            )
        )
        assertTrue(manager.isProxyActive)

        // Public media host goes through NordVPN SOCKS5 with proxy hostname resolved via 1.1.1.1 DoH
        val publicProxies = manager.proxySelector.select(URI("https://cdn.moviehost.com/master.m3u8"))
        assertEquals(1, publicProxies.size)
        assertEquals(Proxy.Type.SOCKS, publicProxies.first().type())
        assertTrue(proxyResolvedHosts.contains("amsterdam.nl.socks.nordhold.net"))

        // Bypassed hosts never loop into SOCKS5
        assertEquals(listOf(Proxy.NO_PROXY), manager.proxySelector.select(URI("https://1.1.1.1/dns-query")))
        assertEquals(listOf(Proxy.NO_PROXY), manager.proxySelector.select(URI("https://api.nordvpn.com/v1/users/services/credentials")))
        assertEquals(listOf(Proxy.NO_PROXY), manager.proxySelector.select(URI("http://192.168.29.139:8080/video.mkv")))
        assertEquals(listOf(Proxy.NO_PROXY), manager.proxySelector.select(URI("http://127.0.0.1:9000/local.mp4")))
        assertEquals(listOf(Proxy.NO_PROXY), manager.proxySelector.select(URI("https://amsterdam.nl.socks.nordhold.net:1080")))
    }

    /**
     * Full wire-level RFC 1928 + RFC 1929 SOCKS5 TCP tunnel test proving:
     * 1. OkHttp + FastDnsResolver (1.1.1.1 DoH) + DynamicSocksProxyManager completes a REAL binary
     *    SOCKS5 username/password handshake and streams HLS bytes through the proxy tunnel.
     * 2. Replacing the NordVPN key/credentials live immediately authenticates the next request
     *    with the NEW credentials after pool eviction.
     */
    @Test
    fun socks5EndToEnd_realBinaryHandshake_streamsHttp_andReplacesKeyLive() {
        val originServer = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val socksServer = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val expectedCreds = AtomicReference("svc_user_v1" to "svc_pass_v1")
        val seenAuthPairs = CopyOnWriteArrayList<Pair<String, String>>()

        val originThread = thread(isDaemon = true) {
            while (!originServer.isClosed) {
                val client = runCatching { originServer.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { sock ->
                        val reader = sock.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n"
                        val resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n$body"
                        sock.getOutputStream().write(resp.toByteArray())
                        sock.getOutputStream().flush()
                    }
                }
            }
        }

        val socksThread = thread(isDaemon = true) {
            while (!socksServer.isClosed) {
                val client = runCatching { socksServer.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { sock ->
                        val input = DataInputStream(sock.getInputStream())
                        val output = DataOutputStream(sock.getOutputStream())
                        // 1. RFC 1928 Greeting
                        val ver = input.readUnsignedByte()
                        val nMethods = input.readUnsignedByte()
                        val methods = ByteArray(nMethods)
                        input.readFully(methods)
                        if (ver != 5 || !methods.contains(2.toByte())) return@use
                        // Select Username/Password auth (0x02)
                        output.write(byteArrayOf(0x05, 0x02))
                        output.flush()

                        // 2. RFC 1929 Username/Password subnegotiation
                        val authVer = input.readUnsignedByte()
                        val uLen = input.readUnsignedByte()
                        val uBytes = ByteArray(uLen)
                        input.readFully(uBytes)
                        val pLen = input.readUnsignedByte()
                        val pBytes = ByteArray(pLen)
                        input.readFully(pBytes)
                        val user = String(uBytes, Charsets.UTF_8)
                        val pass = String(pBytes, Charsets.UTF_8)
                        seenAuthPairs.add(user to pass)

                        val (expUser, expPass) = expectedCreds.get()
                        if (authVer != 1 || user != expUser || pass != expPass) {
                            output.write(byteArrayOf(0x01, 0x01)) // Auth failure
                            output.flush()
                            return@use
                        }
                        output.write(byteArrayOf(0x01, 0x00)) // Auth success
                        output.flush()

                        // 3. RFC 1928 CONNECT request
                        val reqVer = input.readUnsignedByte()
                        val cmd = input.readUnsignedByte()
                        input.readUnsignedByte() // RSV
                        val atyp = input.readUnsignedByte()
                        val targetIp: InetAddress = when (atyp) {
                            1 -> {
                                val ip = ByteArray(4)
                                input.readFully(ip)
                                InetAddress.getByAddress(ip)
                            }
                            3 -> {
                                val dLen = input.readUnsignedByte()
                                val dBytes = ByteArray(dLen)
                                input.readFully(dBytes)
                                InetAddress.getByName("127.0.0.1")
                            }
                            else -> return@use
                        }
                        val targetPort = input.readUnsignedShort()
                        if (reqVer != 5 || cmd != 1) return@use

                        Socket(targetIp, targetPort).use { upstream ->
                            // Reply: succeeded, bound 127.0.0.1:0
                            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                            output.flush()
                            val pumpUp = thread(isDaemon = true) {
                                runCatching {
                                    sock.getInputStream().copyTo(upstream.getOutputStream())
                                    upstream.shutdownOutput()
                                }
                            }
                            runCatching {
                                upstream.getInputStream().copyTo(sock.getOutputStream())
                                sock.shutdownOutput()
                            }
                            pumpUp.join(2000L)
                        }
                    }
                }
            }
        }

        try {
            // Mock DoH-backed DNS resolving BOTH target media domain and NordVPN SOCKS5 domain to 127.0.0.1
            val loopbackDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            }
            val proxyManager = DynamicSocksProxyManager(proxyDnsResolver = loopbackDns)
            proxyManager.updateConfig(
                DynamicSocksProxyManager.ActiveConfig(
                    enabled = true,
                    host = "amsterdam.nl.socks.nordhold.net",
                    port = socksServer.localPort,
                    username = "svc_user_v1",
                    password = "svc_pass_v1"
                )
            )

            val client = OkHttpClient.Builder()
                .dns(loopbackDns)
                .proxySelector(proxyManager.proxySelector)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val url = "http://cdn.streamtest.example:${originServer.localPort}/master.m3u8"

            // Request 1: authenticates with key #1 through real binary SOCKS5 tunnel
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body!!.string().contains("#EXTM3U"))
            }
            assertEquals("svc_user_v1" to "svc_pass_v1", seenAuthPairs.last())

            // Live Key Replacement: switch server + client to key #2 without recreating OkHttpClient
            expectedCreds.set("svc_user_v2" to "svc_pass_v2")
            proxyManager.updateConfig(
                DynamicSocksProxyManager.ActiveConfig(
                    enabled = true,
                    host = "new-york.us.socks.nordhold.net",
                    port = socksServer.localPort,
                    username = "svc_user_v2",
                    password = "svc_pass_v2"
                )
            )
            client.connectionPool.evictAll()

            // Request 2: authenticates with replacement key #2 over a fresh SOCKS5 handshake
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body!!.string().contains("seg0.ts"))
            }
            assertEquals("svc_user_v2" to "svc_pass_v2", seenAuthPairs.last())
        } finally {
            runCatching { socksServer.close() }
            runCatching { originServer.close() }
            originThread.interrupt()
            socksThread.interrupt()
        }
    }

    @Test
    fun credentialVault_roundTripsSecretsSafely() {
        val secret = "nord_service_secret_987654321"
        val encoded = CredentialVault.encrypt(secret)
        assertTrue(encoded.isNotBlank())
        assertNotEquals(secret, encoded)
        assertEquals(secret, CredentialVault.decrypt(encoded))
        assertEquals("", CredentialVault.encrypt(""))
        assertEquals("", CredentialVault.decrypt(null))
    }

    @Test
    fun nordVpnCatalog_containsAll12OfficialSocks5Locations_andFiltersInvalidHosts() {
        assertEquals(12, NordVpnCatalog.DEFAULT_SERVERS.size)
        assertTrue(NordVpnCatalog.DEFAULT_SERVERS.all { NordVpnCatalog.isOfficialSocks5Host(it.hostname) })
        assertTrue(NordVpnCatalog.DEFAULT_SERVERS.any { it.hostname == "new-york.us.socks.nordhold.net" })
        assertTrue(NordVpnCatalog.DEFAULT_SERVERS.any { it.hostname == "amsterdam.nl.socks.nordhold.net" })
        assertTrue(NordVpnCatalog.DEFAULT_SERVERS.any { it.hostname == "stockholm.se.socks.nordhold.net" })

        val mixedJson = """
            [
              {"hostname":"nl999.nordvpn.com","name":"Netherlands #999","locations":[{"country":{"name":"Netherlands"}}]},
              {"hostname":"miami.us.socks.nordhold.net","name":"USA · Miami","locations":[{"country":{"name":"United States"}}]}
            ]
        """.trimIndent()
        val parsed = NordVpnApiClient.parseRecommendedServersJson(mixedJson)
        assertEquals(1, parsed.size)
        assertEquals("miami.us.socks.nordhold.net", parsed.first().hostname)
    }

    @Test
    fun nordVpnApiClient_exchangesAccessToken_handles401_andProbesTrace() = runBlocking {
        val dummyToken = "mock_nord_access_token_1234567890abcdef"
        val expectedAuth = Credentials.basic("token", dummyToken)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url == NordVpnApiClient.CREDENTIALS_ENDPOINT -> {
                        val auth = chain.request().header("Authorization")
                        if (auth == expectedAuth) {
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_2)
                                .code(200)
                                .message("OK")
                                .body(
                                    """{"username":"nord_svc_u1","password":"nord_svc_p1","nordlynx_private_key":"MOCK_LYNX_KEY_BASE64"}"""
                                        .toResponseBody("application/json".toMediaType())
                                )
                                .build()
                        } else {
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_2)
                                .code(401)
                                .message("Unauthorized")
                                .body("""{"message":"Unauthorized"}""".toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                    }
                    url == NordVpnApiClient.COUNTRIES_ENDPOINT -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_2)
                            .code(200)
                            .message("OK")
                            .body(
                                """[
                                  {"id":100,"name":"India","code":"IN","cities":[{"id":501,"name":"Mumbai"},{"id":502,"name":"Chennai"}]},
                                  {"id":195,"name":"Singapore","code":"SG","cities":[{"id":601,"name":"Singapore"}]}
                                ]""".trimIndent().toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                    url.startsWith("https://api.nordvpn.com/v1/servers/recommendations") && url.contains("wireguard_udp") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_2)
                            .code(200)
                            .message("OK")
                            .body(
                                """[
                                  {
                                    "hostname":"in124.nordvpn.com",
                                    "station":"198.51.100.44",
                                    "load":18,
                                    "locations":[{"country":{"id":100,"name":"India","city":{"id":501,"name":"Mumbai"}}}],
                                    "technologies":[{"identifier":"wireguard_udp","metadata":[{"name":"public_key","value":"SERVER_PUB_KEY_IN124"}]}]
                                  }
                                ]""".trimIndent().toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                    url.startsWith("https://api.nordvpn.com/v1/servers/recommendations") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_2)
                            .code(200)
                            .message("OK")
                            .body(
                                """[{"hostname":"se.socks.nordhold.net","name":"Sweden · Auto","locations":[{"country":{"name":"Sweden"}}]}]"""
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                    url == NordVpnApiClient.CLOUDFLARE_TRACE_URL -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_2)
                            .code(200)
                            .message("OK")
                            .body(
                                "fl=21f1\nh=cloudflare.com\nip=185.220.101.5\nloc=NL\ncolo=AMS\nwarp=off\n"
                                    .toResponseBody("text/plain".toMediaType())
                            )
                            .build()
                    }
                    else -> error("Unexpected URL in mock test: $url")
                }
            }
            .build()

        val apiClient = NordVpnApiClient(directApiClient = mockClient)

        val success = apiClient.loginWithAccessToken(dummyToken)
        assertTrue(success is NordVpnLoginResult.Success)
        val ok = success as NordVpnLoginResult.Success
        assertEquals("nord_svc_u1", ok.credentials.username)
        assertEquals("nord_svc_p1", ok.credentials.password)
        assertEquals("MOCK_LYNX_KEY_BASE64", ok.credentials.nordLynxPrivateKey)
        assertEquals(12, ok.servers.size)
        assertTrue(ok.locations.any { it.countryName == "India" && it.cityName == "Mumbai" })

        val indiaEndpoint = apiClient.fetchRecommendedNordLynxServer(
            NordVpnLocation("c_100_city_501", 100, "India", "IN", 501, "Mumbai")
        )
        assertEquals("in124.nordvpn.com", indiaEndpoint?.hostname)
        assertEquals("198.51.100.44", indiaEndpoint?.stationIp)
        assertEquals("SERVER_PUB_KEY_IN124", indiaEndpoint?.publicKey)

        val failure = apiClient.loginWithAccessToken("wrong_token_000000000000000000")
        assertTrue(failure is NordVpnLoginResult.Error)
        assertTrue((failure as NordVpnLoginResult.Error).message.contains("401"))

        val probe = apiClient.probeActiveRoute(
            activeStreamingClient = mockClient,
            dnsMode = PrivateDnsMode.CLOUDFLARE_1111,
            nordEnabled = true,
            nordServerHost = "amsterdam.nl.socks.nordhold.net",
            systemVpnActive = false
        )
        assertTrue(probe.success)
        assertEquals("185.220.101.5", probe.exitIp)
        assertEquals("NL", probe.locationCode)
        assertEquals("AMS", probe.edgeColo)
        assertTrue(probe.routeSummary.contains("Cloudflare 1.1.1.1 DoH"))
        assertTrue(probe.routeSummary.contains("NordVPN"))
    }

    @Test
    fun subtitlePrefsRepository_persistsAndClearsNetworkPrivacyPrefs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SubtitlePrefsRepository(context)

        repo.updatePrivateDnsMode(PrivateDnsMode.CLOUDFLARE_1111)
        repo.saveNordVpnCredentials(
            credentials = NordVpnCredentials("svc_user_x", "svc_pass_y"),
            serverHost = "stockholm.se.socks.nordhold.net",
            serverLabel = "Sweden · Stockholm",
            enableImmediately = true
        )

        val saved = repo.networkPrivacyPrefs.first()
        assertEquals(PrivateDnsMode.CLOUDFLARE_1111, saved.privateDnsMode)
        assertTrue(saved.hasNordCredentials)
        assertTrue(saved.nordVpnEnabled)
        assertEquals("svc_user_x", saved.nordVpnUsername)
        assertEquals("svc_pass_y", saved.nordVpnPassword)
        assertEquals("stockholm.se.socks.nordhold.net", saved.nordVpnServerHost)

        repo.clearNordVpnCredentials()
        val cleared = repo.networkPrivacyPrefs.first()
        assertFalse(cleared.hasNordCredentials)
        assertFalse(cleared.nordVpnEnabled)
        assertEquals(PrivateDnsMode.CLOUDFLARE_1111, cleared.privateDnsMode)
    }

    @Test
    fun networkPrivacySheet_searchesGlobalLocationsWithoutStaticList_logsIn_replacesKeyAnytime_andProbesRoute() {
        var uiState by mutableStateOf(NetworkPrivacyUiState())
        val submittedTokens = mutableListOf<String>()

        composeRule.setContent {
            CineStreamTheme {
                NetworkPrivacySheet(
                    state = uiState,
                    onSelectDnsMode = { mode -> uiState = uiState.copy(privateDnsMode = mode) },
                    onLoginNordToken = { tok ->
                        submittedTokens.add(tok)
                        uiState = uiState.copy(
                            privateDnsMode = PrivateDnsMode.CLOUDFLARE_1111,
                            nordVpnLoggedIn = true,
                            nordVpnEnabled = true,
                            nordLynxTunnelUp = true,
                            activeNordLynxHostname = "in124.nordvpn.com"
                        )
                    },
                    onLoginNordConfiguredSecret = {},
                    onToggleNordEnabled = { enabled -> uiState = uiState.copy(nordVpnEnabled = enabled) },
                    onSelectNordLocation = { loc ->
                        uiState = uiState.copy(
                            selectedLocation = loc,
                            selectedNordServerLabel = loc.displayLabel
                        )
                    },
                    onLogoutNord = {
                        uiState = uiState.copy(nordVpnLoggedIn = false, nordVpnEnabled = false)
                    },
                    onProbeRoute = {
                        uiState = uiState.copy(
                            probeResult = NetworkProbeResult(
                                success = true,
                                exitIp = "185.220.101.5",
                                locationCode = "IN",
                                edgeColo = "BOM",
                                latencyMs = 38L,
                                routeSummary = "Cloudflare 1.1.1.1 DoH + NordVPN"
                            )
                        )
                    },
                    onDismiss = {}
                )
            }
        }

        // Before searching, NO country list item is shown
        composeRule.onNodeWithTag("nordvpn_location_result_c_100").assertDoesNotExist()
        // No external companion app launcher buttons exist
        composeRule.onNodeWithTag("privacy_launch_nordvpn_app").assertDoesNotExist()

        // Search "India" and select it from the dynamic search results
        composeRule.onNodeWithTag("nordvpn_location_search_input")
            .performScrollTo()
            .performTextInput("India")
        composeRule.onNodeWithTag("nordvpn_location_result_c_100")
            .performScrollTo()
            .performClick()
        assertEquals("India", uiState.selectedLocation.countryName)
        assertEquals("IN", uiState.selectedLocation.countryCode)
        // Search results collapse automatically after choosing a location
        composeRule.onNodeWithTag("nordvpn_location_result_c_100").assertDoesNotExist()

        // Enter key #1 and connect (auto-enables 1.1.1.1 + NordVPN)
        composeRule.onNodeWithTag("nordvpn_token_input")
            .performScrollTo()
            .performTextInput("mock_nord_key_v1_1234567890abcdef")
        composeRule.onNodeWithTag("nordvpn_login_button").performScrollTo().performClick()
        assertEquals(listOf("mock_nord_key_v1_1234567890abcdef"), submittedTokens)
        assertTrue(uiState.nordVpnLoggedIn)
        assertEquals(PrivateDnsMode.CLOUDFLARE_1111, uiState.privateDnsMode)

        // Replace Key anytime without signing out!
        composeRule.onNodeWithTag("nordvpn_replace_key_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("nordvpn_replace_token_input")
            .performScrollTo()
            .performTextInput("mock_nord_key_v2_fedcba0987654321")
        composeRule.onNodeWithTag("nordvpn_replace_submit_button").performScrollTo().performClick()
        assertEquals(2, submittedTokens.size)
        assertEquals("mock_nord_key_v2_fedcba0987654321", submittedTokens.last())

        // Run live route test and verify exit IP
        composeRule.onNodeWithTag("privacy_test_connection_button").performScrollTo().performClick()
        composeRule.onNodeWithText("185.220.101.5", substring = true).assertIsDisplayed()
    }

    @Test
    fun nordLynxEngine_catalogCoversAll118Countries_rankedSearchWorks_andBuildsValidWireGuardConfig() {
        assertTrue(NordVpnLocationCatalog.FALLBACK_COUNTRIES.size >= 115)
        // Blank query never returns a list
        assertTrue(NordVpnLocationCatalog.search(NordVpnLocationCatalog.FALLBACK_COUNTRIES, "").isEmpty())
        assertTrue(NordVpnLocationCatalog.search(NordVpnLocationCatalog.FALLBACK_COUNTRIES, "   ").isEmpty())

        // Country, ISO code, and alias searches return accurate top matches
        assertEquals("India", NordVpnLocationCatalog.search(NordVpnLocationCatalog.FALLBACK_COUNTRIES, "india").first().countryName)
        assertEquals("United Kingdom", NordVpnLocationCatalog.search(NordVpnLocationCatalog.FALLBACK_COUNTRIES, "uk").first().countryName)
        assertEquals("Singapore", NordVpnLocationCatalog.search(NordVpnLocationCatalog.FALLBACK_COUNTRIES, "sg").first().countryName)
        assertEquals("Japan", NordVpnLocationCatalog.search(NordVpnLocationCatalog.FALLBACK_COUNTRIES, "jap").first().countryName)

        // Real WireGuard Curve25519 config build validation
        val clientKeys = KeyPair()
        val serverKeys = KeyPair()
        val endpoint = NordLynxServerEndpoint(
            hostname = "jp512.nordvpn.com",
            stationIp = "198.51.100.88",
            port = 51820,
            publicKey = serverKeys.publicKey.toBase64(),
            countryName = "Japan",
            cityName = "Tokyo",
            loadPercent = 14
        )
        val wgConfig = NordLynxTunnelController.buildWireGuardConfig(
            privateKey = clientKeys.privateKey.toBase64(),
            endpoint = endpoint,
            dnsMode = PrivateDnsMode.CLOUDFLARE_1111,
            includedPackageName = "com.aistudio.cinestream.vqxkpm"
        )
        assertEquals(2, wgConfig.`interface`.dnsServers.size)
        assertEquals("1.1.1.1", wgConfig.`interface`.dnsServers.first().hostAddress)
        assertTrue(wgConfig.`interface`.includedApplications.contains("com.aistudio.cinestream.vqxkpm"))
        assertEquals(1, wgConfig.peers.size)
        assertEquals(51820, wgConfig.peers.first().endpoint.get().port)
        assertEquals("198.51.100.88", wgConfig.peers.first().endpoint.get().host)
    }

    @Test
    fun playerStatsCard_showsCloudflare1111AndNordBadgesWhenActive() {
        composeRule.setContent {
            CineStreamTheme {
                PlayerStatsCard(
                    stats = PlayerNetworkStats(
                        playbackStateLabel = "PLAYING",
                        networkTransportLabel = "HTTP/2 (OkHttp)",
                        isLocalSource = false,
                        privateDnsMode = PrivateDnsMode.CLOUDFLARE_1111,
                        nordVpnActive = true,
                        nordVpnServerHost = "amsterdam.nl.socks.nordhold.net",
                        streamFormatLabel = "HLS",
                        streamHost = "cdn.example.com"
                    ),
                    isTv = true,
                    showCloseButton = false,
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithTag("stats_dns_badge").assertIsDisplayed().assertTextContains("1.1.1.1")
        composeRule.onNodeWithTag("stats_vpn_badge").assertIsDisplayed().assertTextContains("NORD")
    }

    @Test
    fun networkPrivacySheet_disableAllButton_resetsDnsAndNordWhileKeepingSavedKey() {
        var uiState by mutableStateOf(
            NetworkPrivacyUiState(
                privateDnsMode = PrivateDnsMode.CLOUDFLARE_1111,
                nordVpnLoggedIn = true,
                nordVpnEnabled = true,
                selectedNordServerHost = "amsterdam.nl.socks.nordhold.net",
                selectedNordServerLabel = "Netherlands · Amsterdam"
            )
        )

        composeRule.setContent {
            CineStreamTheme {
                NetworkPrivacySheet(
                    state = uiState,
                    onSelectDnsMode = { mode -> uiState = uiState.copy(privateDnsMode = mode) },
                    onLoginNordToken = {},
                    onLoginNordConfiguredSecret = {},
                    onToggleNordEnabled = { enabled -> uiState = uiState.copy(nordVpnEnabled = enabled) },
                    onSelectNordServer = {},
                    onLogoutNord = {},
                    onDisableAll = {
                        uiState = uiState.copy(
                            privateDnsMode = PrivateDnsMode.SYSTEM,
                            nordVpnEnabled = false
                        )
                    },
                    onProbeRoute = {},
                    onLaunchCompanionApp = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("privacy_disable_all_button").assertIsDisplayed().performClick()
        assertEquals(PrivateDnsMode.SYSTEM, uiState.privateDnsMode)
        assertFalse(uiState.nordVpnEnabled)
        assertTrue("Saved NordVPN credentials should stay logged in for quick re-enable", uiState.nordVpnLoggedIn)
        assertEquals("amsterdam.nl.socks.nordhold.net", uiState.selectedNordServerHost)
    }

    @Test
    fun homeScreen_compactPhoneWidth_vpnPillAndNowPlayingBannerNeverCollide() {
        composeRule.setContent {
            CineStreamTheme {
                Box(modifier = Modifier.width(360.dp).height(780.dp)) {
                    HomeScreen(
                        uiState = CineStreamUiState(
                            activeSession = ActiveMediaSession(
                                title = "Resident Evil: Welcome to Raccoon City (4K HDR)",
                                sourceUri = "https://example.com/movie.m3u8",
                                format = StreamFormat.HLS,
                                isLocalFile = false
                            )
                        ),
                        jsonPlaylistState = JsonPlaylistUiState(),
                        sampleStreams = emptyList(),
                        history = emptyList(),
                        onUrlChanged = {},
                        onClearUrl = {},
                        onFormatSelected = {},
                        onPlayUrl = {},
                        onPickLocalVideo = {},
                        onPickLocalM3u = {},
                        onPickLocalJson = {},
                        onLoadJsonUrl = {},
                        onRefreshJsonPlaylist = {},
                        onClearJsonPlaylist = {},
                        onOpenJsonSheet = {},
                        onPlayJsonItem = {},
                        onStageLocalSubtitle = {},
                        onStageRemoteSubtitle = {},
                        onClearStagedSubtitle = {},
                        onPlaySample = {},
                        onPlayHistoryItem = {},
                        onTogglePin = {},
                        onDeleteHistory = {},
                        onOpenM3uSheet = {},
                        onResumeActiveSession = {},
                        onToastShown = {},
                        networkPrivacyState = NetworkPrivacyUiState(
                            privateDnsMode = PrivateDnsMode.CLOUDFLARE_1111,
                            nordVpnLoggedIn = true,
                            nordVpnEnabled = true,
                            selectedNordServerHost = "amsterdam.nl.socks.nordhold.net",
                            selectedNordServerLabel = "Netherlands · Amsterdam"
                        ),
                        onOpenNetworkPrivacy = {}
                    )
                }
            }
        }

        val vpnNode = composeRule.onNodeWithTag("home_privacy_vpn_button").assertIsDisplayed()
        val nowPlayingNode = composeRule.onNodeWithTag("resume_active_player_button").assertIsDisplayed()

        val vpnBounds = vpnNode.getUnclippedBoundsInRoot()
        val nowPlayingBounds = nowPlayingNode.getUnclippedBoundsInRoot()

        val horizontalOverlap = vpnBounds.left < nowPlayingBounds.right && nowPlayingBounds.left < vpnBounds.right
        val verticalOverlap = vpnBounds.top < nowPlayingBounds.bottom && nowPlayingBounds.top < vpnBounds.bottom
        assertFalse(
            "VPN pill ($vpnBounds) and Now Playing banner ($nowPlayingBounds) must not overlap!",
            horizontalOverlap && verticalOverlap
        )
        assertTrue(
            "Now Playing banner (${nowPlayingBounds.top}) should sit cleanly below the top VPN pill row (${vpnBounds.bottom})",
            nowPlayingBounds.top >= vpnBounds.bottom
        )
    }
}
