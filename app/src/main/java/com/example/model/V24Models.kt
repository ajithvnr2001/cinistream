package com.example.model

data class ChapterMarker(
    val title: String,
    val startMs: Long
)

data class AbLoopState(
    val startMs: Long? = null,
    val endMs: Long? = null
) {
    val isActive: Boolean
        get() = startMs != null && endMs != null && endMs > startMs + 500L

    val hasPointAOnly: Boolean
        get() = startMs != null && endMs == null
}

enum class SleepTimerMode(val label: String, val minutes: Int) {
    OFF("Off", 0),
    MIN_15("15m", 15),
    MIN_30("30m", 30),
    MIN_45("45m", 45),
    MIN_60("60m", 60),
    END_OF_EPISODE("End of Ep", -1)
}

data class SleepTimerUiState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingMs: Long = 0L,
    val isFadingOut: Boolean = false
) {
    val isActive: Boolean get() = mode != SleepTimerMode.OFF

    fun compactBadge(): String? = when {
        mode == SleepTimerMode.OFF -> null
        mode == SleepTimerMode.END_OF_EPISODE -> "Sleep: Ep End"
        remainingMs <= 0L -> "Sleep: 0m"
        else -> {
            val totalSec = (remainingMs / 1000L).coerceAtLeast(1L)
            val mins = totalSec / 60L
            val secs = totalSec % 60L
            if (mins >= 1L) "Sleep ${mins}m" else "Sleep ${secs}s"
        }
    }
}

data class UpNextCountdownState(
    val series: JsonPlaylistItem,
    val nextEpisode: SeriesEpisode,
    val secondsRemaining: Int = 10,
    val totalSeconds: Int = 10
)

data class LanguageOption(
    val code: String,
    val label: String
)

object PreferredLanguageCatalog {
    val audioLanguages: List<LanguageOption> = listOf(
        LanguageOption("", "Auto (System)"),
        LanguageOption("en", "English"),
        LanguageOption("ta", "Tamil"),
        LanguageOption("hi", "Hindi"),
        LanguageOption("te", "Telugu"),
        LanguageOption("ml", "Malayalam"),
        LanguageOption("es", "Spanish"),
        LanguageOption("fr", "French"),
        LanguageOption("ja", "Japanese"),
        LanguageOption("ko", "Korean"),
        LanguageOption("de", "German")
    )

    val subtitleLanguages: List<LanguageOption> = listOf(
        LanguageOption("", "Auto"),
        LanguageOption("off", "Off"),
        LanguageOption("en", "English"),
        LanguageOption("ta", "Tamil"),
        LanguageOption("hi", "Hindi"),
        LanguageOption("te", "Telugu"),
        LanguageOption("ml", "Malayalam"),
        LanguageOption("es", "Spanish"),
        LanguageOption("fr", "French"),
        LanguageOption("ja", "Japanese"),
        LanguageOption("ko", "Korean")
    )

    val skipIntervalOptions: List<Int> = listOf(5, 10, 15, 30)
}

data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startMs: Long,
    val endMs: Long
) {
    fun progressFraction(nowMs: Long): Float {
        val dur = (endMs - startMs).coerceAtLeast(1L)
        return ((nowMs - startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }
}

data class ChannelEpgNowNext(
    val now: EpgProgram? = null,
    val next: EpgProgram? = null
)

enum class GlobalSearchCategory(val label: String) {
    FAVORITE("Watchlist"),
    CATALOG("Catalog"),
    M3U("Live TV"),
    DOWNLOAD("Offline"),
    HISTORY("Recent")
}

data class GlobalSearchResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: GlobalSearchCategory,
    val format: StreamFormat = StreamFormat.AUTO,
    val posterUrl: String? = null,
    val sourceUri: String,
    val jsonItem: JsonPlaylistItem? = null,
    val m3uEntry: M3uEntry? = null
)
