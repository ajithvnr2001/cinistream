package com.example

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.model.JsonPlaylistItem
import com.example.model.StreamFormat
import com.example.player.ImdbMetadataClient
import com.example.player.JsonPlaylistLoadResult
import com.example.player.JsonPlaylistParser
import com.example.player.MediaFormatDetector
import com.example.ui.components.PosterStreamCard
import com.example.ui.home.JsonPlaylistLauncherRow
import com.example.ui.home.JsonPlaylistUrlInputDialog
import com.example.ui.player.JsonPlaylistSheet
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
}
