package com.example.ui.components

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private const val TAG = "LocalFilePicker"

enum class LocalFilePickKind {
    VIDEO,
    M3U_PLAYLIST,
    JSON_PLAYLIST,
    SUBTITLE
}

// Pure, testable multi-tier launcher dispatcher.
// Never pre-blocks on PackageManager.resolveActivity() (which fails on Android 11+ without visibility
// or certain OEM/TV setups). Instead attempts each zero-permission Android picker contract in order:
//   - VIDEO: OpenDocument -> GetContent(video) -> GetContent(any) -> PickVisualMedia(VideoOnly)
//   - M3U_PLAYLIST / SUBTITLE: OpenDocument -> GetContent(any)
internal object LocalFilePickerDispatcher {

    fun launchWithFallbacks(
        kind: LocalFilePickKind,
        launchOpenDocument: (Array<String>) -> Unit,
        launchGetContent: (String) -> Unit,
        launchVisualMediaVideo: () -> Unit,
        onUnavailable: (LocalFilePickKind) -> Unit
    ): Boolean {
        val attempts: List<Pair<String, () -> Unit>> = when (kind) {
            LocalFilePickKind.VIDEO -> listOf(
                "OpenDocument(*/*)" to { launchOpenDocument(arrayOf("*/*")) },
                "GetContent(video/*)" to { launchGetContent("video/*") },
                "GetContent(*/*)" to { launchGetContent("*/*") },
                "PickVisualMedia(VideoOnly)" to launchVisualMediaVideo
            )
            LocalFilePickKind.M3U_PLAYLIST,
            LocalFilePickKind.JSON_PLAYLIST,
            LocalFilePickKind.SUBTITLE -> listOf(
                "OpenDocument(*/*)" to { launchOpenDocument(arrayOf("*/*")) },
                "GetContent(*/*)" to { launchGetContent("*/*") }
            )
        }

        for ((label, attempt) in attempts) {
            val succeeded = runCatching { attempt() }
                .onFailure { err ->
                    Log.w(TAG, "Picker attempt $label failed for $kind: ${err.javaClass.simpleName}: ${err.message}")
                }
                .isSuccess
            if (succeeded) return true
        }

        onUnavailable(kind)
        return false
    }
}

@Stable
class LocalFilePickerController internal constructor(
    private val launchPicker: (LocalFilePickKind) -> Unit
) {
    fun pickVideo() = launchPicker(LocalFilePickKind.VIDEO)
    fun pickM3uPlaylist() = launchPicker(LocalFilePickKind.M3U_PLAYLIST)
    fun pickJsonPlaylist() = launchPicker(LocalFilePickKind.JSON_PLAYLIST)
    fun pickSubtitle() = launchPicker(LocalFilePickKind.SUBTITLE)
}

/**
 * Provides resilient, zero-permission local file picking across Android Mobile (API 24-36)
 * and Android TV devices using SAF OpenDocument, GetContent, and Android Photo Picker fallbacks.
 */
@Composable
fun rememberLocalFilePicker(
    onVideoPicked: (Uri) -> Unit = {},
    onM3uPicked: (Uri) -> Unit = {},
    onJsonPicked: (Uri) -> Unit = {},
    onSubtitlePicked: (Uri) -> Unit = {},
    onPickerUnavailable: (LocalFilePickKind) -> Unit = {}
): LocalFilePickerController {
    val latestOnVideo by rememberUpdatedState(onVideoPicked)
    val latestOnM3u by rememberUpdatedState(onM3uPicked)
    val latestOnJson by rememberUpdatedState(onJsonPicked)
    val latestOnSubtitle by rememberUpdatedState(onSubtitlePicked)
    val latestOnUnavailable by rememberUpdatedState(onPickerUnavailable)

    var pendingKindName by rememberSaveable { mutableStateOf<String?>(null) }

    val dispatchResult: (Uri?) -> Unit = { uri ->
        val kind = pendingKindName?.let { name ->
            runCatching { LocalFilePickKind.valueOf(name) }.getOrNull()
        }
        pendingKindName = null
        if (uri != null && kind != null) {
            when (kind) {
                LocalFilePickKind.VIDEO -> latestOnVideo(uri)
                LocalFilePickKind.M3U_PLAYLIST -> latestOnM3u(uri)
                LocalFilePickKind.JSON_PLAYLIST -> latestOnJson(uri)
                LocalFilePickKind.SUBTITLE -> latestOnSubtitle(uri)
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = dispatchResult
    )

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = dispatchResult
    )

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = dispatchResult
    )

    return remember {
        LocalFilePickerController { kind ->
            pendingKindName = kind.name
            val launched = LocalFilePickerDispatcher.launchWithFallbacks(
                kind = kind,
                launchOpenDocument = { mimeTypes -> openDocumentLauncher.launch(mimeTypes) },
                launchGetContent = { mimeType -> getContentLauncher.launch(mimeType) },
                launchVisualMediaVideo = {
                    pickVisualMediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onUnavailable = { failedKind ->
                    pendingKindName = null
                    latestOnUnavailable(failedKind)
                }
            )
            if (!launched) {
                pendingKindName = null
            }
        }
    }
}
