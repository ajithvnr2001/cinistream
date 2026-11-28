package com.example.model

enum class StreamFormat(val label: String, val badge: String) {
    AUTO("Auto Detect", "AUTO"),
    HLS("HLS / M3U8", "HLS"),
    M3U8("M3U8 Stream", "M3U8"),
    M3U("M3U Playlist", "M3U"),
    MKV("Matroska MKV", "MKV"),
    MP4("MP4 Video", "MP4"),
    DASH("MPEG-DASH", "DASH"),
    WEBM("WebM Video", "WEBM")
}

data class ExternalSubtitle(
    val id: String,
    val uri: String,
    val label: String,
    val mimeType: String,
    val formatBadge: String,
    val isLocalFile: Boolean = false
)

data class ExternalAudioTrack(
    val id: String,
    val url: String,
    val label: String,
    val formatBadge: String = "HLS",
    val isHls: Boolean = true,
    val isDash: Boolean = false
) {
    companion object {
        const val TRACK_ID_PREFIX = "ext_audio_"

        fun taggedTrackId(audioId: String, groupIndex: Int, trackIndex: Int): String =
            "$TRACK_ID_PREFIX$audioId:$groupIndex:$trackIndex"

        fun extractAudioId(trackId: String?): String? {
            if (trackId.isNullOrBlank() || !trackId.startsWith(TRACK_ID_PREFIX)) return null
            val remainder = trackId.removePrefix(TRACK_ID_PREFIX)
            return remainder.substringBefore(':').takeIf { it.isNotBlank() }
        }
    }
}

/**
 * External audio track specification authored inside a JSON playlist item (movie, collection film,
 * or series episode). Auto-loaded and merged when playback starts.
 */
data class PlaylistAudioSpec(
    val url: String,
    val label: String? = null,
    val language: String? = null,
    val isDefault: Boolean? = null,
    val delayMs: Long? = null
)


data class M3uEntry(
    val id: String,
    val title: String,
    val url: String,
    val groupTitle: String = "Playlist",
    val format: StreamFormat = StreamFormat.AUTO,
    val tvgId: String? = null,
    val tvgLogo: String? = null
)

enum class MediaContentType {
    MOVIE,
    SERIES,
    COLLECTION
}

data class SeriesEpisode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val url: String,
    val mirrors: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val format: StreamFormat = StreamFormat.AUTO,
    val description: String? = null,
    val durationLabel: String? = null,
    val imageUrl: String? = null,
    val subtitleUrl: String? = null,
    val subtitleLabel: String? = null,
    val externalAudios: List<PlaylistAudioSpec> = emptyList()
) {
    val codeLabel: String get() = "S${seasonNumber} E${episodeNumber}"
}

data class SeriesSeason(
    val seasonNumber: Int,
    val title: String,
    val episodes: List<SeriesEpisode>
)

/**
 * Individual film inside a Movie Collection / Franchise (e.g. Resident Evil, Final Destination, Evil Dead).
 * Each film carries its own IMDb ID, poster, rating, release year, mirrors, and headers—never TV S/E codes.
 */
data class CollectionMovie(
    val id: String,
    val order: Int,
    val title: String,
    val url: String,
    val mirrors: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val format: StreamFormat = StreamFormat.AUTO,
    val imageUrl: String? = null,
    val imdbId: String? = null,
    val imdbRating: String? = null,
    val releaseYear: String? = null,
    val durationLabel: String? = null,
    val description: String? = null,
    val subtitleUrl: String? = null,
    val subtitleLabel: String? = null,
    val externalAudios: List<PlaylistAudioSpec> = emptyList()
)

data class JsonPlaylistItem(
    val id: String,
    val title: String,
    val url: String,
    val groupTitle: String = "Movies",
    val format: StreamFormat = StreamFormat.AUTO,
    val imageUrl: String? = null,
    val imdbId: String? = null,
    val imdbRating: String? = null,
    val releaseYear: String? = null,
    val description: String? = null,
    val subtitleUrl: String? = null,
    val subtitleLabel: String? = null,
    val contentType: MediaContentType = MediaContentType.MOVIE,
    val mirrors: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val seasons: List<SeriesSeason> = emptyList(),
    val collectionMovies: List<CollectionMovie> = emptyList(),
    val externalAudios: List<PlaylistAudioSpec> = emptyList()
) {
    val isSeries: Boolean
        get() = contentType == MediaContentType.SERIES && seasons.isNotEmpty()

    val isCollection: Boolean
        get() = contentType == MediaContentType.COLLECTION && collectionMovies.isNotEmpty()

    val totalEpisodes: Int
        get() = seasons.sumOf { it.episodes.size }

    val totalCollectionMovies: Int
        get() = collectionMovies.size

    val seriesBadgeLabel: String?
        get() = when {
            isSeries -> "SERIES · ${seasons.size}S"
            isCollection -> {
                val count = collectionMovies.size
                if (count == 1) "COLLECTION · 1 FILM" else "COLLECTION · $count FILMS"
            }
            else -> null
        }

    fun allEpisodesOrdered(): List<SeriesEpisode> =
        seasons.sortedBy { it.seasonNumber }
            .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }

    fun orderedCollectionMovies(): List<CollectionMovie> =
        collectionMovies.sortedWith(
            compareBy<CollectionMovie> { it.order }
                .thenBy { it.releaseYear?.toIntOrNull() ?: Int.MAX_VALUE }
        )

    fun findNextEpisode(currentEpisodeId: String?): SeriesEpisode? {
        if (currentEpisodeId.isNullOrBlank()) return null
        val ordered = allEpisodesOrdered()
        val idx = ordered.indexOfFirst { it.id == currentEpisodeId }
        return if (idx in 0 until ordered.lastIndex) ordered[idx + 1] else null
    }

    fun findNextCollectionMovie(currentMovieId: String?): CollectionMovie? {
        if (currentMovieId.isNullOrBlank()) return null
        val ordered = orderedCollectionMovies()
        val idx = ordered.indexOfFirst { it.id == currentMovieId }
        return if (idx in 0 until ordered.lastIndex) ordered[idx + 1] else null
    }
}

data class TrackOption(
    val id: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val detail: String,
    val language: String?,
    val isSelected: Boolean,
    val isExternal: Boolean = false,
    val externalAudioId: String? = null
)

enum class SubtitleTextSize(val label: String, val sp: Float) {
    SMALL("Small", 15f),
    MEDIUM("Medium", 19f),
    LARGE("Large", 24f),
    EXTRA_LARGE("XL", 30f)
}

enum class SubtitleColorPreset(val label: String, val argb: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    AMBER("Amber", 0xFFFBBF24.toInt()),
    YELLOW("Yellow", 0xFFFDE047.toInt()),
    CYAN("Cyan", 0xFF7DD3FC.toInt())
}

enum class SubtitleBgPreset(val label: String, val argb: Int) {
    TRANSLUCENT("Cinema Dim", 0xB3080A0F.toInt()),
    SOLID("Solid Black", 0xFF000000.toInt()),
    TRANSPARENT("Shadow Only", 0x00000000)
}

data class SubtitleAppearance(
    val textSize: SubtitleTextSize = SubtitleTextSize.MEDIUM,
    val colorPreset: SubtitleColorPreset = SubtitleColorPreset.WHITE,
    val bgPreset: SubtitleBgPreset = SubtitleBgPreset.TRANSLUCENT
)

enum class AspectRatioMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom")
}

data class SampleStream(
    val title: String,
    val subtitleHint: String,
    val url: String,
    val format: StreamFormat,
    val sampleSubtitleUrl: String? = null,
    val sampleSubtitleName: String? = null,
    val imdbId: String? = null,
    val posterUrl: String? = null,
    val imdbRating: String? = null,
    val releaseYear: String? = null
)

object SampleStreamsCatalog {
    val items = listOf(
        SampleStream(
            title = "The Hidden Face (2011) HLS",
            subtitleHint = "Spanish Movie · Multi-track HLS",
            url = "https://multicloud-s3-index.ajithvnr2001.workers.dev/pub/9f712691f8937399bfe52e34c80a450d/rustfs-demo-2/The.hidden.face.2011-hls/master.m3u8",
            format = StreamFormat.HLS,
            imdbId = "tt1772240",
            releaseYear = "2011"
        ),
        SampleStream(
            title = "Tears of Steel (HLS Adaptive)",
            subtitleHint = "Sci-Fi Short · Multi-bitrate HLS",
            url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
            format = StreamFormat.HLS,
            imdbId = "tt2285752",
            releaseYear = "2012"
        ),
        SampleStream(
            title = "Big Buck Bunny (MP4 HD)",
            subtitleHint = "Animated Short · Progressive MP4",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            format = StreamFormat.MP4,
            imdbId = "tt1254207",
            releaseYear = "2008"
        ),
        SampleStream(
            title = "Sintel Open Movie (HLS M3U8)",
            subtitleHint = "Fantasy Short · HLS Stream",
            url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
            format = StreamFormat.M3U8,
            imdbId = "tt1727587",
            releaseYear = "2010"
        ),
        SampleStream(
            title = "Elephants Dream (MP4)",
            subtitleHint = "Blender Open Movie · MP4",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            format = StreamFormat.MP4,
            imdbId = "tt0807840",
            releaseYear = "2006"
        )
    )
}
