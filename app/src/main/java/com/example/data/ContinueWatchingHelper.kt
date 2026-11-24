package com.example.data

import kotlin.math.roundToInt

/**
 * Pure helper utilities for CineStream's Continue Watching rail and per-episode resume progress.
 */
object ContinueWatchingHelper {

    const val MIN_DURATION_MS = 60_000L
    const val MIN_RESUME_FRACTION = 0.02f
    const val COMPLETED_FRACTION = 0.95f

    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        return durationMs >= MIN_DURATION_MS &&
            progressFraction(positionMs, durationMs) >= COMPLETED_FRACTION
    }

    fun isUnfinished(entity: MediaHistoryEntity): Boolean {
        if (entity.durationMs < MIN_DURATION_MS) return false
        val frac = progressFraction(entity.lastPositionMs, entity.durationMs)
        return frac in MIN_RESUME_FRACTION..<COMPLETED_FRACTION
    }

    /**
     * Filters watch history down to unfinished movies/episodes (2%–95% watched), sorted by most
     * recently played, and deduplicates by [MediaHistoryEntity.seriesId] so each series appears
     * once with its latest active episode card.
     */
    fun buildContinueWatchingList(
        history: List<MediaHistoryEntity>,
        limit: Int = 15
    ): List<MediaHistoryEntity> {
        val seenSeriesIds = HashSet<String>()
        return history
            .asSequence()
            .filter { isUnfinished(it) }
            .sortedByDescending { it.lastPlayedAt }
            .filter { item ->
                val sid = item.seriesId?.takeIf { it.isNotBlank() }
                if (sid == null) true else seenSeriesIds.add(sid)
            }
            .take(limit)
            .toList()
    }

    /**
     * Builds a lookup map of `episodeId` (and `sourceUri` fallback) -> watched progress fraction
     * (`0f..1f`) so Series Detail sheets can display inline progress bars and watched badges.
     */
    fun buildEpisodeProgressMap(history: List<MediaHistoryEntity>): Map<String, Float> {
        val map = HashMap<String, Float>(history.size * 2)
        for (item in history) {
            val frac = progressFraction(item.lastPositionMs, item.durationMs)
            if (frac <= 0f) continue
            item.episodeId?.takeIf { it.isNotBlank() }?.let { epId ->
                val prev = map[epId] ?: 0f
                if (frac > prev) map[epId] = frac
            }
            if (item.sourceUri.isNotBlank()) {
                val prev = map[item.sourceUri] ?: 0f
                if (frac > prev) map[item.sourceUri] = frac
            }
        }
        return map
    }

    fun formatEpisodeCode(seasonNumber: Int?, episodeNumber: Int?): String? {
        if (seasonNumber == null && episodeNumber == null) return null
        return buildString {
            if (seasonNumber != null && seasonNumber > 0) append("S$seasonNumber")
            if (episodeNumber != null && episodeNumber > 0) {
                if (isNotEmpty()) append(" · ")
                append("E$episodeNumber")
            }
        }.takeIf { it.isNotBlank() }
    }

    fun formatRemainingTime(positionMs: Long, durationMs: Long): String {
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val totalMinutes = (remainingMs / 60_000.0).roundToInt().coerceAtLeast(1)
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m left"
            hours > 0 -> "${hours}h left"
            else -> "${totalMinutes}m left"
        }
    }
}
