package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.SubtitlePrefsRepository
import com.example.model.JsonPlaylistItem
import com.example.model.StreamFormat
import com.example.ui.home.JsonPlaylistLauncherRow
import com.example.ui.player.JsonPlaylistSheet
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.JsonPlaylistUiState
import com.example.viewmodel.LoadedJsonPlaylist
import com.example.viewmodel.MultiJsonPlaylistReducer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiJsonPlaylistTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun subtitlePrefsRepositoryPersistsDeduplicatesAndMigratesMultipleUrls() = runBlocking {
        // Pure encode / decode / deduplicate / legacy migration checks
        val encoded = SubtitlePrefsRepository.encodeUrlList(
            listOf(
                " https://cdn.example.com/movies.json ",
                "https://cdn.example.com/series.json",
                "HTTPS://CDN.EXAMPLE.COM/MOVIES.JSON",
                "   ",
                "https://cdn.example.com/anime.json?token=1&v=2"
            )
        )
        val decoded = SubtitlePrefsRepository.decodeUrlList(encodedJson = encoded)
        assertEquals(
            listOf(
                "https://cdn.example.com/movies.json",
                "https://cdn.example.com/series.json",
                "https://cdn.example.com/anime.json?token=1&v=2"
            ),
            decoded
        )

        // Seamless migration from v1.18 single JSON_PLAYLIST_URL key
        val migrated = SubtitlePrefsRepository.decodeUrlList(
            encodedJson = null,
            legacySingleUrl = " https://legacy.example.com/v18.json "
        )
        assertEquals(listOf("https://legacy.example.com/v18.json"), migrated)

        // Corrupt JSON gracefully falls back to legacy URL
        val recovered = SubtitlePrefsRepository.decodeUrlList(
            encodedJson = "{broken",
            legacySingleUrl = "https://legacy.example.com/fallback.json"
        )
        assertEquals(listOf("https://legacy.example.com/fallback.json"), recovered)

        // End-to-end DataStore persistence round-trip
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SubtitlePrefsRepository(context)
        repo.saveJsonPlaylistUrls(
            listOf(
                "https://feed1.example.com/movies.json",
                "https://feed2.example.com/series.json"
            )
        )
        assertEquals(
            listOf(
                "https://feed1.example.com/movies.json",
                "https://feed2.example.com/series.json"
            ),
            repo.loadJsonPlaylistUrls()
        )
        assertEquals("https://feed1.example.com/movies.json", repo.loadJsonPlaylistUrl())

        // Clearing removes all saved playlist URLs
        repo.saveJsonPlaylistUrls(emptyList())
        assertTrue(repo.loadJsonPlaylistUrls().isEmpty())
        assertNull(repo.loadJsonPlaylistUrl())
    }

    @Test
    fun reducerNamespacesCollidingIdsSwitchesViewsRemovesIndividuallyAndAppliesImdb() {
        val urlA = "https://cdn.example.com/list-a.json"
        val urlB = "https://cdn.example.com/list-b.json"
        val idA = MultiJsonPlaylistReducer.stablePlaylistId(urlA, isLocal = false)
        val idB = MultiJsonPlaylistReducer.stablePlaylistId(urlB, isLocal = false)
        assertNotEquals(idA, idB)

        // Both playlists use identical item id "item_1" (and duplicate "item_1" inside B!)
        val playlistA = LoadedJsonPlaylist(
            id = idA,
            label = "Action Feed",
            sourceUrl = urlA,
            items = listOf(
                JsonPlaylistItem(
                    id = "item_1",
                    title = "The Hidden Face",
                    url = "https://cdn.example.com/hidden.m3u8",
                    groupTitle = "Thriller",
                    format = StreamFormat.M3U8
                )
            )
        )
        val playlistB = LoadedJsonPlaylist(
            id = idB,
            label = "Sci-Fi Feed",
            sourceUrl = urlB,
            items = listOf(
                JsonPlaylistItem(
                    id = "item_1",
                    title = "Tears of Steel",
                    url = "https://cdn.example.com/tos.m3u8",
                    groupTitle = "Sci-Fi",
                    format = StreamFormat.M3U8
                ),
                JsonPlaylistItem(
                    id = "item_1",
                    title = "Sintel",
                    url = "https://cdn.example.com/sintel.mp4",
                    groupTitle = "Fantasy",
                    format = StreamFormat.MP4
                )
            )
        )

        var state = JsonPlaylistUiState()
        state = MultiJsonPlaylistReducer.upsertPlaylist(state, playlistA, selectPlaylist = true)
        assertEquals(1, state.playlists.size)
        assertEquals(idA, state.selectedPlaylistId)
        assertEquals("Action Feed", state.sourceLabel)
        assertEquals(1, state.items.size)

        // Add second playlist -> both stored, newly added selected, all 3 item IDs strictly unique!
        state = MultiJsonPlaylistReducer.upsertPlaylist(state, playlistB, selectPlaylist = true)
        assertEquals(2, state.playlists.size)
        assertEquals(idB, state.selectedPlaylistId)
        assertEquals("Sci-Fi Feed", state.sourceLabel)
        assertEquals(2, state.items.size)
        assertEquals(3, state.allItems.size)
        assertEquals(3, state.allItems.map { it.id }.distinct().size)

        // Switch to combined "All" view
        state = MultiJsonPlaylistReducer.selectPlaylist(state, null)
        assertNull(state.selectedPlaylistId)
        assertEquals("2 JSON Playlists", state.sourceLabel)
        assertEquals(3, state.items.size)
        assertTrue(state.canRefreshRemote)

        // Apply IMDb enrichment to an item in playlist A while viewing "All"
        val hiddenFaceId = state.allItems.first { it.title == "The Hidden Face" }.id
        val enrichedItem = state.allItems.first { it.id == hiddenFaceId }.copy(imdbRating = "7.4")
        state = MultiJsonPlaylistReducer.applyImdbUpdates(state, mapOf(hiddenFaceId to enrichedItem))
        assertEquals("7.4", state.items.first { it.id == hiddenFaceId }.imdbRating)
        assertEquals(
            "7.4",
            state.playlists.first { it.id == idA }.items.first().imdbRating
        )

        // Updating playlist A in-place does not duplicate playlist count
        val updatedA = playlistA.copy(
            items = playlistA.items + JsonPlaylistItem(
                id = "item_2",
                title = "Big Buck Bunny",
                url = "https://cdn.example.com/bbb.mp4"
            )
        )
        state = MultiJsonPlaylistReducer.upsertPlaylist(state, updatedA, selectPlaylist = false)
        assertEquals(2, state.playlists.size)
        assertEquals(4, state.allItems.size)

        // Removing playlist B leaves playlist A cleanly selected
        state = MultiJsonPlaylistReducer.removePlaylist(state, idB)
        assertEquals(1, state.playlists.size)
        assertEquals(idA, state.selectedPlaylistId)
        assertEquals("Action Feed", state.sourceLabel)
        assertEquals(2, state.items.size)

        // Removing last playlist clears everything
        state = MultiJsonPlaylistReducer.removePlaylist(state, idA)
        assertTrue(state.playlists.isEmpty())
        assertTrue(state.items.isEmpty())
        assertFalse(state.canRefreshRemote)
    }

    @Test
    fun launcherRowAndSheetSupportSwitchingAddingAndRemovingMultiplePlaylists() {
        val urlA = "https://cdn.example.com/movies.json"
        val urlB = "https://cdn.example.com/series.json"
        val idA = MultiJsonPlaylistReducer.stablePlaylistId(urlA, isLocal = false)
        val idB = MultiJsonPlaylistReducer.stablePlaylistId(urlB, isLocal = false)

        val plA = LoadedJsonPlaylist(
            id = idA,
            label = "Movies HD",
            sourceUrl = urlA,
            items = listOf(
                JsonPlaylistItem(
                    id = "m1",
                    title = "The Hidden Face",
                    url = "https://cdn.example.com/hidden.m3u8",
                    groupTitle = "Thriller"
                )
            )
        )
        val plB = LoadedJsonPlaylist(
            id = idB,
            label = "Anime Vault",
            sourceUrl = urlB,
            items = listOf(
                JsonPlaylistItem(
                    id = "a1",
                    title = "Sintel",
                    url = "https://cdn.example.com/sintel.mp4",
                    groupTitle = "Fantasy"
                )
            )
        )

        var state by mutableStateOf(
            MultiJsonPlaylistReducer.selectPlaylist(
                MultiJsonPlaylistReducer.upsertPlaylist(
                    MultiJsonPlaylistReducer.upsertPlaylist(
                        JsonPlaylistUiState(),
                        plA,
                        selectPlaylist = true
                    ),
                    plB,
                    selectPlaylist = true
                ),
                null
            )
        )

        var addedUrlFromLauncher = false
        var addedUrlFromSheet: String? = null

        composeRule.setContent {
            CineStreamTheme {
                androidx.compose.foundation.layout.Column {
                    JsonPlaylistLauncherRow(
                        state = state,
                        onPickJsonFile = {},
                        onOpenJsonUrlDialog = { addedUrlFromLauncher = true },
                        onOpenJsonSheet = {},
                        onRefreshJsonPlaylist = {},
                        onClearJsonPlaylist = {}
                    )
                    JsonPlaylistSheet(
                        sourceLabel = state.sourceLabel,
                        items = state.items,
                        activeItemId = null,
                        isEnrichingImdb = false,
                        canRefreshUrl = state.canRefreshRemote,
                        onRefresh = {},
                        onDismiss = {},
                        onSelectItem = {},
                        playlists = state.playlists,
                        selectedPlaylistId = state.selectedPlaylistId,
                        onSelectPlaylist = { pid ->
                            state = MultiJsonPlaylistReducer.selectPlaylist(state, pid)
                        },
                        onRemovePlaylist = { pid ->
                            state = MultiJsonPlaylistReducer.removePlaylist(state, pid)
                        },
                        onAddPlaylistUrl = { url -> addedUrlFromSheet = url }
                    )
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()

        // Combined "All" view shows both playlists' items and "+ Add" button on launcher
        composeRule.onNodeWithText("All (2)").assertIsDisplayed()
        composeRule.onNodeWithText("The Hidden Face").assertIsDisplayed()
        composeRule.onNodeWithText("Sintel").assertIsDisplayed()

        // Switch to Anime Vault playlist chip
        composeRule.onNodeWithTag("json_sheet_playlist_chip_$idB").performClick()
        composeRule.waitForIdle()
        assertEquals(idB, state.selectedPlaylistId)
        assertEquals(1, state.items.size)
        assertEquals("Sintel", state.items.first().title)

        // Add another playlist URL via inline + button in JsonPlaylistSheet
        composeRule.onNodeWithTag("json_sheet_add_url_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_json_url_input")
            .performTextInput("https://cdn.example.com/third.json")
        composeRule.onNodeWithTag("dialog_json_url_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals("https://cdn.example.com/third.json", addedUrlFromSheet)

        // Remove selected playlist (Anime Vault) -> cleanly falls back to Movies HD
        composeRule.onNodeWithTag("json_sheet_playlist_chip_remove_selected").performClick()
        composeRule.waitForIdle()
        assertEquals(1, state.playlists.size)
        assertEquals(idA, state.selectedPlaylistId)
        assertEquals("The Hidden Face", state.items.first().title)

        // Verify launcher + button exists when playlists are loaded
        composeRule.onNodeWithTag("add_json_url_button").performClick()
        assertTrue(addedUrlFromLauncher)
    }
}
