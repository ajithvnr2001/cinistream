package com.example.viewmodel

import com.example.player.NetworkProbeResult
import com.example.player.NordVpnCatalog
import com.example.player.NordVpnServer
import com.example.player.PrivateDnsMode

/**
 * UI state for CineStream's integrated Cloudflare 1.1.1.1 Encrypted DNS (DoH),
 * NordVPN SOCKS5 Access Token routing, and Android System VPN status.
 */
data class NetworkPrivacyUiState(
    val privateDnsMode: PrivateDnsMode = PrivateDnsMode.SYSTEM,
    val nordVpnLoggedIn: Boolean = false,
    val nordVpnEnabled: Boolean = false,
    val selectedNordServerHost: String = NordVpnCatalog.DEFAULT_SERVERS.first().hostname,
    val selectedNordServerLabel: String = NordVpnCatalog.DEFAULT_SERVERS.first().label,
    val availableNordServers: List<NordVpnServer> = NordVpnCatalog.DEFAULT_SERVERS,
    val hasConfiguredBuildToken: Boolean = false,
    val isLoggingInNord: Boolean = false,
    val loginError: String? = null,
    val isProbingRoute: Boolean = false,
    val probeResult: NetworkProbeResult? = null,
    val systemVpnActive: Boolean = false,
    val warpAppInstalled: Boolean = false,
    val nordAppInstalled: Boolean = false
) {
    val isAnyPrivacyActive: Boolean
        get() = privateDnsMode != PrivateDnsMode.SYSTEM ||
            (nordVpnLoggedIn && nordVpnEnabled) ||
            systemVpnActive

    val compactStatusBadge: String
        get() = buildList {
            if (privateDnsMode != PrivateDnsMode.SYSTEM) add(privateDnsMode.shortBadge)
            if (nordVpnLoggedIn && nordVpnEnabled) add("NordVPN")
            else if (systemVpnActive) add("VPN")
        }.joinToString(" + ").ifBlank { "1.1.1.1 / VPN" }
}
