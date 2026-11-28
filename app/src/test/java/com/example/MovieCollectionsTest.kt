package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.GlobalSearchEngine
import com.example.model.CollectionMovie
import com.example.model.JsonPlaylistItem
import com.example.model.MediaContentType
import com.example.model.StreamFormat
import com.example.player.ImdbMetadataClient
import com.example.player.ImdbMovieInfo
import com.example.player.JsonPlaylistLoadResult
import com.example.player.JsonPlaylistParser
import com.example.ui.player.CollectionDetailSheet
import com.example.ui.player.JsonPlaylistSheet
import com.example.ui.theme.CineStreamTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MovieCollectionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun jsonPlaylistParser_parsesEgPlaylistV2CollectionsBothNestedAndFlatGrouped() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val parser = JsonPlaylistParser(ctx, OkHttpClient())

        // Locate eg-playlist-v2.json at repo root (working dir may be /app or project root)
        val candidates = listOf(File("../eg-playlist-v2.json"), File("eg-playlist-v2.json"))
        val jsonFile = candidates.firstOrNull { it.exists() }
        assertNotNull("eg-playlist-v2.json must exist at project root", jsonFile)

        val result = parser.parseJson(jsonFile!!.readText(Charsets.UTF_8))
        assertTrue("Expected Success parse result, got $result", result is JsonPlaylistLoadResult.Success)
        val items = (result as JsonPlaylistLoadResult.Success).items

        // 1 standalone movie + 3 collections (Resident Evil, Final Destination, Evil Dead) + 1 series = 5 items
        assertEquals(5, items.size)

        val movies = items.filter { it.contentType == MediaContentType.MOVIE }
        val collections = items.filter { it.isCollection }
        val series = items.filter { it.isSeries }
        assertEquals(1, movies.size)
        assertEquals(3, collections.size)
        assertEquals(1, series.size)

        val residentEvil = collections.first { it.title == "Resident Evil Collection" }
        assertEquals(6, residentEvil.collectionMovies.size)
        assertEquals("COLLECTION · 6 FILMS", residentEvil.seriesBadgeLabel)
        assertEquals("2002–2016", residentEvil.releaseYear)
        assertEquals("tt0120804", residentEvil.collectionMovies[0].imdbId)
        assertEquals("tt2592614", residentEvil.collectionMovies[5].imdbId)
        assertEquals(
            "resident-evil-apocalypse-2004",
            residentEvil.findNextCollectionMovie("resident-evil-2002")?.id
        )

        val finalDest = collections.first { it.title == "Final Destination Collection" }
        assertEquals(5, finalDest.collectionMovies.size)
        assertEquals("2000–2011", finalDest.releaseYear)

        // Flat "collection": "Evil Dead Collection" items must auto-group into a single COLLECTION item
        val evilDead = collections.first { it.title == "Evil Dead Collection" }
        assertEquals(5, evilDead.collectionMovies.size)
        assertEquals("1981–2023", evilDead.releaseYear)
        assertEquals("The Evil Dead", evilDead.collectionMovies[0].title)
        assertEquals("Evil Dead Rise", evilDead.collectionMovies[4].title)
        assertEquals("tt13345606", evilDead.collectionMovies[4].imdbId)
    }

    @Test
    fun imdbMetadataClient_enrichesEveryMovieInCollectionAndBackfillsCoverAndAverageRating(): Unit = runBlocking {
        val rawCollection = JsonPlaylistItem(
            id = "re_col",
            title = "Resident Evil Collection",
            url = "https://example.com/re1.m3u8",
            groupTitle = "Collections",
            contentType = MediaContentType.COLLECTION,
            collectionMovies = listOf(
                CollectionMovie(
                    id = "re1",
                    order = 1,
                    title = "Resident Evil",
                    url = "https://example.com/re1.m3u8",
                    imdbId = "tt0120804",
                    releaseYear = "2002"
                ),
                CollectionMovie(
                    id = "re2",
                    order = 2,
                    title = "Resident Evil: Apocalypse",
                    url = "https://example.com/re2.m3u8",
                    imdbId = "tt0318627",
                    releaseYear = "2004"
                )
            )
        )

        val lookedUpIds = mutableListOf<String>()
        val enriched = ImdbMetadataClient.enrichCollectionItem(rawCollection) { imdbId, _, _ ->
            if (imdbId != null) lookedUpIds.add(imdbId)
            when (imdbId) {
                "tt0120804" -> ImdbMovieInfo(
                    imdbId = "tt0120804",
                    title = "Resident Evil",
                    year = "2002",
                    posterUrl = "https://m.media-amazon.com/re1.jpg",
                    rating = "6.6",
                    genres = "Action · Horror · Sci-Fi",
                    plot = "Umbrella lab outbreak."
                )
                "tt0318627" -> ImdbMovieInfo(
                    imdbId = "tt0318627",
                    title = "Resident Evil: Apocalypse",
                    year = "2004",
                    posterUrl = "https://m.media-amazon.com/re2.jpg",
                    rating = "6.2",
                    genres = "Action · Horror",
                    plot = "Raccoon City quarantine."
                )
                else -> null
            }
        }

        assertEquals(setOf("tt0120804", "tt0318627"), lookedUpIds.toSet())
        assertEquals("https://m.media-amazon.com/re1.jpg", enriched.imageUrl)
        assertEquals("6.4", enriched.imdbRating)
        assertEquals("2002–2004", enriched.releaseYear)
        assertEquals("Action · Horror · Sci-Fi", enriched.groupTitle)

        assertEquals("https://m.media-amazon.com/re1.jpg", enriched.collectionMovies[0].imageUrl)
        assertEquals("6.6", enriched.collectionMovies[0].imdbRating)
        assertEquals("Umbrella lab outbreak.", enriched.collectionMovies[0].description)

        assertEquals("https://m.media-amazon.com/re2.jpg", enriched.collectionMovies[1].imageUrl)
        assertEquals("6.2", enriched.collectionMovies[1].imdbRating)
        assertEquals("Raccoon City quarantine.", enriched.collectionMovies[1].description)
    }

    @Test
    fun globalSearchEngine_findsBothCollectionAndIndividualSequelMovies() {
        val evilDeadCollection = JsonPlaylistItem(
            id = "col_evil_dead",
            title = "Evil Dead Collection",
            url = "https://example.com/ed1.m3u8",
            groupTitle = "Horror",
            contentType = MediaContentType.COLLECTION,
            releaseYear = "1981–2023",
            collectionMovies = listOf(
                CollectionMovie(
                    id = "ed1",
                    order = 1,
                    title = "The Evil Dead",
                    url = "https://example.com/ed1.m3u8",
                    imdbId = "tt0083907",
                    imdbRating = "7.4",
                    releaseYear = "1981"
                ),
                CollectionMovie(
                    id = "ed5",
                    order = 2,
                    title = "Evil Dead Rise",
                    url = "https://example.com/ed5.m3u8",
                    imdbId = "tt13345606",
                    imdbRating = "6.5",
                    releaseYear = "2023"
                )
            )
        )

        val riseResults = GlobalSearchEngine.search(
            query = "Evil Dead Rise",
            favorites = emptyList(),
            jsonItems = listOf(evilDeadCollection),
            m3uEntries = emptyList(),
            downloads = emptyList(),
            history = emptyList()
        )
        assertEquals(1, riseResults.size)
        assertEquals("Evil Dead Rise", riseResults.first().title)
        assertTrue(riseResults.first().subtitle.contains("Evil Dead Collection"))
        assertTrue(riseResults.first().subtitle.contains("2023"))
    }

    @Test
    fun collectionDetailSheet_showsPerMovieImdbWithoutEpisodeLabels_andHandlesPlayAndDownload() {
        val collection = JsonPlaylistItem(
            id = "fd_col",
            title = "Final Destination Collection",
            url = "https://example.com/fd1.m3u8",
            groupTitle = "Horror · Thriller",
            releaseYear = "2000–2006",
            imdbRating = "6.3",
            contentType = MediaContentType.COLLECTION,
            collectionMovies = listOf(
                CollectionMovie(
                    id = "fd1",
                    order = 1,
                    title = "Final Destination",
                    url = "https://example.com/fd1.m3u8",
                    format = StreamFormat.HLS,
                    imdbId = "tt0195714",
                    imdbRating = "6.7",
                    releaseYear = "2000",
                    durationLabel = "1h 38m"
                ),
                CollectionMovie(
                    id = "fd2",
                    order = 2,
                    title = "Final Destination 2",
                    url = "https://example.com/fd2.m3u8",
                    format = StreamFormat.HLS,
                    imdbId = "tt0309593",
                    imdbRating = "6.2",
                    releaseYear = "2003",
                    durationLabel = "1h 30m"
                ),
                CollectionMovie(
                    id = "fd3",
                    order = 3,
                    title = "Final Destination 3",
                    url = "https://example.com/fd3.m3u8",
                    format = StreamFormat.MP4,
                    imdbId = "tt0414982",
                    imdbRating = "5.8",
                    releaseYear = "2006",
                    durationLabel = "1h 33m"
                )
            )
        )

        var playedMovie: CollectionMovie? = null
        var downloadedMovie: CollectionMovie? = null
        composeRule.setContent {
            CineStreamTheme {
                CollectionDetailSheet(
                    collection = collection,
                    activeMovieId = null,
                    movieProgressMap = mapOf("fd1" to 0.96f),
                    onDismiss = {},
                    onSelectMovie = { _, m -> playedMovie = m },
                    onDownloadMovie = { _, m -> downloadedMovie = m }
                )
            }
        }

        composeRule.onNodeWithTag("collection_detail_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("COLLECTION · 3 FILMS").assertIsDisplayed()
        composeRule.onNodeWithText("Final Destination").assertIsDisplayed()
        composeRule.onNodeWithText("Final Destination 2").assertIsDisplayed()
        composeRule.onNodeWithText("#1").assertIsDisplayed()
        composeRule.onNodeWithText("#2").assertIsDisplayed()
        composeRule.onNodeWithText("Watched").assertIsDisplayed()

        // Zero TV episode codes anywhere in the Collections UI
        assertEquals(0, composeRule.onAllNodesWithText("S1 E1", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("S1 E2", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("E1", substring = false).fetchSemanticsNodes().size)

        // Download movie #2
        composeRule.onNodeWithTag("collection_movie_download_fd2").performClick()
        assertNotNull(downloadedMovie)
        assertEquals("fd2", downloadedMovie!!.id)

        // Smart "Play Next" CTA skips watched fd1 and launches fd2
        composeRule.onNodeWithTag("collection_play_next_button").performClick()
        assertNotNull(playedMovie)
        assertEquals("fd2", playedMovie!!.id)
    }

    @Test
    fun jsonPlaylistSheet_displaysCollectionsFilterChipAndFiltersToCollections() {
        val items = listOf(
            JsonPlaylistItem(
                id = "m1",
                title = "The Hidden Face",
                url = "https://example.com/hidden.m3u8",
                contentType = MediaContentType.MOVIE
            ),
            JsonPlaylistItem(
                id = "c1",
                title = "Resident Evil Collection",
                url = "https://example.com/re1.m3u8",
                contentType = MediaContentType.COLLECTION,
                collectionMovies = listOf(
                    CollectionMovie(
                        id = "re1",
                        order = 1,
                        title = "Resident Evil",
                        url = "https://example.com/re1.m3u8"
                    ),
                    CollectionMovie(
                        id = "re2",
                        order = 2,
                        title = "Resident Evil: Apocalypse",
                        url = "https://example.com/re2.m3u8"
                    )
                )
            )
        )

        var selected: JsonPlaylistItem? = null
        composeRule.setContent {
            CineStreamTheme {
                JsonPlaylistSheet(
                    sourceLabel = "Test Catalog",
                    items = items,
                    activeItemId = null,
                    isEnrichingImdb = false,
                    canRefreshUrl = false,
                    onRefresh = {},
                    onDismiss = {},
                    onSelectItem = { selected = it }
                )
            }
        }

        composeRule.onNodeWithTag("json_type_filter_collections").assertIsDisplayed()
        composeRule.onNodeWithTag("json_type_filter_collections").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("COLLECTION · 2 FILMS").assertIsDisplayed()
        composeRule.onNodeWithTag("json_entry_c1").performClick()

        assertNotNull(selected)
        assertEquals("c1", selected!!.id)
        assertTrue(selected!!.isCollection)
    }

    @Test
    fun version36MetadataIsSet() {
        assertEquals(39, BuildConfig.VERSION_CODE)
        assertEquals("1.39", BuildConfig.VERSION_NAME)
    }
}
