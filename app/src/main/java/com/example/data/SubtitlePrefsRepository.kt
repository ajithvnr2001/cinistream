package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AspectRatioMode
import com.example.model.SubtitleAppearance
import com.example.model.SubtitleBgPreset
import com.example.model.SubtitleColorPreset
import com.example.model.SubtitleTextSize
import com.example.player.CredentialVault
import com.example.player.NetworkTransportMode
import com.example.player.NordVpnCatalog
import com.example.player.NordVpnCredentials
import com.example.player.NordVpnLocation
import com.example.player.NordVpnLocationCatalog
import com.example.player.PrivateDnsMode
import com.example.player.VideoDecoderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class NetworkPrivacyPrefs(
    val privateDnsMode: PrivateDnsMode = PrivateDnsMode.SYSTEM,
    val nordVpnEnabled: Boolean = false,
    val nordVpnServerHost: String = NordVpnCatalog.DEFAULT_SERVERS.first().hostname,
    val nordVpnServerLabel: String = NordVpnCatalog.DEFAULT_SERVERS.first().label,
    val nordVpnUsername: String = "",
    val nordVpnPassword: String = "",
    val nordLynxPrivateKey: String = "",
    val nordVpnCountryId: Int = NordVpnLocationCatalog.DEFAULT_LOCATION.countryId,
    val nordVpnCityId: Int? = null,
    val nordVpnCountryName: String = NordVpnLocationCatalog.DEFAULT_LOCATION.countryName,
    val nordVpnCountryCode: String = NordVpnLocationCatalog.DEFAULT_LOCATION.countryCode,
    val nordVpnCityName: String? = null,
    val smartAutoVpnFailoverEnabled: Boolean = false
) {
    val hasNordCredentials: Boolean
        get() = nordLynxPrivateKey.isNotBlank() || (nordVpnUsername.isNotBlank() && nordVpnPassword.isNotBlank())

    val selectedLocation: NordVpnLocation
        get() = NordVpnLocation(
            id = if (nordVpnCityId != null) "c_${nordVpnCountryId}_city_$nordVpnCityId" else "c_$nordVpnCountryId",
            countryId = nordVpnCountryId,
            countryName = nordVpnCountryName.ifBlank { NordVpnLocationCatalog.DEFAULT_LOCATION.countryName },
            countryCode = nordVpnCountryCode.ifBlank { NordVpnLocationCatalog.DEFAULT_LOCATION.countryCode },
            cityId = nordVpnCityId,
            cityName = nordVpnCityName?.takeIf { it.isNotBlank() }
        )
}

private val Context.cinePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cinestream_prefs"
)

class SubtitlePrefsRepository(private val context: Context) {

    private object Keys {
        val TEXT_SIZE = stringPreferencesKey("sub_text_size")
        val COLOR_PRESET = stringPreferencesKey("sub_color_preset")
        val BG_PRESET = stringPreferencesKey("sub_bg_preset")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio_mode")
        val JSON_PLAYLIST_URL = stringPreferencesKey("json_playlist_url")
        val JSON_PLAYLIST_URLS = stringPreferencesKey("json_playlist_urls")
        val VOLUME_BOOST_PERCENT = intPreferencesKey("volume_boost_percent")
        val STATS_FOR_NERDS_ENABLED = booleanPreferencesKey("stats_for_nerds_enabled")
        val VIDEO_DECODER_MODE = stringPreferencesKey("video_decoder_mode")
        val NETWORK_TRANSPORT_MODE = stringPreferencesKey("network_transport_mode")
        val PRIVATE_DNS_MODE = stringPreferencesKey("private_dns_mode")
        val NORDVPN_ENABLED = booleanPreferencesKey("nordvpn_enabled")
        val NORDVPN_SERVER_HOST = stringPreferencesKey("nordvpn_server_host")
        val NORDVPN_SERVER_LABEL = stringPreferencesKey("nordvpn_server_label")
        val NORDVPN_USER_ENC = stringPreferencesKey("nordvpn_user_enc")
        val NORDVPN_PASS_ENC = stringPreferencesKey("nordvpn_pass_enc")
        val NORDVPN_LYNX_KEY_ENC = stringPreferencesKey("nordvpn_lynx_key_enc")
        val NORDVPN_COUNTRY_ID = intPreferencesKey("nordvpn_country_id")
        val NORDVPN_CITY_ID = intPreferencesKey("nordvpn_city_id")
        val NORDVPN_COUNTRY_NAME = stringPreferencesKey("nordvpn_country_name")
        val NORDVPN_COUNTRY_CODE = stringPreferencesKey("nordvpn_country_code")
        val NORDVPN_CITY_NAME = stringPreferencesKey("nordvpn_city_name")
        val SMART_AUTO_VPN_FAILOVER = booleanPreferencesKey("smart_auto_vpn_failover")
        val BACKGROUND_AUDIO_ENABLED = booleanPreferencesKey("background_audio_enabled")
        val NIGHT_MODE_AUDIO_ENABLED = booleanPreferencesKey("night_mode_audio_enabled")
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
        val PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
        val SEEK_INTERVAL_SECONDS = intPreferencesKey("seek_interval_seconds")
        val EPG_URL = stringPreferencesKey("epg_url")
        val AUTO_PLAY_UP_NEXT_ENABLED = booleanPreferencesKey("auto_play_up_next_enabled")
        val OPENSUBTITLES_USER_ENC = stringPreferencesKey("opensubtitles_user_enc")
        val OPENSUBTITLES_PASS_ENC = stringPreferencesKey("opensubtitles_pass_enc")
    }

    val appearance: Flow<SubtitleAppearance> = context.cinePrefsDataStore.data.map { prefs ->
        SubtitleAppearance(
            textSize = prefs[Keys.TEXT_SIZE]
                ?.let { runCatching { SubtitleTextSize.valueOf(it) }.getOrNull() }
                ?: SubtitleTextSize.MEDIUM,
            colorPreset = prefs[Keys.COLOR_PRESET]
                ?.let { runCatching { SubtitleColorPreset.valueOf(it) }.getOrNull() }
                ?: SubtitleColorPreset.WHITE,
            bgPreset = prefs[Keys.BG_PRESET]
                ?.let { runCatching { SubtitleBgPreset.valueOf(it) }.getOrNull() }
                ?: SubtitleBgPreset.TRANSLUCENT
        )
    }

    val aspectRatioMode: Flow<AspectRatioMode> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.ASPECT_RATIO]
            ?.let { runCatching { AspectRatioMode.valueOf(it) }.getOrNull() }
            ?: AspectRatioMode.FIT
    }

    val volumeBoostPercent: Flow<Int> = context.cinePrefsDataStore.data.map { prefs ->
        (prefs[Keys.VOLUME_BOOST_PERCENT] ?: 100).coerceIn(0, 200)
    }

    val statsForNerdsEnabled: Flow<Boolean> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.STATS_FOR_NERDS_ENABLED] ?: false
    }

    val videoDecoderMode: Flow<VideoDecoderMode> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.VIDEO_DECODER_MODE]
            ?.let { runCatching { VideoDecoderMode.valueOf(it) }.getOrNull() }
            ?: VideoDecoderMode.AUTO
    }

    val networkTransportMode: Flow<NetworkTransportMode> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.NETWORK_TRANSPORT_MODE]
            ?.let { runCatching { NetworkTransportMode.valueOf(it) }.getOrNull() }
            ?: NetworkTransportMode.AUTO
    }

    val backgroundAudioEnabled: Flow<Boolean> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.BACKGROUND_AUDIO_ENABLED] ?: false
    }

    val nightModeAudioEnabled: Flow<Boolean> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.NIGHT_MODE_AUDIO_ENABLED] ?: false
    }

    val preferredAudioLanguage: Flow<String> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.PREFERRED_AUDIO_LANGUAGE].orEmpty()
    }

    val preferredSubtitleLanguage: Flow<String> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.PREFERRED_SUBTITLE_LANGUAGE].orEmpty()
    }

    val seekIntervalSeconds: Flow<Int> = context.cinePrefsDataStore.data.map { prefs ->
        val raw = prefs[Keys.SEEK_INTERVAL_SECONDS] ?: 10
        if (raw in listOf(5, 10, 15, 30)) raw else 10
    }

    val epgUrl: Flow<String> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.EPG_URL].orEmpty()
    }

    val autoPlayUpNextEnabled: Flow<Boolean> = context.cinePrefsDataStore.data.map { prefs ->
        prefs[Keys.AUTO_PLAY_UP_NEXT_ENABLED] ?: true
    }

    suspend fun updateTextSize(size: SubtitleTextSize) {
        context.cinePrefsDataStore.edit { it[Keys.TEXT_SIZE] = size.name }
    }

    suspend fun updateColorPreset(color: SubtitleColorPreset) {
        context.cinePrefsDataStore.edit { it[Keys.COLOR_PRESET] = color.name }
    }

    suspend fun updateBgPreset(bg: SubtitleBgPreset) {
        context.cinePrefsDataStore.edit { it[Keys.BG_PRESET] = bg.name }
    }

    suspend fun updateAspectRatio(mode: AspectRatioMode) {
        context.cinePrefsDataStore.edit { it[Keys.ASPECT_RATIO] = mode.name }
    }

    suspend fun loadJsonPlaylistUrls(): List<String> {
        val prefs = context.cinePrefsDataStore.data.first()
        return decodeUrlList(
            encodedJson = prefs[Keys.JSON_PLAYLIST_URLS],
            legacySingleUrl = prefs[Keys.JSON_PLAYLIST_URL]
        )
    }

    suspend fun saveJsonPlaylistUrls(urls: List<String>) {
        val cleaned = normalizeUrlList(urls)
        context.cinePrefsDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.JSON_PLAYLIST_URLS)
                prefs.remove(Keys.JSON_PLAYLIST_URL)
            } else {
                prefs[Keys.JSON_PLAYLIST_URLS] = encodeUrlList(cleaned)
                prefs[Keys.JSON_PLAYLIST_URL] = cleaned.first()
            }
        }
    }

    suspend fun loadJsonPlaylistUrl(): String? = loadJsonPlaylistUrls().firstOrNull()

    suspend fun saveJsonPlaylistUrl(url: String?) {
        if (url.isNullOrBlank()) {
            saveJsonPlaylistUrls(emptyList())
        } else {
            val existing = loadJsonPlaylistUrls()
            saveJsonPlaylistUrls(existing + url.trim())
        }
    }

    companion object {
        fun normalizeUrlList(urls: List<String>): List<String> {
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<String>()
            for (raw in urls) {
                val trimmed = raw.trim()
                if (trimmed.isBlank()) continue
                val key = trimmed.lowercase()
                if (seen.add(key)) {
                    result.add(trimmed)
                }
            }
            return result
        }

        fun encodeUrlList(urls: List<String>): String {
            val arr = org.json.JSONArray()
            normalizeUrlList(urls).forEach { arr.put(it) }
            return arr.toString()
        }

        fun decodeUrlList(encodedJson: String?, legacySingleUrl: String? = null): List<String> {
            if (!encodedJson.isNullOrBlank()) {
                val parsed = runCatching {
                    val arr = org.json.JSONArray(encodedJson)
                    buildList {
                        for (i in 0 until arr.length()) {
                            val item = arr.optString(i, "").trim()
                            if (item.isNotBlank()) add(item)
                        }
                    }
                }.getOrDefault(emptyList())
                val normalized = normalizeUrlList(parsed)
                if (normalized.isNotEmpty()) return normalized
            }
            val legacy = legacySingleUrl?.trim().orEmpty()
            return if (legacy.isNotBlank()) listOf(legacy) else emptyList()
        }
    }

    val networkPrivacyPrefs: Flow<NetworkPrivacyPrefs> = context.cinePrefsDataStore.data.map { prefs ->
        val defaultServer = NordVpnCatalog.DEFAULT_SERVERS.first()
        val defaultLoc = NordVpnLocationCatalog.DEFAULT_LOCATION
        val mode = prefs[Keys.PRIVATE_DNS_MODE]
            ?.let { runCatching { PrivateDnsMode.valueOf(it) }.getOrNull() }
            ?: PrivateDnsMode.SYSTEM
        val user = CredentialVault.decrypt(prefs[Keys.NORDVPN_USER_ENC])
        val pass = CredentialVault.decrypt(prefs[Keys.NORDVPN_PASS_ENC])
        val lynxKey = CredentialVault.decrypt(prefs[Keys.NORDVPN_LYNX_KEY_ENC])
        val hasCreds = lynxKey.isNotBlank() || (user.isNotBlank() && pass.isNotBlank())
        val savedCountryId = prefs[Keys.NORDVPN_COUNTRY_ID] ?: defaultLoc.countryId
        val savedCityId = prefs[Keys.NORDVPN_CITY_ID]?.takeIf { it > 0 }
        NetworkPrivacyPrefs(
            privateDnsMode = mode,
            nordVpnEnabled = (prefs[Keys.NORDVPN_ENABLED] ?: false) && hasCreds,
            nordVpnServerHost = prefs[Keys.NORDVPN_SERVER_HOST]?.takeIf { it.isNotBlank() } ?: defaultServer.hostname,
            nordVpnServerLabel = prefs[Keys.NORDVPN_SERVER_LABEL]?.takeIf { it.isNotBlank() } ?: defaultServer.label,
            nordVpnUsername = user,
            nordVpnPassword = pass,
            nordLynxPrivateKey = lynxKey,
            nordVpnCountryId = savedCountryId,
            nordVpnCityId = savedCityId,
            nordVpnCountryName = prefs[Keys.NORDVPN_COUNTRY_NAME]?.takeIf { it.isNotBlank() } ?: defaultLoc.countryName,
            nordVpnCountryCode = prefs[Keys.NORDVPN_COUNTRY_CODE]?.takeIf { it.isNotBlank() } ?: defaultLoc.countryCode,
            nordVpnCityName = prefs[Keys.NORDVPN_CITY_NAME]?.takeIf { it.isNotBlank() },
            smartAutoVpnFailoverEnabled = (prefs[Keys.SMART_AUTO_VPN_FAILOVER] ?: false) && hasCreds
        )
    }

    suspend fun updatePrivateDnsMode(mode: PrivateDnsMode) {
        context.cinePrefsDataStore.edit { it[Keys.PRIVATE_DNS_MODE] = mode.name }
    }

    suspend fun saveNordVpnCredentials(
        credentials: NordVpnCredentials,
        serverHost: String,
        serverLabel: String,
        location: NordVpnLocation = NordVpnLocationCatalog.DEFAULT_LOCATION,
        enableImmediately: Boolean = true
    ) {
        context.cinePrefsDataStore.edit { prefs ->
            prefs[Keys.NORDVPN_USER_ENC] = CredentialVault.encrypt(credentials.username)
            prefs[Keys.NORDVPN_PASS_ENC] = CredentialVault.encrypt(credentials.password)
            if (credentials.nordLynxPrivateKey.isNotBlank()) {
                prefs[Keys.NORDVPN_LYNX_KEY_ENC] = CredentialVault.encrypt(credentials.nordLynxPrivateKey)
            }
            prefs[Keys.NORDVPN_SERVER_HOST] = serverHost.trim()
            prefs[Keys.NORDVPN_SERVER_LABEL] = serverLabel.trim()
            prefs[Keys.NORDVPN_COUNTRY_ID] = location.countryId
            prefs[Keys.NORDVPN_COUNTRY_NAME] = location.countryName
            prefs[Keys.NORDVPN_COUNTRY_CODE] = location.countryCode
            if (location.cityId != null && !location.cityName.isNullOrBlank()) {
                prefs[Keys.NORDVPN_CITY_ID] = location.cityId
                prefs[Keys.NORDVPN_CITY_NAME] = location.cityName
            } else {
                prefs.remove(Keys.NORDVPN_CITY_ID)
                prefs.remove(Keys.NORDVPN_CITY_NAME)
            }
            prefs[Keys.NORDVPN_ENABLED] = enableImmediately
        }
    }

    suspend fun updateNordVpnEnabled(enabled: Boolean) {
        context.cinePrefsDataStore.edit { it[Keys.NORDVPN_ENABLED] = enabled }
    }

    suspend fun updateNordVpnServer(serverHost: String, serverLabel: String) {
        context.cinePrefsDataStore.edit {
            it[Keys.NORDVPN_SERVER_HOST] = serverHost.trim()
            it[Keys.NORDVPN_SERVER_LABEL] = serverLabel.trim()
        }
    }

    suspend fun updateNordVpnLocation(location: NordVpnLocation) {
        context.cinePrefsDataStore.edit { prefs ->
            prefs[Keys.NORDVPN_COUNTRY_ID] = location.countryId
            prefs[Keys.NORDVPN_COUNTRY_NAME] = location.countryName
            prefs[Keys.NORDVPN_COUNTRY_CODE] = location.countryCode
            if (location.cityId != null && !location.cityName.isNullOrBlank()) {
                prefs[Keys.NORDVPN_CITY_ID] = location.cityId
                prefs[Keys.NORDVPN_CITY_NAME] = location.cityName
            } else {
                prefs.remove(Keys.NORDVPN_CITY_ID)
                prefs.remove(Keys.NORDVPN_CITY_NAME)
            }
            prefs[Keys.NORDVPN_SERVER_LABEL] = location.displayLabel
        }
    }

    suspend fun clearNordVpnCredentials() {
        context.cinePrefsDataStore.edit { prefs ->
            prefs.remove(Keys.NORDVPN_USER_ENC)
            prefs.remove(Keys.NORDVPN_PASS_ENC)
            prefs.remove(Keys.NORDVPN_LYNX_KEY_ENC)
            prefs[Keys.NORDVPN_ENABLED] = false
            prefs[Keys.SMART_AUTO_VPN_FAILOVER] = false
        }
    }

    suspend fun updateSmartAutoVpnFailover(enabled: Boolean) {
        context.cinePrefsDataStore.edit { it[Keys.SMART_AUTO_VPN_FAILOVER] = enabled }
    }

    suspend fun updateBackgroundAudioEnabled(enabled: Boolean) {
        context.cinePrefsDataStore.edit { it[Keys.BACKGROUND_AUDIO_ENABLED] = enabled }
    }

    suspend fun updateVolumeBoostPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        context.cinePrefsDataStore.edit { it[Keys.VOLUME_BOOST_PERCENT] = clamped }
    }

    suspend fun updateStatsForNerdsEnabled(enabled: Boolean) {
        context.cinePrefsDataStore.edit { it[Keys.STATS_FOR_NERDS_ENABLED] = enabled }
    }

    suspend fun updateVideoDecoderMode(mode: VideoDecoderMode) {
        context.cinePrefsDataStore.edit { it[Keys.VIDEO_DECODER_MODE] = mode.name }
    }

    suspend fun updateNetworkTransportMode(mode: NetworkTransportMode) {
        context.cinePrefsDataStore.edit { it[Keys.NETWORK_TRANSPORT_MODE] = mode.name }
    }

    suspend fun updateNightModeAudioEnabled(enabled: Boolean) {
        context.cinePrefsDataStore.edit { it[Keys.NIGHT_MODE_AUDIO_ENABLED] = enabled }
    }

    suspend fun updatePreferredAudioLanguage(langCode: String) {
        context.cinePrefsDataStore.edit { it[Keys.PREFERRED_AUDIO_LANGUAGE] = langCode.trim().lowercase() }
    }

    suspend fun updatePreferredSubtitleLanguage(langCode: String) {
        context.cinePrefsDataStore.edit { it[Keys.PREFERRED_SUBTITLE_LANGUAGE] = langCode.trim().lowercase() }
    }

    suspend fun updateSeekIntervalSeconds(seconds: Int) {
        val valid = if (seconds in listOf(5, 10, 15, 30)) seconds else 10
        context.cinePrefsDataStore.edit { it[Keys.SEEK_INTERVAL_SECONDS] = valid }
    }

    suspend fun updateEpgUrl(url: String) {
        context.cinePrefsDataStore.edit { it[Keys.EPG_URL] = url.trim() }
    }

    suspend fun updateAutoPlayUpNextEnabled(enabled: Boolean) {
        context.cinePrefsDataStore.edit { it[Keys.AUTO_PLAY_UP_NEXT_ENABLED] = enabled }
    }

    suspend fun loadOpenSubtitlesCredentials(): Pair<String, String>? {
        val prefs = context.cinePrefsDataStore.data.first()
        val user = CredentialVault.decrypt(prefs[Keys.OPENSUBTITLES_USER_ENC]).trim()
        val pass = CredentialVault.decrypt(prefs[Keys.OPENSUBTITLES_PASS_ENC])
        return if (user.isNotBlank() && pass.isNotBlank()) user to pass else null
    }

    suspend fun saveOpenSubtitlesCredentials(username: String, password: String) {
        context.cinePrefsDataStore.edit { prefs ->
            prefs[Keys.OPENSUBTITLES_USER_ENC] = CredentialVault.encrypt(username.trim())
            prefs[Keys.OPENSUBTITLES_PASS_ENC] = CredentialVault.encrypt(password)
        }
    }

    suspend fun clearOpenSubtitlesCredentials() {
        context.cinePrefsDataStore.edit { prefs ->
            prefs.remove(Keys.OPENSUBTITLES_USER_ENC)
            prefs.remove(Keys.OPENSUBTITLES_PASS_ENC)
        }
    }
}
