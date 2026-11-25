package com.example.viewmodel

import com.example.data.NetworkServerEntity
import com.example.model.ChannelEpgNowNext
import com.example.model.EpgProgram
import com.example.player.NetworkBrowserItem
import com.example.player.SecondarySubtitleTrack

data class SecondarySubtitleUiState(
    val activeTrack: SecondarySubtitleTrack? = null,
    val currentText: String? = null,
    val isLoading: Boolean = false
)

data class EpgUiState(
    val epgUrl: String = "",
    val isLoading: Boolean = false,
    val programsByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    val channelAliases: Map<String, String> = emptyMap(),
    val loadedProgramCount: Int = 0,
    val errorMessage: String? = null
)

data class ChannelZapBannerState(
    val channelNumber: Int,
    val totalChannels: Int,
    val channelTitle: String,
    val groupTitle: String,
    val epgNowNext: ChannelEpgNowNext? = null
)

data class LocalNetworkUiState(
    val activeServer: NetworkServerEntity? = null,
    val breadcrumbPath: List<Pair<String, String>> = emptyList(), // (label, pathOrParentId)
    val items: List<NetworkBrowserItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class WebRemoteUiState(
    val isRunning: Boolean = false,
    val serverUrl: String = "",
    val pin: String = "",
    val lastReceivedAction: String? = null,
    val errorMessage: String? = null
)
