package com.example

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import com.example.model.StreamFormat
import com.example.player.M3uInspectionResult
import com.example.player.M3uPlaylistParser
import com.example.player.MediaFormatDetector
import com.example.player.SubtitleHelper
import com.example.ui.components.LocalFilePickKind
import com.example.ui.components.LocalFilePickerDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalFilePickerTest {

    @Test
    fun videoPickerFallsBackAcrossOpenDocumentGetContentAndPhotoPicker() {
        val attempts = mutableListOf<String>()
        var unavailableCalled = false

        val launched = LocalFilePickerDispatcher.launchWithFallbacks(
            kind = LocalFilePickKind.VIDEO,
            launchOpenDocument = { mimes ->
                attempts.add("OpenDocument:${mimes.joinToString(",")}")
                throw ActivityNotFoundException("No DocumentsUI on TV")
            },
            launchGetContent = { mime ->
                attempts.add("GetContent:$mime")
                throw ActivityNotFoundException("No GetContent handler")
            },
            launchVisualMediaVideo = {
                attempts.add("PickVisualMedia:VideoOnly")
            },
            onUnavailable = { unavailableCalled = true }
        )

        assertTrue("Video picker should succeed via PickVisualMedia fallback", launched)
        assertFalse("onUnavailable must not fire when fallback succeeds", unavailableCalled)
        assertEquals(
            listOf(
                "OpenDocument:*/*",
                "GetContent:video/*",
                "GetContent:*/*",
                "PickVisualMedia:VideoOnly"
            ),
            attempts
        )
    }

    @Test
    fun subtitlePickerFallsBackFromOpenDocumentToGetContentAndReportsUnavailableIfAllFail() {
        val attempts = mutableListOf<String>()
        var unavailableKind: LocalFilePickKind? = null

        val launchedFallback = LocalFilePickerDispatcher.launchWithFallbacks(
            kind = LocalFilePickKind.SUBTITLE,
            launchOpenDocument = { mimes ->
                attempts.add("OpenDocument:${mimes.joinToString(",")}")
                throw ActivityNotFoundException("No DocumentsUI")
            },
            launchGetContent = { mime ->
                attempts.add("GetContent:$mime")
            },
            launchVisualMediaVideo = { attempts.add("UnexpectedVisualMedia") },
            onUnavailable = { unavailableKind = it }
        )

        assertTrue(launchedFallback)
        assertEquals(listOf("OpenDocument:*/*", "GetContent:*/*"), attempts)
        assertEquals(null, unavailableKind)

        // When every picker fails, onUnavailable must fire cleanly
        val launchedAllFail = LocalFilePickerDispatcher.launchWithFallbacks(
            kind = LocalFilePickKind.M3U_PLAYLIST,
            launchOpenDocument = { throw ActivityNotFoundException() },
            launchGetContent = { throw ActivityNotFoundException() },
            launchVisualMediaVideo = { throw ActivityNotFoundException() },
            onUnavailable = { unavailableKind = it }
        )
        assertFalse(launchedAllFail)
        assertEquals(LocalFilePickKind.M3U_PLAYLIST, unavailableKind)
    }

    @Test
    fun pickedLocalSubtitleIsCachedDurablyAndSupportsOffsetShifting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = SubtitleHelper(context, OkHttpClient())

        val sourceFile = File(context.cacheDir, "movie_english.srt")
        sourceFile.writeText(
            """
            1
            00:00:02,000 --> 00:00:05,000
            Hello from local subtitle!
            """.trimIndent(),
            Charsets.UTF_8
        )

        val externalSub = helper.buildFromLocalUri(Uri.fromFile(sourceFile))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, externalSub.mimeType)
        assertEquals("SRT", externalSub.formatBadge)
        assertEquals("movie_english.srt", externalSub.label)

        // Delete original source to verify app-private durable copy still works
        sourceFile.delete()

        val shiftedUri = helper.resolvePlayableSubtitleUri(externalSub, offsetMs = 1_500L)
        val shiftedText = File(shiftedUri.path!!).readText(Charsets.UTF_8)
        assertTrue(
            "Shifted subtitle should contain updated timestamp 00:00:03,500",
            shiftedText.contains("00:00:03,500 --> 00:00:06,500")
        )
    }

    @Test
    fun parsesLocalFileM3uPlaylistAndDetectsLocalContentFormats() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = M3uPlaylistParser(context, OkHttpClient())

        val localM3u = File(context.cacheDir, "channels.m3u")
        localM3u.writeText(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Movies",Hidden Face HLS
            https://example.com/hidden-face/master.m3u8
            """.trimIndent(),
            Charsets.UTF_8
        )

        val inspection = parser.inspect(Uri.fromFile(localM3u).toString(), isLocalFile = true)
        assertTrue("Local file:// M3U should parse into ChannelPlaylist", inspection is M3uInspectionResult.ChannelPlaylist)
        assertEquals(1, (inspection as M3uInspectionResult.ChannelPlaylist).entries.size)

        // Verify content:// URI format detection with display names
        val contentUri = "content://com.android.externalstorage.documents/document/primary%3AMovies%2Ffilm.mkv"
        assertEquals(StreamFormat.MKV, MediaFormatDetector.detectFormat(contentUri, "The.Hidden.Face.2011.mkv"))
        assertEquals(StreamFormat.MP4, MediaFormatDetector.detectFormat("content://media/picker/0/123", "clip.mp4"))
        assertEquals(StreamFormat.M3U, MediaFormatDetector.detectFormat("content://downloads/42", "iptv.m3u"))
    }
}
