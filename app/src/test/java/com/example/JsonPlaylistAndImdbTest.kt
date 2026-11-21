package com.example

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.model.JsonPlaylistItem
import com.example.model.MediaContentType
import com.example.model.SeriesEpisode
import com.example.model.SeriesSeason
import com.example.model.StreamFormat
import com.example.player.CdnMirrorResolver
import com.example.player.ImdbMetadataClient
import com.example.player.JsonPlaylistLoadResult
import com.example.player.JsonPlaylistParser
import com.example.player.MediaFormatDetector
import com.example.ui.components.PosterStreamCard
import com.example.ui.home.JsonPlaylistLauncherRow
import com.example.ui.home.JsonPlaylistUrlInputDialog
import com.example.ui.player.JsonPlaylistSheet
import com.example.ui.player.SeriesDetailSheet
import com.example.ui.player.TracksSheet
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.JsonPlaylistUiState
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JsonPlaylistAndImdbTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun parsesTopLevelArrayAndWrappedCategoriesJsonPlaylists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = JsonPlaylistParser(context, OkHttpClient())

        val arrayJson = """
            [
              {
                "id": "item-1",
                "title": "The Hidden Face",
                "url": "https://cdn.example.com/hidden-face/master.m3u8",
                "imdbUrl": "https://www.imdb.com/title/tt1772240/",
                "poster": "https://m.media-amazon.com/images/hidden_face.jpg",
                "imdbRating": 7.4,
                "year": "2011",
                "subtitleUrl": "https://cdn.example.com/subs/en.srt",
                "subtitleLabel": "English"
              },
              {
                "title": "Big Buck Bunny",
                "sources": ["https://cdn.example.com/bbb.mp4"],
                "imdbId": "tt1254207",
                "genres": ["Animation", "Short"],
                "subtitles": [
                  { "url": "https://cdn.example.com/subs/bbb.vtt", "label": "English VTT" }
                ]
              }
            ]
        """.trimIndent()

        val arrayResult = parser.parseJson(arrayJson)
        assertTrue(arrayResult is JsonPlaylistLoadResult.Success)
        val arrayItems = (arrayResult as JsonPlaylistLoadResult.Success).items
        assertEquals(2, arrayItems.size)

        val first = arrayItems[0]
        assertEquals("The Hidden Face", first.title)
        assertEquals("tt1772240", first.imdbId)
        assertEquals("7.4", first.imdbRating)
        assertEquals("2011", first.releaseYear)
        assertEquals(StreamFormat.M3U8, first.format)
        assertEquals("https://m.media-amazon.com/images/hidden_face.jpg", first.imageUrl)
        assertEquals("https://cdn.example.com/subs/en.srt", first.subtitleUrl)
        assertEquals("English", first.subtitleLabel)

        val second = arrayItems[1]
        assertEquals("https://cdn.example.com/bbb.mp4", second.url)
        assertEquals(StreamFormat.MP4, second.format)
        assertEquals("tt1254207", second.imdbId)
        assertEquals("Animation · Short", second.groupTitle)
        assertEquals("https://cdn.example.com/subs/bbb.vtt", second.subtitleUrl)
        assertEquals("English VTT", second.subtitleLabel)

        val wrappedCategoriesJson = """
            {
              "playlistName": "Weekend IMDb Picks",
              "categories": [
                {
                  "name": "Sci-Fi",
                  "videos": [
                    {
                      "title": "Tears of Steel",
                      "streamUrl": "https://cdn.example.com/tos/playlist.mpd",
                      "imdb": "tt2285752",
                      "i": { "imageUrl": "https://m.media-amazon.com/images/tos.jpg" }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val wrappedResult = parser.parseJson(wrappedCategoriesJson)
        assertTrue(wrappedResult is JsonPlaylistLoadResult.Success)
        val success = wrappedResult as JsonPlaylistLoadResult.Success
        assertEquals("Weekend IMDb Picks", success.sourceTitle)
        assertEquals(1, success.items.size)
        assertEquals("Sci-Fi", success.items[0].groupTitle)
        assertEquals(StreamFormat.DASH, success.items[0].format)
        assertEquals("tt2285752", success.items[0].imdbId)
        assertEquals("https://m.media-amazon.com/images/tos.jpg", success.items[0].imageUrl)
    }

    @Test
    fun loadsJsonPlaylistFromHttpUrlAndLocalFileAndDetectsJsonUrls() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val remotePayload = """
            {
              "title": "Remote Feed",
              "movies": [
                {
                  "title": "Sintel",
                  "url": "https://cdn.example.com/sintel.mkv",
                  "imdbId": "tt1727587",
                  "image": "//m.media-amazon.com/images/sintel.jpg"
                }
              ]
            }
        """.trimIndent()

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(remotePayload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val parser = JsonPlaylistParser(context, mockHttpClient)

        val invalidScheme = parser.loadFromUrl("ftp://example.com/playlist.json")
        assertTrue(invalidScheme is JsonPlaylistLoadResult.Error)

        val remoteResult = parser.loadFromUrl("https://example.com/catalog.json?v=1")
        assertTrue(remoteResult is JsonPlaylistLoadResult.Success)
        val remoteSuccess = remoteResult as JsonPlaylistLoadResult.Success
        assertEquals("Remote Feed", remoteSuccess.sourceTitle)
        assertEquals(1, remoteSuccess.items.size)
        assertEquals("https://m.media-amazon.com/images/sintel.jpg", remoteSuccess.items[0].imageUrl)
        assertEquals(StreamFormat.MKV, remoteSuccess.items[0].format)

        val localJsonFile = File(context.cacheDir, "local_movies.json")
        localJsonFile.writeText(
            """[{"title":"Local Film","url":"https://cdn.example.com/local.mp4","imdbId":"tt0807840"}]""",
            Charsets.UTF_8
        )
        val localResult = parser.loadFromLocalUri(Uri.fromFile(localJsonFile))
        assertTrue(localResult is JsonPlaylistLoadResult.Success)
        assertEquals("Local Film", (localResult as JsonPlaylistLoadResult.Success).items[0].title)
        localJsonFile.delete()

        assertTrue(MediaFormatDetector.shouldInspectAsJson("https://example.com/movies.json"))
        assertTrue(MediaFormatDetector.shouldInspectAsJson("https://example.com/playlist.json?token=abc"))
        assertTrue(MediaFormatDetector.shouldInspectAsJson("content://downloads/12", "catalog.json"))
        assertFalse(MediaFormatDetector.shouldInspectAsJson("https://example.com/master.m3u8"))
    }

    @Test
    fun imdbMetadataClientParsesSuggestionJsonJsonLdAndCachesLookup() = runBlocking {
        val suggestionJson = """
            {
              "d": [
                {
                  "id": "nm0000001",
                  "l": "Not a title"
                },
                {
                  "id": "tt1772240",
                  "l": "The Hidden Face",
                  "y": 2011,
                  "s": "Quim Gutierrez, Martina Garcia",
                  "i": {
                    "imageUrl": "https://m.media-amazon.com/images/M/hidden_face_poster.jpg"
                  }
                }
              ]
            }
        """.trimIndent()

        val titleHtml = """
            <html>
              <head>
                <script type="application/ld+json">
                {
                  "name": "The Hidden Face",
                  "image": "https://m.media-amazon.com/images/M/hidden_face_poster.jpg",
                  "description": "A Spanish orchestra conductor investigates the mysterious disappearance of his girlfriend.",
                  "genre": ["Drama", "Mystery", "Thriller"],
                  "datePublished": "2011-09-16",
                  "aggregateRating": { "ratingValue": 7.4 }
                }
                </script>
              </head>
            </html>
        """.trimIndent()

        var suggestionHits = 0
        var titlePageHits = 0
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val (bodyText, contentType) = when {
                    url.contains("v3.sg.media-imdb.com/suggestion/") -> {
                        suggestionHits++
                        suggestionJson to "application/json"
                    }
                    url.contains("www.imdb.com/title/tt1772240") -> {
                        titlePageHits++
                        titleHtml to "text/html"
                    }
                    else -> "{}" to "application/json"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyText.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()

        val client = ImdbMetadataClient(mockHttpClient)

        assertEquals("tt1772240", ImdbMetadataClient.extractImdbId("https://www.imdb.com/title/TT1772240/"))
        assertNull(ImdbMetadataClient.extractImdbId("no-imdb-id-here"))
        assertEquals(
            "The Hidden Face",
            ImdbMetadataClient.cleanTitleForSearch("The.Hidden.Face.(2011).[1080p].BluRay.mkv")
        )

        val parsedSuggestion = client.parseSuggestionJson(suggestionJson, expectedImdbId = "tt1772240")
        assertNotNull(parsedSuggestion)
        assertEquals("tt1772240", parsedSuggestion?.imdbId)
        assertEquals("2011", parsedSuggestion?.year)
        assertEquals("https://m.media-amazon.com/images/M/hidden_face_poster.jpg", parsedSuggestion?.posterUrl)

        val parsedLd = client.parseImdbHtmlJsonLd(titleHtml, "tt1772240")
        assertNotNull(parsedLd)
        assertEquals("7.4", parsedLd?.rating)
        assertEquals("Drama · Mystery · Thriller", parsedLd?.genres)
        assertEquals("2011", parsedLd?.year)

        val firstLookup = client.lookup(titleQuery = "The Hidden Face (2011)", releaseYear = "2011")
        assertNotNull(firstLookup)
        assertEquals("tt1772240", firstLookup?.imdbId)
        assertEquals("7.4", firstLookup?.rating)
        assertEquals("https://m.media-amazon.com/images/M/hidden_face_poster.jpg", firstLookup?.posterUrl)
        assertEquals(1, suggestionHits)
        assertEquals(1, titlePageHits)

        // Second lookup should hit in-memory ConcurrentHashMap cache without extra HTTP calls
        val cachedLookup = client.lookup(imdbId = "tt1772240")
        assertEquals(firstLookup, cachedLookup)
        assertEquals(1, suggestionHits)
        assertEquals(1, titlePageHits)
    }

    @Test
    fun posterCardClickStartsPlaybackAndLauncherOpensUrlDialog() {
        var dialogOpened = false
        var clickedPosterItem: JsonPlaylistItem? = null

        val sampleItem = JsonPlaylistItem(
            id = "hidden_face_1",
            title = "The Hidden Face",
            url = "https://cdn.example.com/hidden-face/master.m3u8",
            groupTitle = "Thriller",
            format = StreamFormat.M3U8,
            imageUrl = "https://m.media-amazon.com/images/hidden_face.jpg",
            imdbId = "tt1772240",
            imdbRating = "7.4",
            releaseYear = "2011",
            subtitleUrl = "https://cdn.example.com/hidden-face/en.srt"
        )

        composeRule.setContent {
            CineStreamTheme {
                androidx.compose.foundation.layout.Column {
                    JsonPlaylistLauncherRow(
                        state = JsonPlaylistUiState(),
                        onPickJsonFile = {},
                        onOpenJsonUrlDialog = { dialogOpened = true },
                        onOpenJsonSheet = {},
                        onRefreshJsonPlaylist = {},
                        onClearJsonPlaylist = {}
                    )
                    PosterStreamCard(
                        title = sampleItem.title,
                        subtitle = sampleItem.groupTitle,
                        imageUrl = sampleItem.imageUrl,
                        format = sampleItem.format,
                        imdbId = sampleItem.imdbId,
                        imdbRating = sampleItem.imdbRating,
                        releaseYear = sampleItem.releaseYear,
                        hasSubtitle = true,
                        testTag = "json_poster_${sampleItem.id}",
                        onClick = { clickedPosterItem = sampleItem }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("IMDb 7.4").assertIsDisplayed()
        composeRule.onNodeWithText("2011").assertIsDisplayed()
        composeRule.onNodeWithTag("load_json_url_button").performClick()
        assertTrue(dialogOpened)

        composeRule.onNodeWithTag("json_poster_hidden_face_1").performClick()
        assertEquals(sampleItem, clickedPosterItem)
    }

    @Test
    fun jsonPlaylistUrlDialogSubmitsEnteredUrl() {
        var submittedUrl: String? = null

        composeRule.setContent {
            CineStreamTheme {
                JsonPlaylistUrlInputDialog(
                    initialUrl = "",
                    onDismiss = {},
                    onConfirm = { submittedUrl = it }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_json_url_input")
            .performTextInput("https://example.com/movies.json")
        composeRule.onNodeWithTag("dialog_json_url_confirm").performClick()
        assertEquals("https://example.com/movies.json", submittedUrl)
    }

    @Test
    fun jsonPlaylistSheetClickPlaysSelectedMovieAndDismisses() {
        val items = listOf(
            JsonPlaylistItem(
                id = "m1",
                title = "Sintel",
                url = "https://cdn.example.com/sintel.mp4",
                groupTitle = "Fantasy",
                format = StreamFormat.MP4,
                imdbId = "tt1727587",
                imdbRating = "7.4",
                releaseYear = "2010",
                description = "A wandering girl searches for her dragon."
            ),
            JsonPlaylistItem(
                id = "m2",
                title = "Tears of Steel",
                url = "https://cdn.example.com/tos.m3u8",
                groupTitle = "Sci-Fi",
                format = StreamFormat.M3U8,
                imdbId = "tt2285752",
                imdbRating = "5.6",
                releaseYear = "2012"
            )
        )
        var selected: JsonPlaylistItem? = null
        var dismissed = false

        composeRule.setContent {
            CineStreamTheme {
                JsonPlaylistSheet(
                    sourceLabel = "IMDb Showcase",
                    items = items,
                    activeItemId = "m1",
                    isEnrichingImdb = false,
                    canRefreshUrl = true,
                    onRefresh = {},
                    onDismiss = { dismissed = true },
                    onSelectItem = { selected = it }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("IMDb Showcase").assertIsDisplayed()
        composeRule.onNodeWithTag("json_entry_m2").performClick()

        assertEquals("m2", selected?.id)
        assertTrue(dismissed)
    }

    @Test
    fun parsesUnifiedMoviesAndSeriesWithMirrorsAndInheritedHeaders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = JsonPlaylistParser(context, OkHttpClient())

        val combinedJson = """
            {
              "playlistName": "CineStream Hybrid Catalog",
              "cdnMirrors": ["https://edge2.example.com"],
              "headers": {
                "Referer": "https://origin.example.com",
                "X-Playlist": "v11"
              },
              "items": [
                {
                  "id": "movie-1",
                  "type": "movie",
                  "title": "The Hidden Face",
                  "url": "https://origin.example.com/movies/hidden-face/master.m3u8",
                  "mirrors": ["https://backup.example.com/movies/hidden-face/master.m3u8"],
                  "imdbId": "tt1772240"
                },
                {
                  "id": "series-1",
                  "type": "series",
                  "title": "Blender Anthology",
                  "imdbId": "tt1727587",
                  "headers": { "X-Series": "blender" },
                  "seasons": [
                    {
                      "seasonNumber": 1,
                      "title": "Season 1",
                      "headers": { "X-Season": "s1" },
                      "episodes": [
                        {
                          "id": "s1e1",
                          "episodeNumber": 1,
                          "title": "Big Buck Bunny",
                          "url": "https://origin.example.com/series/s1e1.mp4",
                          "mirrors": ["https://backup.example.com/series/s1e1.mp4"],
                          "duration": "10m",
                          "headers": { "X-Episode": "e1", "X-Playlist": "override" }
                        },
                        {
                          "id": "s1e2",
                          "episodeNumber": 2,
                          "title": "Sintel",
                          "url": "https://origin.example.com/series/s1e2.mp4"
                        }
                      ]
                    },
                    {
                      "seasonNumber": 2,
                      "title": "Season 2",
                      "episodes": [
                        {
                          "id": "s2e1",
                          "episodeNumber": 1,
                          "title": "Tears of Steel",
                          "url": "https://origin.example.com/series/s2e1.m3u8"
                        }
                      ]
                    }
                  ]
                }
              ],
              "series": [
                {
                  "id": "flat-series",
                  "title": "Flat Mini-Series",
                  "episodes": [
                    {
                      "episode": 1,
                      "title": "Pilot",
                      "url": "https://origin.example.com/flat/ep1.mp4"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parser.parseJson(combinedJson)
        assertTrue(result is JsonPlaylistLoadResult.Success)
        val success = result as JsonPlaylistLoadResult.Success
        assertEquals("CineStream Hybrid Catalog", success.sourceTitle)
        assertEquals(listOf("https://edge2.example.com"), success.playlistCdnMirrors)
        assertEquals(3, success.items.size)

        val movie = success.items[0]
        assertFalse(movie.isSeries)
        assertEquals(MediaContentType.MOVIE, movie.contentType)
        assertTrue(movie.mirrors.contains("https://backup.example.com/movies/hidden-face/master.m3u8"))
        assertTrue(movie.mirrors.contains("https://edge2.example.com/movies/hidden-face/master.m3u8"))
        assertEquals("https://origin.example.com", movie.headers["Referer"])

        val series = success.items[1]
        assertTrue(series.isSeries)
        assertEquals(MediaContentType.SERIES, series.contentType)
        assertEquals(2, series.seasons.size)
        assertEquals(3, series.totalEpisodes)
        assertEquals("SERIES · 2S", series.seriesBadgeLabel)

        val ep1 = series.seasons[0].episodes[0]
        assertEquals("S1 E1", ep1.codeLabel)
        assertEquals("https://origin.example.com", ep1.headers["Referer"])
        assertEquals("override", ep1.headers["X-Playlist"])
        assertEquals("blender", ep1.headers["X-Series"])
        assertEquals("s1", ep1.headers["X-Season"])
        assertEquals("e1", ep1.headers["X-Episode"])
        assertTrue(ep1.mirrors.contains("https://backup.example.com/series/s1e1.mp4"))
        assertTrue(ep1.mirrors.contains("https://edge2.example.com/series/s1e1.mp4"))

        // Auto-next episode traversal across seasons
        assertEquals("s1e2", series.findNextEpisode("s1e1")?.id)
        assertEquals("s2e1", series.findNextEpisode("s1e2")?.id)
        assertNull(series.findNextEpisode("s2e1"))

        val flatSeries = success.items[2]
        assertTrue(flatSeries.isSeries)
        assertEquals(1, flatSeries.seasons.size)
        assertEquals("S1 E1", flatSeries.seasons[0].episodes[0].codeLabel)
    }

    @Test
    fun cdnMirrorResolverHandlesCandidateUrlsCacheRulesAndDiskCaps() {
        val candidates = CdnMirrorResolver.buildCandidateUrls(
            primaryUrl = "https://worker1.workers.dev/pub/film/master.m3u8?token=abc",
            itemMirrors = listOf(
                "https://backup.cdn.net/film/master.m3u8",
                "https://worker1.workers.dev/pub/film/master.m3u8?token=abc",
                "ftp://invalid.example.com/file.mp4"
            ),
            playlistCdnMirrors = listOf("https://edge2.example.com")
        )
        assertEquals(
            listOf(
                "https://worker1.workers.dev/pub/film/master.m3u8?token=abc",
                "https://backup.cdn.net/film/master.m3u8",
                "https://edge2.example.com/pub/film/master.m3u8?token=abc"
            ),
            candidates
        )

        // Cache rules: VOD segments cached, manifests/subtitles/local bypassed
        assertTrue(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/seg001.ts")))
        assertTrue(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/chunk.m4s")))
        assertTrue(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/movie.mp4")))
        assertTrue(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/movie.mkv")))
        assertFalse(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/master.m3u8")))
        assertFalse(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/stream.mpd")))
        assertFalse(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/sub.srt")))
        assertFalse(CdnMirrorResolver.shouldCacheUri(Uri.parse("https://cdn.example.com/sub.vtt")))
        assertFalse(CdnMirrorResolver.shouldCacheUri(Uri.parse("content://media/external/video/1")))
        assertFalse(CdnMirrorResolver.shouldCacheUri(Uri.parse("file:///sdcard/movie.mp4")))

        assertEquals(64L * 1024L * 1024L, CdnMirrorResolver.computeSafeDiskCacheBytes(true))
        assertEquals(128L * 1024L * 1024L, CdnMirrorResolver.computeSafeDiskCacheBytes(false))
    }

    @Test
    fun seriesDetailSheetSwitchesSeasonsAndPlaysSelectedEpisode() {
        val epS1E1 = SeriesEpisode(
            id = "ep_s1_1",
            seasonNumber = 1,
            episodeNumber = 1,
            title = "Pilot Episode",
            url = "https://cdn.example.com/s1e1.mp4",
            format = StreamFormat.MP4
        )
        val epS2E1 = SeriesEpisode(
            id = "ep_s2_1",
            seasonNumber = 2,
            episodeNumber = 1,
            title = "Return to Cinema",
            url = "https://cdn.example.com/s2e1.m3u8",
            format = StreamFormat.M3U8,
            mirrors = listOf("https://backup.example.com/s2e1.m3u8")
        )
        val series = JsonPlaylistItem(
            id = "series_test",
            title = "Chronicles",
            url = epS1E1.url,
            groupTitle = "Sci-Fi",
            format = StreamFormat.MP4,
            contentType = MediaContentType.SERIES,
            seasons = listOf(
                SeriesSeason(1, "Season 1", listOf(epS1E1)),
                SeriesSeason(2, "Season 2", listOf(epS2E1))
            )
        )

        var selectedEp: SeriesEpisode? = null
        var dismissed = false

        composeRule.setContent {
            CineStreamTheme {
                SeriesDetailSheet(
                    series = series,
                    activeEpisodeId = "ep_s1_1",
                    onDismiss = { dismissed = true },
                    onSelectEpisode = { _, ep -> selectedEp = ep }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Chronicles").assertIsDisplayed()
        composeRule.onNodeWithTag("series_season_chip_2").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("S2 E1").assertIsDisplayed()
        composeRule.onNodeWithTag("series_episode_ep_s2_1").performClick()

        assertEquals("ep_s2_1", selectedEp?.id)
        assertTrue(dismissed)
    }

    @Test
    fun jsonPlaylistSheetFiltersMoviesAndSeries() {
        val mixedItems = listOf(
            JsonPlaylistItem(
                id = "mov_1",
                title = "Sintel",
                url = "https://cdn.example.com/sintel.mp4",
                groupTitle = "Fantasy",
                format = StreamFormat.MP4,
                contentType = MediaContentType.MOVIE
            ),
            JsonPlaylistItem(
                id = "ser_1",
                title = "Blender Anthology",
                url = "https://cdn.example.com/ep1.mp4",
                groupTitle = "Animation",
                format = StreamFormat.MP4,
                contentType = MediaContentType.SERIES,
                seasons = listOf(
                    SeriesSeason(
                        1,
                        "Season 1",
                        listOf(
                            SeriesEpisode(
                                id = "e1",
                                seasonNumber = 1,
                                episodeNumber = 1,
                                title = "Ep 1",
                                url = "https://cdn.example.com/ep1.mp4"
                            )
                        )
                    )
                )
            )
        )

        composeRule.setContent {
            CineStreamTheme {
                JsonPlaylistSheet(
                    sourceLabel = "Mixed Catalog",
                    items = mixedItems,
                    activeItemId = null,
                    isEnrichingImdb = false,
                    canRefreshUrl = false,
                    onRefresh = {},
                    onDismiss = {},
                    onSelectItem = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("json_type_filter_series").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("json_entry_ser_1").assertIsDisplayed()
    }

    @Test
    fun tracksSheetExposesCdnMirrorAndEdgeCacheControls() {
        var switchedCdn = false
        var clearedCache = false

        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    activeCdnIndex = 1,
                    totalCdnNodes = 3,
                    activeCdnHost = "edge1.example.com",
                    onSwitchCdnMirror = { switchedCdn = true },
                    diskCacheUsedBytes = 8L * 1024L * 1024L,
                    diskCacheMaxBytes = 64L * 1024L * 1024L,
                    onClearEdgeCache = { clearedCache = true },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tracks_switch_cdn_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_clear_edge_cache_button").performScrollTo().performClick()
        assertTrue(switchedCdn)
        assertTrue(clearedCache)
    }
}
