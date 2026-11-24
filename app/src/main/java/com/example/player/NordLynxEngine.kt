package com.example.player

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents any global NordVPN country or city location (all 118+ NordVPN countries & cities).
 * Used with CineStream's embedded NordLynx (WireGuard) tunnel so every location works directly
 * inside CineStream without launching any external VPN app.
 */
data class NordVpnLocation(
    val id: String,
    val countryId: Int,
    val countryName: String,
    val countryCode: String,
    val cityId: Int? = null,
    val cityName: String? = null
) {
    val displayLabel: String
        get() = if (!cityName.isNullOrBlank()) "$countryName · $cityName" else countryName

    val compactBadge: String
        get() = countryCode.uppercase(Locale.ROOT).ifBlank { countryName.take(2).uppercase(Locale.ROOT) }
}

/**
 * Live recommended NordVPN NordLynx (WireGuard UDP) endpoint returned by `api.nordvpn.com`.
 */
data class NordLynxServerEndpoint(
    val hostname: String,
    val stationIp: String,
    val port: Int = DEFAULT_WIREGUARD_PORT,
    val publicKey: String,
    val countryName: String,
    val cityName: String? = null,
    val loadPercent: Int = 0
) {
    companion object {
        const val DEFAULT_WIREGUARD_PORT = 51820
    }
}

/**
 * Full global catalog of NordVPN countries (used instantly offline and enriched live from
 * `https://api.nordvpn.com/v1/servers/countries` with all countries + cities).
 */
object NordVpnLocationCatalog {
    val DEFAULT_LOCATION = NordVpnLocation(
        id = "c_153",
        countryId = 153,
        countryName = "Netherlands",
        countryCode = "NL"
    )

    val FALLBACK_COUNTRIES: List<NordVpnLocation> = listOf(
        NordVpnLocation("c_2", 2, "Albania", "AL"),
        NordVpnLocation("c_3", 3, "Algeria", "DZ"),
        NordVpnLocation("c_5", 5, "Andorra", "AD"),
        NordVpnLocation("c_6", 6, "Angola", "AO"),
        NordVpnLocation("c_10", 10, "Argentina", "AR"),
        NordVpnLocation("c_11", 11, "Armenia", "AM"),
        NordVpnLocation("c_13", 13, "Australia", "AU"),
        NordVpnLocation("c_14", 14, "Austria", "AT"),
        NordVpnLocation("c_15", 15, "Azerbaijan", "AZ"),
        NordVpnLocation("c_16", 16, "Bahamas", "BS"),
        NordVpnLocation("c_17", 17, "Bahrain", "BH"),
        NordVpnLocation("c_18", 18, "Bangladesh", "BD"),
        NordVpnLocation("c_21", 21, "Belgium", "BE"),
        NordVpnLocation("c_22", 22, "Belize", "BZ"),
        NordVpnLocation("c_24", 24, "Bermuda", "BM"),
        NordVpnLocation("c_25", 25, "Bhutan", "BT"),
        NordVpnLocation("c_26", 26, "Bolivia", "BO"),
        NordVpnLocation("c_27", 27, "Bosnia and Herzegovina", "BA"),
        NordVpnLocation("c_30", 30, "Brazil", "BR"),
        NordVpnLocation("c_32", 32, "Brunei Darussalam", "BN"),
        NordVpnLocation("c_33", 33, "Bulgaria", "BG"),
        NordVpnLocation("c_36", 36, "Cambodia", "KH"),
        NordVpnLocation("c_38", 38, "Canada", "CA"),
        NordVpnLocation("c_40", 40, "Cayman Islands", "KY"),
        NordVpnLocation("c_43", 43, "Chile", "CL"),
        NordVpnLocation("c_47", 47, "Colombia", "CO"),
        NordVpnLocation("c_52", 52, "Costa Rica", "CR"),
        NordVpnLocation("c_54", 54, "Croatia", "HR"),
        NordVpnLocation("c_56", 56, "Cyprus", "CY"),
        NordVpnLocation("c_57", 57, "Czech Republic", "CZ"),
        NordVpnLocation("c_58", 58, "Denmark", "DK"),
        NordVpnLocation("c_61", 61, "Dominican Republic", "DO"),
        NordVpnLocation("c_63", 63, "Ecuador", "EC"),
        NordVpnLocation("c_64", 64, "Egypt", "EG"),
        NordVpnLocation("c_65", 65, "El Salvador", "SV"),
        NordVpnLocation("c_68", 68, "Estonia", "EE"),
        NordVpnLocation("c_73", 73, "Finland", "FI"),
        NordVpnLocation("c_74", 74, "France", "FR"),
        NordVpnLocation("c_80", 80, "Georgia", "GE"),
        NordVpnLocation("c_81", 81, "Germany", "DE"),
        NordVpnLocation("c_82", 82, "Ghana", "GH"),
        NordVpnLocation("c_84", 84, "Greece", "GR"),
        NordVpnLocation("c_85", 85, "Greenland", "GL"),
        NordVpnLocation("c_88", 88, "Guam", "GU"),
        NordVpnLocation("c_89", 89, "Guatemala", "GT"),
        NordVpnLocation("c_96", 96, "Honduras", "HN"),
        NordVpnLocation("c_97", 97, "Hong Kong", "HK"),
        NordVpnLocation("c_98", 98, "Hungary", "HU"),
        NordVpnLocation("c_99", 99, "Iceland", "IS"),
        NordVpnLocation("c_100", 100, "India", "IN"),
        NordVpnLocation("c_101", 101, "Indonesia", "ID"),
        NordVpnLocation("c_104", 104, "Ireland", "IE"),
        NordVpnLocation("c_105", 105, "Isle of Man", "IM"),
        NordVpnLocation("c_106", 106, "Israel", "IL"),
        NordVpnLocation("c_107", 107, "Italy", "IT"),
        NordVpnLocation("c_108", 108, "Jamaica", "JM"),
        NordVpnLocation("c_109", 109, "Japan", "JP"),
        NordVpnLocation("c_110", 110, "Jersey", "JE"),
        NordVpnLocation("c_112", 112, "Kazakhstan", "KZ"),
        NordVpnLocation("c_113", 113, "Kenya", "KE"),
        NordVpnLocation("c_117", 117, "Kuwait", "KW"),
        NordVpnLocation("c_119", 119, "Laos", "LA"),
        NordVpnLocation("c_120", 120, "Latvia", "LV"),
        NordVpnLocation("c_121", 121, "Lebanon", "LB"),
        NordVpnLocation("c_125", 125, "Liechtenstein", "LI"),
        NordVpnLocation("c_126", 126, "Lithuania", "LT"),
        NordVpnLocation("c_127", 127, "Luxembourg", "LU"),
        NordVpnLocation("c_132", 132, "Malaysia", "MY"),
        NordVpnLocation("c_135", 135, "Malta", "MT"),
        NordVpnLocation("c_140", 140, "Mexico", "MX"),
        NordVpnLocation("c_142", 142, "Moldova", "MD"),
        NordVpnLocation("c_143", 143, "Monaco", "MC"),
        NordVpnLocation("c_144", 144, "Mongolia", "MN"),
        NordVpnLocation("c_145", 145, "Montenegro", "ME"),
        NordVpnLocation("c_147", 147, "Morocco", "MA"),
        NordVpnLocation("c_148", 148, "Mozambique", "MZ"),
        NordVpnLocation("c_149", 149, "Myanmar", "MM"),
        NordVpnLocation("c_152", 152, "Nepal", "NP"),
        DEFAULT_LOCATION,
        NordVpnLocation("c_156", 156, "New Zealand", "NZ"),
        NordVpnLocation("c_159", 159, "Nigeria", "NG"),
        NordVpnLocation("c_129", 129, "North Macedonia", "MK"),
        NordVpnLocation("c_163", 163, "Norway", "NO"),
        NordVpnLocation("c_165", 165, "Pakistan", "PK"),
        NordVpnLocation("c_168", 168, "Panama", "PA"),
        NordVpnLocation("c_169", 169, "Papua New Guinea", "PG"),
        NordVpnLocation("c_170", 170, "Paraguay", "PY"),
        NordVpnLocation("c_171", 171, "Peru", "PE"),
        NordVpnLocation("c_172", 172, "Philippines", "PH"),
        NordVpnLocation("c_174", 174, "Poland", "PL"),
        NordVpnLocation("c_175", 175, "Portugal", "PT"),
        NordVpnLocation("c_176", 176, "Puerto Rico", "PR"),
        NordVpnLocation("c_177", 177, "Qatar", "QA"),
        NordVpnLocation("c_179", 179, "Romania", "RO"),
        NordVpnLocation("c_190", 190, "Saudi Arabia", "SA"),
        NordVpnLocation("c_192", 192, "Serbia", "RS"),
        NordVpnLocation("c_195", 195, "Singapore", "SG"),
        NordVpnLocation("c_196", 196, "Slovakia", "SK"),
        NordVpnLocation("c_197", 197, "Slovenia", "SI"),
        NordVpnLocation("c_200", 200, "South Africa", "ZA"),
        NordVpnLocation("c_115", 115, "South Korea", "KR"),
        NordVpnLocation("c_202", 202, "Spain", "ES"),
        NordVpnLocation("c_203", 203, "Sri Lanka", "LK"),
        NordVpnLocation("c_208", 208, "Sweden", "SE"),
        NordVpnLocation("c_209", 209, "Switzerland", "CH"),
        NordVpnLocation("c_211", 211, "Taiwan", "TW"),
        NordVpnLocation("c_214", 214, "Thailand", "TH"),
        NordVpnLocation("c_218", 218, "Trinidad and Tobago", "TT"),
        NordVpnLocation("c_219", 219, "Tunisia", "TN"),
        NordVpnLocation("c_220", 220, "Turkey", "TR"),
        NordVpnLocation("c_225", 225, "Ukraine", "UA"),
        NordVpnLocation("c_226", 226, "United Arab Emirates", "AE"),
        NordVpnLocation("c_227", 227, "United Kingdom", "GB"),
        NordVpnLocation("c_228", 228, "United States", "US"),
        NordVpnLocation("c_229", 229, "Uruguay", "UY"),
        NordVpnLocation("c_230", 230, "Uzbekistan", "UZ"),
        NordVpnLocation("c_232", 232, "Venezuela", "VE"),
        NordVpnLocation("c_233", 233, "Vietnam", "VN")
    )

    /**
     * Ranked fuzzy/prefix search across all countries and cities.
     * Returns empty when [query] is blank so the UI never dumps a long list by default.
     */
    fun search(
        locations: List<NordVpnLocation>,
        query: String,
        limit: Int = 6
    ): List<NordVpnLocation> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()
        val alias = when (needle) {
            "uk" -> "united kingdom"
            "usa" -> "united states"
            "uae" -> "united arab emirates"
            "korea" -> "south korea"
            else -> needle
        }
        return locations.asSequence()
            .mapNotNull { loc ->
                val cName = loc.countryName.lowercase(Locale.ROOT)
                val cCode = loc.countryCode.lowercase(Locale.ROOT)
                val city = loc.cityName?.lowercase(Locale.ROOT).orEmpty()
                val full = loc.displayLabel.lowercase(Locale.ROOT)
                val score = when {
                    (cCode == needle || cName == alias || cName == needle) && loc.cityId == null -> 0
                    city == needle -> 1
                    cName.startsWith(alias) && loc.cityId == null -> 2
                    city.startsWith(needle) -> 3
                    cName.startsWith(alias) -> 4
                    full.contains(needle) || full.contains(alias) -> 5
                    else -> return@mapNotNull null
                }
                score to loc
            }
            .sortedWith(compareBy<Pair<Int, NordVpnLocation>> { it.first }
                .thenBy { it.second.cityId != null }
                .thenBy { it.second.displayLabel.lowercase(Locale.ROOT) })
            .take(limit)
            .map { it.second }
            .toList()
    }

    fun mergeWithLive(live: List<NordVpnLocation>): List<NordVpnLocation> {
        if (live.isEmpty()) return FALLBACK_COUNTRIES
        val byId = LinkedHashMap<String, NordVpnLocation>()
        live.forEach { byId[it.id] = it }
        FALLBACK_COUNTRIES.forEach { byId.putIfAbsent(it.id, it) }
        return byId.values.sortedWith(
            compareBy<NordVpnLocation> { it.countryName.lowercase(Locale.ROOT) }
                .thenBy { it.cityName != null }
                .thenBy { it.cityName?.lowercase(Locale.ROOT).orEmpty() }
        )
    }

    fun findByCountryAndCity(
        countryId: Int,
        cityId: Int?,
        available: List<NordVpnLocation> = FALLBACK_COUNTRIES
    ): NordVpnLocation {
        if (cityId != null) {
            available.firstOrNull { it.countryId == countryId && it.cityId == cityId }?.let { return it }
        }
        return available.firstOrNull { it.countryId == countryId && it.cityId == null }
            ?: available.firstOrNull { it.countryId == countryId }
            ?: DEFAULT_LOCATION
    }
}

/**
 * In-app WireGuard (NordLynx) VPN tunnel manager powered by official `com.wireguard.android:tunnel`
 * (`GoBackend` + `VpnService`). Routes CineStream's traffic directly through ANY of NordVPN's
 * 118+ global countries without opening any external app.
 */
class NordLynxTunnelController(private val appContext: Context) {

    private class CineNordLynxTunnel(
        private val onState: (Boolean) -> Unit
    ) : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            onState(newState == Tunnel.State.UP)
        }
    }

    private val tunnelUpFlag = AtomicBoolean(false)
    private val tunnel = CineNordLynxTunnel { up -> tunnelUpFlag.set(up) }

    @Volatile
    private var backend: GoBackend? = null

    val isTunnelUp: Boolean
        get() = tunnelUpFlag.get()

    private fun getOrCreateBackend(): GoBackend {
        backend?.let { return it }
        return synchronized(this) {
            backend ?: GoBackend(appContext).also { backend = it }
        }
    }

    /**
     * Checks whether Android's built-in VPN consent has already been granted.
     * Returns an [Intent] to display Android's 1-tap system permission dialog if needed,
     * or `null` if permission is already granted and the tunnel can connect immediately.
     */
    fun prepareVpnPermissionIntent(): Intent? {
        return runCatching { VpnService.prepare(appContext) }.getOrNull()
    }

    /**
     * Brings up the embedded WireGuard (NordLynx) tunnel to [endpoint] on [Dispatchers.IO].
     */
    suspend fun connect(
        privateKey: String,
        endpoint: NordLynxServerEndpoint,
        dnsMode: PrivateDnsMode
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = buildWireGuardConfig(
                privateKey = privateKey,
                endpoint = endpoint,
                dnsMode = dnsMode,
                includedPackageName = appContext.packageName
            )
            val wgBackend = getOrCreateBackend()
            // Cleanly cycle if already running so endpoint/DNS updates always take effect immediately
            if (tunnelUpFlag.get()) {
                runCatching { wgBackend.setState(tunnel, Tunnel.State.DOWN, null) }
            }
            val resultingState = wgBackend.setState(tunnel, Tunnel.State.UP, config)
            tunnelUpFlag.set(resultingState == Tunnel.State.UP)
            if (resultingState != Tunnel.State.UP) {
                error("WireGuard tunnel did not reach UP state")
            }
        }
    }

    /**
     * Cleanly shuts down the embedded NordLynx WireGuard tunnel.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val existingBackend = backend ?: return@withContext
        runCatching {
            existingBackend.setState(tunnel, Tunnel.State.DOWN, null)
        }
        tunnelUpFlag.set(false)
    }

    fun disconnectBlocking() {
        val existingBackend = backend ?: return
        runCatching {
            existingBackend.setState(tunnel, Tunnel.State.DOWN, null)
        }
        tunnelUpFlag.set(false)
    }

    companion object {
        const val TUNNEL_NAME = "cinestream_nordlynx"
        const val NORDLYNX_CLIENT_ADDRESS = "10.5.0.2/32"
        const val NORDLYNX_DEFAULT_MTU = 1280
        const val NORD_DNS_PRIMARY = "103.86.96.100"
        const val NORD_DNS_SECONDARY = "103.86.99.100"

        fun dnsServersForMode(dnsMode: PrivateDnsMode): List<String> = when (dnsMode) {
            PrivateDnsMode.CLOUDFLARE_1111 -> listOf("1.1.1.1", "1.0.0.1")
            PrivateDnsMode.GOOGLE_8888 -> listOf("8.8.8.8", "8.8.4.4")
            PrivateDnsMode.SYSTEM -> listOf(NORD_DNS_PRIMARY, NORD_DNS_SECONDARY)
        }

        /**
         * Pure, unit-testable WireGuard [Config] builder for NordVPN NordLynx.
         * Scopes the tunnel to CineStream's package name (`includeApplication`) so other apps on
         * the phone or TV remain unaffected.
         */
        fun buildWireGuardConfig(
            privateKey: String,
            endpoint: NordLynxServerEndpoint,
            dnsMode: PrivateDnsMode,
            includedPackageName: String? = null
        ): Config {
            require(privateKey.isNotBlank()) { "Missing NordLynx private key" }
            require(endpoint.publicKey.isNotBlank()) { "Missing server WireGuard public key" }
            require(endpoint.stationIp.isNotBlank()) { "Missing server endpoint IP" }

            val dnsList = dnsServersForMode(dnsMode).joinToString(", ")
            val interfaceBuilder = Interface.Builder()
                .parsePrivateKey(privateKey.trim())
                .parseAddresses(NORDLYNX_CLIENT_ADDRESS)
                .parseDnsServers(dnsList)
                .parseMtu(NORDLYNX_DEFAULT_MTU.toString())

            if (!includedPackageName.isNullOrBlank()) {
                interfaceBuilder.includeApplication(includedPackageName.trim())
            }

            val peer = Peer.Builder()
                .parsePublicKey(endpoint.publicKey.trim())
                .parseEndpoint("${endpoint.stationIp.trim()}:${endpoint.port}")
                .parseAllowedIPs("0.0.0.0/0")
                .parsePersistentKeepalive("25")
                .build()

            return Config.Builder()
                .setInterface(interfaceBuilder.build())
                .addPeer(peer)
                .build()
        }
    }
}
