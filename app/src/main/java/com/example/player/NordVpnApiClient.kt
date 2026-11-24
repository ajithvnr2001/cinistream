package com.example.player

import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class NordVpnCredentials(
    val username: String,
    val password: String,
    val nordLynxPrivateKey: String = ""
)

sealed class NordVpnLoginResult {
    data class Success(
        val credentials: NordVpnCredentials,
        val servers: List<NordVpnServer>,
        val locations: List<NordVpnLocation> = NordVpnLocationCatalog.FALLBACK_COUNTRIES
    ) : NordVpnLoginResult()

    data class Error(val message: String) : NordVpnLoginResult()
}

data class NetworkProbeResult(
    val success: Boolean,
    val exitIp: String = "",
    val locationCode: String = "",
    val edgeColo: String = "",
    val warpStatus: String = "",
    val latencyMs: Long = 0L,
    val routeSummary: String = "",
    val errorMessage: String? = null
)

/**
 * On-device AES-256-GCM credential protection backed by Android Keystore.
 * Encrypts NordVPN service & NordLynx keys before saving them to DataStore; falls back safely on
 * JVM unit test environments without AndroidKeyStore.
 */
object CredentialVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "cinestream_vpn_cred_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENC_PREFIX = "enc:v1:"
    private const val PLAIN_PREFIX = "raw:v1:"
    private const val GCM_TAG_BITS = 128

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return runCatching {
            val key = getOrCreateKey() ?: return@runCatching encodeFallback(plainText)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrElse { encodeFallback(plainText) }
    }

    fun decrypt(storedValue: String?): String {
        if (storedValue.isNullOrBlank()) return ""
        if (storedValue.startsWith(PLAIN_PREFIX)) {
            return runCatching {
                String(
                    Base64.decode(storedValue.removePrefix(PLAIN_PREFIX), Base64.NO_WRAP),
                    Charsets.UTF_8
                )
            }.getOrDefault("")
        }
        if (!storedValue.startsWith(ENC_PREFIX)) return storedValue
        return runCatching {
            val key = getOrCreateKey() ?: return ""
            val combined = Base64.decode(storedValue.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            if (combined.size < 2) return ""
            val ivLen = combined[0].toInt() and 0xFF
            if (ivLen <= 0 || combined.size <= 1 + ivLen) return ""
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun encodeFallback(plainText: String): String {
        return PLAIN_PREFIX + Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun getOrCreateKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }
}

/**
 * Official NordVPN REST API client (HTTPS-only, resolved via Cloudflare 1.1.1.1 DoH):
 * 1. Exchanges a user's NordVPN Access Token (`Authorization: Basic base64("token:<token>")`)
 *    for official NordVPN `nordlynx_private_key` (WireGuard) + service credentials.
 * 2. Fetches all 118+ global NordVPN countries & cities (`/v1/servers/countries`).
 * 3. Queries the fastest live NordLynx (`wireguard_udp`) endpoint + public key for any chosen country/city.
 * 4. Probes the active streaming route (`https://cloudflare.com/cdn-cgi/trace`) to verify real
 *    exit IP, location, and latency.
 */
class NordVpnApiClient(
    private val directApiClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(FastDnsResolver().apply { setPrivateDnsMode(PrivateDnsMode.CLOUDFLARE_1111) })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val CREDENTIALS_ENDPOINT = "https://api.nordvpn.com/v1/users/services/credentials"
        const val COUNTRIES_ENDPOINT = "https://api.nordvpn.com/v1/servers/countries"
        const val SOCKS_RECOMMENDATIONS_ENDPOINT =
            "https://api.nordvpn.com/v1/servers/recommendations?filters%5Bservers_technologies%5D%5Bidentifier%5D=socks&limit=8"
        const val CLOUDFLARE_TRACE_URL = "https://cloudflare.com/cdn-cgi/trace"

        fun wireGuardRecommendationUrl(countryId: Int, limit: Int = 10): String =
            "https://api.nordvpn.com/v1/servers/recommendations" +
                "?filters%5Bservers_technologies%5D%5Bidentifier%5D=wireguard_udp" +
                "&filters%5Bcountry_id%5D=$countryId&limit=$limit"

        fun isPlausibleAccessToken(token: String): Boolean {
            val trimmed = token.trim()
            if (trimmed.length < 16 || trimmed.length > 256) return false
            return trimmed.none { it.isWhitespace() }
        }

        fun parseCredentialsJson(jsonBody: String): NordVpnCredentials? {
            val obj = runCatching { JSONObject(jsonBody) }.getOrNull() ?: return null
            val username = obj.optString("username", "").trim()
            val password = obj.optString("password", "").trim()
            val nordLynxKey = obj.optString("nordlynx_private_key", "").trim()
            if (nordLynxKey.isBlank() && (username.isBlank() || password.isBlank())) return null
            return NordVpnCredentials(
                username = username,
                password = password,
                nordLynxPrivateKey = nordLynxKey
            )
        }

        fun parseCountriesJson(jsonBody: String): List<NordVpnLocation> {
            val arr = runCatching { JSONArray(jsonBody) }.getOrNull() ?: return emptyList()
            val result = ArrayList<NordVpnLocation>(arr.length() * 2)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val countryId = item.optInt("id", -1)
                val countryName = item.optString("name", "").trim()
                val countryCode = item.optString("code", "").trim().uppercase()
                if (countryId <= 0 || countryName.isBlank()) continue
                result.add(
                    NordVpnLocation(
                        id = "c_$countryId",
                        countryId = countryId,
                        countryName = countryName,
                        countryCode = countryCode
                    )
                )
                val cities = item.optJSONArray("cities") ?: continue
                if (cities.length() > 1) {
                    for (c in 0 until cities.length()) {
                        val cityObj = cities.optJSONObject(c) ?: continue
                        val cityId = cityObj.optInt("id", -1)
                        val cityName = cityObj.optString("name", "").trim()
                        if (cityId <= 0 || cityName.isBlank()) continue
                        result.add(
                            NordVpnLocation(
                                id = "c_${countryId}_city_$cityId",
                                countryId = countryId,
                                countryName = countryName,
                                countryCode = countryCode,
                                cityId = cityId,
                                cityName = cityName
                            )
                        )
                    }
                }
            }
            return result
        }

        fun parseNordLynxRecommendationsJson(
            jsonBody: String,
            preferredCityId: Int? = null,
            preferredCityName: String? = null
        ): NordLynxServerEndpoint? {
            val arr = runCatching { JSONArray(jsonBody) }.getOrNull() ?: return null
            val candidates = ArrayList<Triple<Boolean, Int, NordLynxServerEndpoint>>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val stationIp = item.optString("station", "").trim()
                val hostname = item.optString("hostname", "").trim()
                val load = item.optInt("load", 50)
                if (stationIp.isBlank() || hostname.isBlank()) continue

                var pubKey = ""
                val techs = item.optJSONArray("technologies")
                if (techs != null) {
                    for (t in 0 until techs.length()) {
                        val tech = techs.optJSONObject(t) ?: continue
                        if (!tech.optString("identifier", "").equals("wireguard_udp", ignoreCase = true)) continue
                        val metadata = tech.optJSONArray("metadata") ?: continue
                        for (m in 0 until metadata.length()) {
                            val meta = metadata.optJSONObject(m) ?: continue
                            if (meta.optString("name", "").equals("public_key", ignoreCase = true)) {
                                pubKey = meta.optString("value", "").trim()
                                break
                            }
                        }
                        if (pubKey.isNotBlank()) break
                    }
                }
                if (pubKey.isBlank()) continue

                val firstLocation = item.optJSONArray("locations")?.optJSONObject(0)
                val countryObj = firstLocation?.optJSONObject("country")
                val countryName = countryObj?.optString("name", "")?.trim().orEmpty()
                val cityObj = countryObj?.optJSONObject("city")
                val cityId = cityObj?.optInt("id", -1)?.takeIf { it > 0 }
                val cityName = cityObj?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() }

                val matchesCity = (preferredCityId != null && cityId == preferredCityId) ||
                    (!preferredCityName.isNullOrBlank() && cityName.equals(preferredCityName, ignoreCase = true))

                candidates.add(
                    Triple(
                        matchesCity,
                        load,
                        NordLynxServerEndpoint(
                            hostname = hostname,
                            stationIp = stationIp,
                            port = NordLynxServerEndpoint.DEFAULT_WIREGUARD_PORT,
                            publicKey = pubKey,
                            countryName = countryName,
                            cityName = cityName,
                            loadPercent = load
                        )
                    )
                )
            }
            return candidates
                .sortedWith(compareByDescending<Triple<Boolean, Int, NordLynxServerEndpoint>> { it.first }
                    .thenBy { it.second })
                .firstOrNull()
                ?.third
        }

        fun parseRecommendedServersJson(jsonBody: String): List<NordVpnServer> {
            val arr = runCatching { JSONArray(jsonBody) }.getOrNull() ?: return emptyList()
            val result = mutableListOf<NordVpnServer>()
            val seenHosts = HashSet<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val hostname = item.optString("hostname", "").trim()
                if (!NordVpnCatalog.isOfficialSocks5Host(hostname)) continue
                if (!seenHosts.add(hostname.lowercase())) continue
                val name = item.optString("name", "").trim().ifBlank { hostname.substringBefore('.') }
                val locationCountry = item.optJSONArray("locations")
                    ?.optJSONObject(0)
                    ?.optJSONObject("country")
                    ?.optString("name", "")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "NordVPN" }
                result.add(
                    NordVpnServer(
                        id = "nord_${hostname.lowercase().hashCode()}",
                        label = name,
                        country = locationCountry,
                        hostname = hostname,
                        port = NordVpnServer.DEFAULT_SOCKS5_PORT
                    )
                )
            }
            return result
        }

        fun parseCloudflareTrace(traceBody: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            traceBody.lineSequence().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotEmpty()) map[key] = value
                }
            }
            return map
        }
    }

    suspend fun loginWithAccessToken(accessToken: String): NordVpnLoginResult = withContext(Dispatchers.IO) {
        val trimmed = accessToken.trim()
        if (!isPlausibleAccessToken(trimmed)) {
            return@withContext NordVpnLoginResult.Error("Enter a valid NordVPN Access Token.")
        }

        val authHeader = Credentials.basic("token", trimmed)
        val req = Request.Builder()
            .url(CREDENTIALS_ENDPOINT)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
            .build()

        val credentials = try {
            directApiClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> {
                        val body = resp.body?.string().orEmpty()
                        parseCredentialsJson(body)
                            ?: return@withContext NordVpnLoginResult.Error(
                                "NordVPN returned an unexpected credentials response."
                            )
                    }
                    401, 403 -> {
                        return@withContext NordVpnLoginResult.Error(
                            "Invalid or expired NordVPN access token (HTTP ${resp.code}). Generate a fresh token in your Nord Account."
                        )
                    }
                    429 -> {
                        return@withContext NordVpnLoginResult.Error(
                            "NordVPN rate-limited login attempts (HTTP 429). Wait a moment and retry."
                        )
                    }
                    else -> {
                        return@withContext NordVpnLoginResult.Error(
                            "NordVPN login failed (HTTP ${resp.code}). Check network and retry."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext NordVpnLoginResult.Error(
                "Could not reach NordVPN API: ${e.message ?: "network error"}"
            )
        }

        val locations = fetchAllLocations()
        val servers = fetchRecommendedSocksServers()
        NordVpnLoginResult.Success(credentials = credentials, servers = servers, locations = locations)
    }

    suspend fun fetchAllLocations(): List<NordVpnLocation> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(COUNTRIES_ENDPOINT)
            .header("Accept", "application/json")
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
            .build()
        val live = runCatching {
            directApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                parseCountriesJson(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
        NordVpnLocationCatalog.mergeWithLive(live)
    }

    suspend fun fetchRecommendedNordLynxServer(location: NordVpnLocation): NordLynxServerEndpoint? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(wireGuardRecommendationUrl(countryId = location.countryId, limit = 12))
                .header("Accept", "application/json")
                .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                .build()
            runCatching {
                directApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    parseNordLynxRecommendationsJson(
                        jsonBody = resp.body?.string().orEmpty(),
                        preferredCityId = location.cityId,
                        preferredCityName = location.cityName
                    )
                }
            }.getOrNull()
        }

    suspend fun fetchRecommendedSocksServers(): List<NordVpnServer> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(SOCKS_RECOMMENDATIONS_ENDPOINT)
            .header("Accept", "application/json")
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
            .build()

        val live = runCatching {
            directApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                parseRecommendedServersJson(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())

        val merged = LinkedHashMap<String, NordVpnServer>()
        NordVpnCatalog.DEFAULT_SERVERS.forEach { merged[it.hostname.lowercase()] = it }
        live.forEach { merged.putIfAbsent(it.hostname.lowercase(), it) }
        merged.values.toList()
    }

    /**
     * Tests the LIVE active OkHttp streaming pipeline (including Cloudflare 1.1.1.1 DoH and
     * NordVPN SOCKS5 proxy if enabled) by querying Cloudflare's edge trace endpoint.
     */
    suspend fun probeActiveRoute(
        activeStreamingClient: OkHttpClient,
        dnsMode: PrivateDnsMode,
        nordEnabled: Boolean,
        nordServerHost: String,
        systemVpnActive: Boolean
    ): NetworkProbeResult = withContext(Dispatchers.IO) {
        val startMs = SystemClock.elapsedRealtime()
        val probeClient = activeStreamingClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val routeParts = buildList {
            if (dnsMode != PrivateDnsMode.SYSTEM) add("${dnsMode.label} DoH")
            if (nordEnabled && nordServerHost.isNotBlank()) add("NordVPN ($nordServerHost)")
            if (systemVpnActive) add("System VPN")
            if (isEmpty()) add("Direct ISP")
        }
        val routeSummary = routeParts.joinToString(" + ")

        val req = Request.Builder()
            .url(CLOUDFLARE_TRACE_URL)
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
            .build()

        try {
            probeClient.newCall(req).execute().use { resp ->
                val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                if (!resp.isSuccessful) {
                    return@withContext NetworkProbeResult(
                        success = false,
                        latencyMs = latency,
                        routeSummary = routeSummary,
                        errorMessage = "Route test returned HTTP ${resp.code}"
                    )
                }
                val parsed = parseCloudflareTrace(resp.body?.string().orEmpty())
                val ip = parsed["ip"].orEmpty()
                if (ip.isBlank()) {
                    return@withContext NetworkProbeResult(
                        success = false,
                        latencyMs = latency,
                        routeSummary = routeSummary,
                        errorMessage = "Empty trace response"
                    )
                }
                NetworkProbeResult(
                    success = true,
                    exitIp = ip,
                    locationCode = parsed["loc"].orEmpty(),
                    edgeColo = parsed["colo"].orEmpty(),
                    warpStatus = parsed["warp"].orEmpty(),
                    latencyMs = latency,
                    routeSummary = routeSummary
                )
            }
        } catch (e: Exception) {
            val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            NetworkProbeResult(
                success = false,
                latencyMs = latency,
                routeSummary = routeSummary,
                errorMessage = e.message ?: "Connection failed through $routeSummary"
            )
        }
    }
}
