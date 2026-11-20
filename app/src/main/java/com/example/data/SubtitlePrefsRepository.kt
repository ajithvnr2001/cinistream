package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AspectRatioMode
import com.example.model.SubtitleAppearance
import com.example.model.SubtitleBgPreset
import com.example.model.SubtitleColorPreset
import com.example.model.SubtitleTextSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cinePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cinestream_prefs"
)

data class PersistedAiDailyUsage(
    val dayKey: String = "",
    val flashRpd: Int = 0,
    val liteRpd: Int = 0
)

class SubtitlePrefsRepository(private val context: Context) {

    private object Keys {
        val TEXT_SIZE = stringPreferencesKey("sub_text_size")
        val COLOR_PRESET = stringPreferencesKey("sub_color_preset")
        val BG_PRESET = stringPreferencesKey("sub_bg_preset")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio_mode")
        val AI_DAY_KEY = stringPreferencesKey("ai_rpd_day_key")
        val AI_FLASH_RPD = intPreferencesKey("ai_flash_rpd")
        val AI_LITE_RPD = intPreferencesKey("ai_lite_rpd")
        val JSON_PLAYLIST_URL = stringPreferencesKey("json_playlist_url")
        val VOLUME_BOOST_PERCENT = intPreferencesKey("volume_boost_percent")
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

    suspend fun loadAiDailyUsage(): PersistedAiDailyUsage {
        val prefs = context.cinePrefsDataStore.data.first()
        return PersistedAiDailyUsage(
            dayKey = prefs[Keys.AI_DAY_KEY].orEmpty(),
            flashRpd = prefs[Keys.AI_FLASH_RPD] ?: 0,
            liteRpd = prefs[Keys.AI_LITE_RPD] ?: 0
        )
    }

    suspend fun saveAiDailyUsage(dayKey: String, flashRpd: Int, liteRpd: Int) {
        context.cinePrefsDataStore.edit {
            it[Keys.AI_DAY_KEY] = dayKey
            it[Keys.AI_FLASH_RPD] = flashRpd
            it[Keys.AI_LITE_RPD] = liteRpd
        }
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

    suspend fun loadJsonPlaylistUrl(): String? {
        val prefs = context.cinePrefsDataStore.data.first()
        return prefs[Keys.JSON_PLAYLIST_URL]?.takeIf { it.isNotBlank() }
    }

    suspend fun saveJsonPlaylistUrl(url: String?) {
        context.cinePrefsDataStore.edit {
            if (url.isNullOrBlank()) {
                it.remove(Keys.JSON_PLAYLIST_URL)
            } else {
                it[Keys.JSON_PLAYLIST_URL] = url.trim()
            }
        }
    }

    suspend fun updateVolumeBoostPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        context.cinePrefsDataStore.edit { it[Keys.VOLUME_BOOST_PERCENT] = clamped }
    }
}
