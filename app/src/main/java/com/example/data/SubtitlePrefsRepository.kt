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
import com.example.player.NetworkTransportMode
import com.example.player.VideoDecoderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
}
