package com.example.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.Authenticator
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app Encrypted DNS-over-HTTPS (DoH) resolver modes.
 * Works on both Android Mobile and Android 9+ TVs without requiring a system VpnService.
 */
enum class PrivateDnsMode(
    val label: String,
    val shortBadge: String,
    val description: String,
    val primaryEndpoint: String?,
    val fallbackEndpoint: String?
) {
    SYSTEM(
        label = "System DNS",
        shortBadge = "",
        description = "Device / router default DNS with IPv4-first 5-min cache",
        primaryEndpoint = null,
        fallbackEndpoint = null
    ),
    CLOUDFLARE_1111(
        label = "Cloudflare 1.1.1.1",
        shortBadge = "1.1.1.1",
        description = "Encrypted HTTPS DNS via 1.1.1.1 / 1.0.0.1 (bypasses ISP DNS filtering)",
        primaryEndpoint = "https://1.1.1.1/dns-query",
        fallbackEndpoint = "https://1.0.0.1/dns-query"
    ),
    GOOGLE_8888(
        label = "Google 8.8.8.8",
        shortBadge = "8.8.8.8",
        description = "Encrypted HTTPS DNS via 8.8.8.8 / 8.8.4.4",
        primaryEndpoint = "https://8.8.8.8/resolve",
        fallbackEndpoint = "https://8.8.4.4/resolve"
    )
}

/**
 * Pure RFC 8484 JSON DNS-over-HTTPS response parser (works identically with Cloudflare 1.1.1.1
 * and Google 8.8.8.8 JSON DoH APIs). Converts numeric IPv4/IPv6 literals to [InetAddress]
 * without triggering any blocking OS reverse-DNS lookup.
 */
object DohJsonParser {
    private const val DNS_TYPE_A = 1
    private const val DNS_TYPE_AAAA = 28

    fun parseIpAddresses(jsonBody: String): List<InetAddress> {
        val root = runCatching { JSONObject(jsonBody) }.getOrNull() ?: return emptyList()
        if (root.optInt("Status", 0) != 0) return emptyList()
        val answers = root.optJSONArray("Answer") ?: return emptyList()
        val result = ArrayList<InetAddress>(answers.length())
        for (i in 0 until answers.length()) {
            val entry = answers.optJSONObject(i) ?: continue
            val type = entry.optInt("type", -1)
            if (type != DNS_TYPE_A && type != DNS_TYPE_AAAA) continue
            val data = entry.optString("data", "").trim()
            if (data.isBlank()) continue
            val addr = parseNumericAddress(data, expectIpv4 = type == DNS_TYPE_A)
            if (addr != null) result.add(addr)
        }
        return result
    }

    internal fun parseNumericAddress(ipLiteral: String, expectIpv4: Boolean): InetAddress? {
        return if (expectIpv4) {
            parseIpv4Literal(ipLiteral)
        } else {
            // Only pass strictly validated hex/colon IPv6 literals so InetAddress.getByName never hits OS DNS
            if (!ipLiteral.contains(':') || !ipLiteral.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return null
            }
            runCatching { InetAddress.getByName(ipLiteral) as? Inet6Address }.getOrNull()
        }
    }

    internal fun parseIpv4Literal(ip: String): Inet4Address? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val octet = parts[i].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            bytes[i] = octet.toByte()
        }
        return runCatching { InetAddress.getByAddress(bytes) as? Inet4Address }.getOrNull()
    }

    fun isIpLiteral(hostname: String): Boolean {
        val trimmed = hostname.trim()
        if (parseIpv4Literal(trimmed) != null) return true
        return trimmed.contains(':') && trimmed.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '[' || it == ']'
        }
    }
}

/**
 * Lightweight direct-IP DNS-over-HTTPS resolver client.
 * Uses direct bootstrap IPs (`1.1.1.1` / `1.0.0.1` / `8.8.8.8`) and `Proxy.NO_PROXY` so DoH
 * lookups never trigger circular DNS or proxy deadlocks.
 */
class DohDnsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
) {
    fun resolve(hostname: String, mode: PrivateDnsMode): List<InetAddress> {
        val primary = mode.primaryEndpoint ?: return emptyList()
        val cleanHost = hostname.trim()
        if (cleanHost.isBlank() || DohJsonParser.isIpLiteral(cleanHost)) return emptyList()

        // Prefer IPv4 A records first (eliminates dual-stack IPv6 stalls on Android 9 TVs / JioFiber)
        val ipv4 = queryRecord(primary, cleanHost, "A")
            .ifEmpty { mode.fallbackEndpoint?.let { queryRecord(it, cleanHost, "A") }.orEmpty() }
        if (ipv4.isNotEmpty()) {
            return ipv4
        }

        // Fall back to IPv6 AAAA records on IPv6-only hosts
        return queryRecord(primary, cleanHost, "AAAA")
            .ifEmpty { mode.fallbackEndpoint?.let { queryRecord(it, cleanHost, "AAAA") }.orEmpty() }
    }

    private fun queryRecord(endpoint: String, hostname: String, recordType: String): List<InetAddress> {
        val encodedName = Uri.encode(hostname)
        val url = "$endpoint?name=$encodedName&type=$recordType"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                DohJsonParser.parseIpAddresses(body)
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Represents a NordVPN SOCKS5 proxy exit server (`port 1080`).
 */
data class NordVpnServer(
    val id: String,
    val label: String,
    val country: String,
    val hostname: String,
    val port: Int = DEFAULT_SOCKS5_PORT
) {
    companion object {
        const val DEFAULT_SOCKS5_PORT = 1080
    }
}

object NordVpnCatalog {
    /**
     * All official NordVPN SOCKS5 proxy locations (`*.socks.nordhold.net`, port 1080).
     */
    val DEFAULT_SERVERS: List<NordVpnServer> = listOf(
        NordVpnServer("nl_ams", "Netherlands · Amsterdam", "Netherlands", "amsterdam.nl.socks.nordhold.net"),
        NordVpnServer("nl_auto", "Netherlands · Auto", "Netherlands", "nl.socks.nordhold.net"),
        NordVpnServer("se_sto", "Sweden · Stockholm", "Sweden", "stockholm.se.socks.nordhold.net"),
        NordVpnServer("se_auto", "Sweden · Auto", "Sweden", "se.socks.nordhold.net"),
        NordVpnServer("us_any", "USA · Smart Route", "United States", "us.socks.nordhold.net"),
        NordVpnServer("us_nyc", "USA · New York", "United States", "new-york.us.socks.nordhold.net"),
        NordVpnServer("us_atl", "USA · Atlanta", "United States", "atlanta.us.socks.nordhold.net"),
        NordVpnServer("us_chi", "USA · Chicago", "United States", "chicago.us.socks.nordhold.net"),
        NordVpnServer("us_dal", "USA · Dallas", "United States", "dallas.us.socks.nordhold.net"),
        NordVpnServer("us_phx", "USA · Phoenix", "United States", "phoenix.us.socks.nordhold.net"),
        NordVpnServer("us_lax", "USA · Los Angeles", "United States", "los-angeles.us.socks.nordhold.net"),
        NordVpnServer("us_sfo", "USA · San Francisco", "United States", "san-francisco.us.socks.nordhold.net")
    )

    fun isOfficialSocks5Host(hostname: String): Boolean {
        val lower = hostname.trim().lowercase(Locale.ROOT)
        return isValidHostname(lower) && lower.endsWith(".socks.nordhold.net")
    }

    fun findByHostname(hostname: String?, available: List<NordVpnServer> = DEFAULT_SERVERS): NordVpnServer {
        if (hostname.isNullOrBlank()) return available.firstOrNull() ?: DEFAULT_SERVERS.first()
        return available.firstOrNull { it.hostname.equals(hostname.trim(), ignoreCase = true) }
            ?: DEFAULT_SERVERS.firstOrNull { it.hostname.equals(hostname.trim(), ignoreCase = true) }
            ?: NordVpnServer(
                id = "custom_${hostname.trim().lowercase(Locale.ROOT).hashCode()}",
                label = hostname.trim().substringBefore('.').replaceFirstChar { it.uppercase() },
                country = "NordVPN",
                hostname = hostname.trim()
            )
    }

    fun isValidHostname(host: String): Boolean {
        val trimmed = host.trim()
        if (trimmed.isBlank() || trimmed.length > 253) return false
        return trimmed.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }
    }
}

/**
 * Runtime-switchable SOCKS5 ProxySelector + scoped RFC 1929 Authenticator for NordVPN routing
 * inside OkHttp without recreating OkHttpClient or requiring Android VpnService privileges.
 *
 * Resolves the SOCKS5 proxy hostname through [proxyDnsResolver] (e.g., Cloudflare 1.1.1.1 DoH)
 * so ISP DNS blocks on `*.socks.nordhold.net` never break the tunnel.
 */
class DynamicSocksProxyManager(
    private val proxyDnsResolver: Dns? = null
) {

    data class ActiveConfig(
        val enabled: Boolean = false,
        val host: String = "",
        val port: Int = NordVpnServer.DEFAULT_SOCKS5_PORT,
        val username: String = "",
        val password: String = ""
    ) {
        val isActive: Boolean
            get() = enabled && host.isNotBlank() && port in 1..65535 &&
                username.isNotBlank() && password.isNotBlank()
    }

    companion object {
        private val BYPASS_HOSTS = setOf(
            "localhost",
            "127.0.0.1",
            "::1",
            "1.1.1.1",
            "1.0.0.1",
            "8.8.8.8",
            "8.8.4.4",
            "api.nordvpn.com"
        )

        private val authenticatorInstalled = AtomicBoolean(false)

        @Volatile
        private var activeManagerForAuth: DynamicSocksProxyManager? = null

        fun shouldBypassProxy(host: String?): Boolean {
            if (host.isNullOrBlank()) return true
            val lower = host.trim().lowercase(Locale.ROOT)
            if (lower in BYPASS_HOSTS) return true
            if (lower.endsWith(".local") || lower.endsWith(".lan")) return true
            if (lower.startsWith("192.168.") || lower.startsWith("10.") || lower.startsWith("169.254.")) {
                return true
            }
            if (lower.startsWith("172.")) {
                val second = lower.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (second != null && second in 16..31) return true
            }
            return false
        }
    }

    @Volatile
    var config: ActiveConfig = ActiveConfig()
        private set

    @Volatile
    private var resolvedProxyHost: String = ""

    @Volatile
    private var resolvedProxyIp: String = ""

    fun updateConfig(newConfig: ActiveConfig) {
        if (!newConfig.host.equals(resolvedProxyHost, ignoreCase = true)) {
            resolvedProxyHost = ""
            resolvedProxyIp = ""
        }
        config = newConfig
        if (newConfig.isActive) {
            installScopedSocksAuthenticator(this)
        }
    }

    val isProxyActive: Boolean
        get() = config.isActive

    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val current = config
            if (!current.isActive) return listOf(Proxy.NO_PROXY)
            val targetHost = uri?.host
            if (shouldBypassProxy(targetHost)) return listOf(Proxy.NO_PROXY)
            if (targetHost.equals(current.host, ignoreCase = true)) return listOf(Proxy.NO_PROXY)
            val socketAddress = resolveProxySocketAddress(current.host, current.port)
            return listOf(Proxy(Proxy.Type.SOCKS, socketAddress))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // Clear cached proxy IP on connect failure so next retry re-resolves via 1.1.1.1 DoH
            resolvedProxyHost = ""
            resolvedProxyIp = ""
        }
    }

    private fun resolveProxySocketAddress(host: String, port: Int): InetSocketAddress {
        val resolver = proxyDnsResolver
        if (resolver != null && !DohJsonParser.isIpLiteral(host)) {
            val addr = runCatching { resolver.lookup(host).firstOrNull() }.getOrNull()
            if (addr != null) {
                resolvedProxyHost = host
                resolvedProxyIp = addr.hostAddress.orEmpty()
                return InetSocketAddress(addr, port)
            }
        }
        if (DohJsonParser.isIpLiteral(host)) {
            val numeric = DohJsonParser.parseIpv4Literal(host)
            if (numeric != null) {
                resolvedProxyHost = host
                resolvedProxyIp = numeric.hostAddress.orEmpty()
                return InetSocketAddress(numeric, port)
            }
        }
        return InetSocketAddress.createUnresolved(host, port)
    }

    private fun installScopedSocksAuthenticator(manager: DynamicSocksProxyManager) {
        activeManagerForAuth = manager
        if (!authenticatorInstalled.compareAndSet(false, true)) return
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val activeMgr = activeManagerForAuth ?: return null
                val cfg = activeMgr.config
                if (!cfg.isActive) return null
                val protocol = requestingProtocol?.uppercase(Locale.ROOT).orEmpty()
                val reqHost = requestingHost.orEmpty()
                val reqPort = requestingPort
                // Strictly scope credentials to SOCKS5 requests targeting our configured NordVPN server
                val isSocksRequest = protocol.contains("SOCKS") || reqPort == cfg.port
                val cachedIp = activeMgr.resolvedProxyIp
                val matchesServer = reqHost.isBlank() ||
                    reqHost.equals(cfg.host, ignoreCase = true) ||
                    (cachedIp.isNotBlank() && reqHost == cachedIp) ||
                    reqPort == cfg.port
                if (!isSocksRequest || !matchesServer) return null
                return PasswordAuthentication(cfg.username, cfg.password.toCharArray())
            }
        })
    }
}

/**
 * Play-Policy compliant helper for detecting active Android system VPNs (`TRANSPORT_VPN`) and
 * launching installed Cloudflare 1.1.1.1 WARP or NordVPN companion apps in 1 tap.
 */
object SystemVpnMonitor {
    const val PACKAGE_CLOUDFLARE_WARP = "com.cloudflare.onedotonedotonedotone"
    const val PACKAGE_NORDVPN = "com.nordvpn.android"

    fun isSystemVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNet = cm.activeNetwork
                val activeCaps = activeNet?.let { cm.getNetworkCapabilities(it) }
                if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    return@runCatching true
                }
                @Suppress("DEPRECATION")
                cm.allNetworks.any { net ->
                    cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            val pm = context.packageManager
            pm.getLaunchIntentForPackage(packageName) != null ||
                pm.getLeanbackLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }

    /**
     * Launches the installed VPN companion app (supports both Mobile and Android TV Leanback),
     * or opens its Play Store listing if not installed. Returns true if an activity launched.
     */
    fun launchOrOpenStore(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(launchIntent)
                true
            }.getOrDefault(false)
        }
        return try {
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(marketIntent)
            true
        } catch (_: ActivityNotFoundException) {
            runCatching {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webIntent)
                true
            }.getOrDefault(false)
        }
    }
}
