package com.example.player

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.example.data.NetworkServerEntity
import com.example.model.ExternalSubtitle
import com.example.model.StreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class NetworkBrowserItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val pathOrParentId: String,
    val playbackUrl: String = "",
    val format: StreamFormat = StreamFormat.AUTO,
    val sizeBytes: Long = 0L,
    val posterUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val siblingSubtitles: List<ExternalSubtitle> = emptyList(),
    val mimeType: String? = null
)

data class NetworkBrowseResult(
    val items: List<NetworkBrowserItem>,
    val errorMessage: String? = null
)

data class NetworkConnectionTestResult(
    val isSuccess: Boolean,
    val message: String,
    val itemCount: Int = 0
)

/**
 * Multi-Account Cloud & LAN Media Server Client supporting:
 * 1. WebDAV (RFC 4918 `PROPFIND` Depth: 1 XML directory listing, UTF-8 HTTP Basic Auth,
 *    same-origin redirect preservation for 301/302/307/308, TorBox/Nextcloud/Synology/Koofr
 *    URL encoding & special-character safety, and sibling subtitle discovery).
 * 2. Jellyfin REST API (API Key auth + library/folder browsing + direct static stream playback).
 *
 * Credentials are stored encrypted in Room via [CredentialVault] (Android Keystore AES-256-GCM).
 */
class LocalNetworkClient(
    private val okHttpClient: OkHttpClient
) {
    private val noRedirectClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    companion object {
        const val TYPE_WEBDAV = "WEBDAV"
        const val TYPE_JELLYFIN = "JELLYFIN"
        const val TORBOX_WEBDAV_URL = "https://webdav.torbox.app/"
        private const val MAX_PROPFIND_REDIRECTS = 5

        private val PLAYABLE_EXTENSIONS = setOf(
            // Video & streaming formats supported by Media3 + NextLib FFmpeg
            "mp4", "mkv", "webm", "m3u8", "ts", "m2ts", "mts", "mov", "avi", "m4v",
            "mpd", "flv", "3gp", "wmv", "mpg", "mpeg", "vob", "ogv", "f4v",
            // Audio formats supported by Media3 + NextLib FFmpeg
            "mp3", "aac", "m4a", "flac", "ogg", "opus", "wav", "wma", "ac3", "eac3", "dts"
        )

        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

        /**
         * Normalizes a user-entered WebDAV or Jellyfin URL. Rejects non-HTTP(S) schemes cleanly.
         */
        fun normalizeBaseUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""
            val lower = trimmed.lowercase(Locale.US)
            if (lower.contains("://") &&
                !lower.startsWith("http://") &&
                !lower.startsWith("https://")
            ) {
                return ""
            }
            val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) {
                trimmed
            } else {
                // Default remote domain-like hosts to HTTPS, LAN IPs / ports to HTTP
                val hostPart = trimmed.substringBefore('/')
                val isLikelyLan = hostPart.startsWith("192.168.") ||
                    hostPart.startsWith("10.") ||
                    hostPart.startsWith("172.") ||
                    hostPart.startsWith("127.") ||
                    hostPart.startsWith("localhost", ignoreCase = true) ||
                    hostPart.contains(":")
                if (isLikelyLan) "http://$trimmed" else "https://$trimmed"
            }
            val parsed = withScheme.toHttpUrlOrNull() ?: return ""
            return parsed.toString().trimEnd('/')
        }

        /**
         * Ensures a WebDAV directory target URL ends with `/` so servers like TorBox, Nginx,
         * and SabreDAV do not issue redundant 301 redirects on `PROPFIND`.
         */
        fun ensureDirectoryTrailingSlash(url: String): String {
            val trimmed = url.trim()
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }

        /**
         * Encodes RFC 7617 UTF-8 HTTP Basic Auth header while preserving all password characters
         * (including `?`, `^`, `$`, `)`, `/`, `;`, `{`, `(`, leading/trailing spaces) byte-for-byte.
         */
        fun buildBasicAuthHeader(username: String, password: String): String? {
            if (username.isEmpty() && password.isEmpty()) return null
            val token = Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return "Basic $token"
        }

        /**
         * Returns true only if [fromUrl] and [toUrl] share the exact same scheme, host, and port,
         * ensuring Basic Auth credentials are never leaked across origins during redirects.
         */
        fun isSameOrigin(fromUrl: String, toUrl: String): Boolean {
            val from = fromUrl.toHttpUrlOrNull() ?: return false
            val to = toUrl.toHttpUrlOrNull() ?: return false
            return from.scheme.equals(to.scheme, ignoreCase = true) &&
                from.host.equals(to.host, ignoreCase = true) &&
                from.port == to.port
        }

        /**
         * Resolves a WebDAV `<D:href>` value (absolute, root-relative, or relative, even when
         * containing unencoded spaces, brackets `[...]`, `#`, `{`, `}`, etc.) into a valid HTTP(S) URL.
         */
        fun resolveWebDavHref(currentDirUrl: String, rawHref: String): String? {
            val trimmed = rawHref.trim()
            if (trimmed.isEmpty()) return null
            val safeHref = sanitizeHrefForUri(trimmed)
            val normalizedBase = ensureDirectoryTrailingSlash(currentDirUrl)
            val baseHttpUrl = normalizedBase.toHttpUrlOrNull() ?: return null
            val resolved = if (safeHref.startsWith("http://", true) || safeHref.startsWith("https://", true)) {
                safeHref.toHttpUrlOrNull()?.toString()
            } else {
                baseHttpUrl.resolve(safeHref)?.toString()
                    ?: runCatching { URI(normalizedBase).resolve(safeHref).toString() }.getOrNull()
            } ?: return null
            return if (resolved.startsWith("http://", true) || resolved.startsWith("https://", true)) {
                resolved
            } else {
                null
            }
        }

        /**
         * Percent-encodes illegal URI path characters (spaces, `[`, `]`, `#`, `{`, `}`, `^`, etc.)
         * while preserving valid URL delimiters (`/`, `:`, `?`, `&`, `=`) and existing `%XX` escapes.
         */
        internal fun sanitizeHrefForUri(rawHref: String): String {
            val sb = StringBuilder(rawHref.length + 16)
            var i = 0
            while (i < rawHref.length) {
                val c = rawHref[i]
                when {
                    c == '%' && i + 2 < rawHref.length &&
                        isHexDigit(rawHref[i + 1]) && isHexDigit(rawHref[i + 2]) -> {
                        sb.append(rawHref, i, i + 3)
                        i += 3
                    }
                    c == '%' -> {
                        sb.append("%25")
                        i++
                    }
                    c == ' ' -> {
                        sb.append("%20")
                        i++
                    }
                    c in charArrayOf('[', ']', '#', '{', '}', '^', '|', '\\', '"', '<', '>', '`') -> {
                        val bytes = c.toString().toByteArray(Charsets.UTF_8)
                        for (b in bytes) {
                            sb.append(String.format(Locale.US, "%%%02X", b.toInt() and 0xFF))
                        }
                        i++
                    }
                    c.code > 0x7E || c.code < 0x20 -> {
                        val bytes = c.toString().toByteArray(Charsets.UTF_8)
                        for (b in bytes) {
                            sb.append(String.format(Locale.US, "%%%02X", b.toInt() and 0xFF))
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }

        private fun isHexDigit(ch: Char): Boolean =
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

        fun isPlayableMedia(fileName: String, contentType: String? = null): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext.isNotEmpty() && ext in PLAYABLE_EXTENSIONS) return true
            val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
            return mime.startsWith("video/") ||
                mime.startsWith("audio/") ||
                mime == "application/vnd.apple.mpegurl" ||
                mime == "application/x-mpegurl" ||
                mime == "application/dash+xml"
        }

        fun isSubtitleFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            return ext.isNotEmpty() && ext in SUBTITLE_EXTENSIONS
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0L) return ""
            return when {
                bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
                bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
                bytes >= 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
                else -> "$bytes B"
            }
        }

        private fun normalizeDecodedPath(url: String): String {
            val rawPath = url.toHttpUrlOrNull()?.encodedPath
                ?: runCatching { URI(url).rawPath }.getOrNull()
                ?: ""
            val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
            return decoded.trimEnd('/')
        }

        private data class RawWebDavEntry(
            val resolvedUrl: String,
            val name: String,
            val isFolder: Boolean,
            val sizeBytes: Long,
            val contentType: String?
        )

        fun parseWebDavPropfindXml(
            xml: String,
            currentDirUrl: String,
            authHeader: String?
        ): List<NetworkBrowserItem> {
            val currentPathNormalized = normalizeDecodedPath(ensureDirectoryTrailingSlash(currentDirUrl))
            val rawEntries = mutableListOf<RawWebDavEntry>()

            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
                // Disable DTD/external entity processing for untrusted remote WebDAV XML
                runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inResponse = false
            var href: String? = null
            var displayName: String? = null
            var isCollection = false
            var contentLength = 0L
            var contentType: String? = null

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase(Locale.US) ?: ""
                        when (name) {
                            "response" -> {
                                inResponse = true
                                href = null
                                displayName = null
                                isCollection = false
                                contentLength = 0L
                                contentType = null
                            }
                            "href" -> if (inResponse) {
                                val text = parser.nextText().trim()
                                if (text.isNotEmpty()) href = text
                            }
                            "displayname" -> if (inResponse) {
                                val text = parser.nextText().trim()
                                if (text.isNotEmpty()) displayName = text
                            }
                            "collection" -> if (inResponse) isCollection = true
                            "getcontentlength" -> if (inResponse) {
                                val parsed = parser.nextText().trim().toLongOrNull()
                                if (parsed != null && parsed >= 0L) contentLength = parsed
                            }
                            "getcontenttype" -> if (inResponse) {
                                val text = parser.nextText().trim()
                                if (text.isNotEmpty()) {
                                    contentType = text
                                    if (text.equals("httpd/unix-directory", ignoreCase = true) ||
                                        text.equals("inode/directory", ignoreCase = true)
                                    ) {
                                        isCollection = true
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("response", ignoreCase = true) && inResponse) {
                            val rawHref = href
                            if (!rawHref.isNullOrBlank()) {
                                val resolvedUrl = resolveWebDavHref(currentDirUrl, rawHref)
                                if (resolvedUrl != null) {
                                    val entryPath = normalizeDecodedPath(resolvedUrl)
                                    val isSelf = entryPath == currentPathNormalized ||
                                        resolvedUrl.trimEnd('/') == currentDirUrl.trimEnd('/')
                                    if (!isSelf) {
                                        val isFolder = isCollection ||
                                            (rawHref.endsWith("/") && contentLength == 0L && contentType.isNullOrBlank())
                                        val fallbackName = entryPath.substringAfterLast('/')
                                        val finalName = displayName
                                            ?.trim()
                                            ?.trimEnd('/')
                                            ?.takeIf { it.isNotBlank() }
                                            ?: fallbackName
                                        rawEntries.add(
                                            RawWebDavEntry(
                                                resolvedUrl = if (isFolder) ensureDirectoryTrailingSlash(resolvedUrl) else resolvedUrl,
                                                name = finalName.ifBlank { "Item" },
                                                isFolder = isFolder,
                                                sizeBytes = contentLength,
                                                contentType = contentType
                                            )
                                        )
                                    }
                                }
                            }
                            inResponse = false
                        }
                    }
                }
                eventType = parser.next()
            }

            // Collect sibling subtitle files in this folder so they can auto-attach during playback
            val folderSubtitles = rawEntries
                .filter { !it.isFolder && isSubtitleFile(it.name) }
                .map { sub ->
                    val (mime, badge) = detectSubtitleMimeAndBadge(sub.name)
                    ExternalSubtitle(
                        id = "webdav_sub_${sub.resolvedUrl.hashCode()}",
                        label = sub.name.substringBeforeLast('.').ifBlank { sub.name },
                        uri = sub.resolvedUrl,
                        mimeType = mime,
                        formatBadge = badge,
                        isLocalFile = false
                    )
                }

            val headers = if (!authHeader.isNullOrBlank()) {
                mapOf("Authorization" to authHeader)
            } else {
                emptyMap()
            }

            val items = mutableListOf<NetworkBrowserItem>()
            for (entry in rawEntries) {
                if (entry.isFolder || isPlayableMedia(entry.name, entry.contentType)) {
                    val detectedFormat = if (entry.isFolder) {
                        StreamFormat.AUTO
                    } else {
                        detectFormatFromNameOrMime(entry.name, entry.contentType)
                    }
                    items.add(
                        NetworkBrowserItem(
                            id = entry.resolvedUrl,
                            name = entry.name,
                            isFolder = entry.isFolder,
                            pathOrParentId = entry.resolvedUrl,
                            playbackUrl = if (entry.isFolder) "" else entry.resolvedUrl,
                            format = detectedFormat,
                            sizeBytes = entry.sizeBytes,
                            requestHeaders = headers,
                            siblingSubtitles = if (entry.isFolder) emptyList() else folderSubtitles,
                            mimeType = entry.contentType
                        )
                    )
                }
            }

            return items.sortedWith(
                compareByDescending<NetworkBrowserItem> { it.isFolder }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
        }

        private fun detectSubtitleMimeAndBadge(fileName: String): Pair<String, String> {
            return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
                "vtt" -> MimeTypes.TEXT_VTT to "VTT"
                "ass" -> MimeTypes.TEXT_SSA to "ASS"
                "ssa" -> MimeTypes.TEXT_SSA to "SSA"
                "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML to "TTML"
                else -> MimeTypes.APPLICATION_SUBRIP to "SRT"
            }
        }

        private fun detectFormatFromNameOrMime(fileName: String, contentType: String?): StreamFormat {
            val fromName = MediaFormatDetector.detectFormat(fileName)
            if (fromName != StreamFormat.AUTO) return fromName
            val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
            return when {
                mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl" -> StreamFormat.HLS
                mime == "application/dash+xml" -> StreamFormat.DASH
                mime == "video/x-matroska" -> StreamFormat.MKV
                mime == "video/mp4" -> StreamFormat.MP4
                mime == "video/webm" -> StreamFormat.WEBM
                else -> StreamFormat.AUTO
            }
        }

        fun parseJellyfinItemsJson(
            json: String,
            baseUrl: String,
            apiKey: String
        ): List<NetworkBrowserItem> {
            val root = JSONObject(json)
            val arr = root.optJSONArray("Items") ?: return emptyList()
            val cleanBase = baseUrl.trimEnd('/')
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val result = mutableListOf<NetworkBrowserItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("Id").trim()
                val name = obj.optString("Name").trim()
                if (id.isBlank() || name.isBlank()) continue
                val isFolder = obj.optBoolean("IsFolder", false) ||
                    obj.optString("Type").equals("CollectionFolder", ignoreCase = true) ||
                    obj.optString("Type").equals("Folder", ignoreCase = true) ||
                    obj.optString("Type").equals("Series", ignoreCase = true) ||
                    obj.optString("Type").equals("Season", ignoreCase = true)
                val posterUrl = "$cleanBase/Items/$id/Images/Primary?maxWidth=360&api_key=$encodedKey"
                val playbackUrl = if (isFolder) {
                    ""
                } else {
                    "$cleanBase/Videos/$id/stream?static=true&api_key=$encodedKey"
                }
                result.add(
                    NetworkBrowserItem(
                        id = id,
                        name = name,
                        isFolder = isFolder,
                        pathOrParentId = id,
                        playbackUrl = playbackUrl,
                        format = StreamFormat.AUTO,
                        posterUrl = posterUrl,
                        requestHeaders = if (apiKey.isNotBlank()) mapOf("X-Emby-Token" to apiKey) else emptyMap()
                    )
                )
            }
            return result.sortedWith(
                compareByDescending<NetworkBrowserItem> { it.isFolder }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
        }
    }

    suspend fun testConnection(
        baseUrl: String,
        serverType: String,
        username: String,
        secret: String
    ): NetworkConnectionTestResult = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeBaseUrl(baseUrl)
        if (cleanUrl.isBlank()) {
            return@withContext NetworkConnectionTestResult(
                isSuccess = false,
                message = "Enter a valid http:// or https:// server URL."
            )
        }
        val result = when (serverType.uppercase(Locale.US)) {
            TYPE_JELLYFIN -> browseJellyfin(cleanUrl, secret, null)
            else -> browseWebDav(cleanUrl, username, secret, ensureDirectoryTrailingSlash(cleanUrl))
        }
        if (result.errorMessage != null) {
            NetworkConnectionTestResult(
                isSuccess = false,
                message = result.errorMessage
            )
        } else {
            val folders = result.items.count { it.isFolder }
            val media = result.items.count { !it.isFolder }
            NetworkConnectionTestResult(
                isSuccess = true,
                message = "Connected · $folders folder${if (folders == 1) "" else "s"}, $media playable file${if (media == 1) "" else "s"}",
                itemCount = result.items.size
            )
        }
    }

    suspend fun browse(
        server: NetworkServerEntity,
        folderPathOrId: String? = null
    ): NetworkBrowseResult = withContext(Dispatchers.IO) {
        val username = CredentialVault.decrypt(server.usernameEnc)
        val secret = CredentialVault.decrypt(server.secretEnc)
        val baseUrl = normalizeBaseUrl(server.baseUrl)
        if (baseUrl.isBlank()) {
            return@withContext NetworkBrowseResult(
                items = emptyList(),
                errorMessage = "Invalid server URL. Edit this account and enter a valid http:// or https:// address."
            )
        }
        return@withContext when (server.serverType.uppercase(Locale.US)) {
            TYPE_JELLYFIN -> browseJellyfin(baseUrl, secret, folderPathOrId)
            else -> {
                val target = folderPathOrId?.takeIf { it.isNotBlank() } ?: baseUrl
                browseWebDav(baseUrl, username, secret, ensureDirectoryTrailingSlash(target))
            }
        }
    }

    private fun browseWebDav(
        baseUrl: String,
        username: String,
        password: String,
        targetUrl: String
    ): NetworkBrowseResult {
        return runCatching {
            val initialUrl = ensureDirectoryTrailingSlash(targetUrl.ifBlank { baseUrl })
            val authHeader = buildBasicAuthHeader(username, password)
            val propfindXml = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getcontenttype/>
  </D:prop>
</D:propfind>"""

            var currentUrl = initialUrl
            var redirectCount = 0
            while (true) {
                val propfindBody = propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaType())
                val reqBuilder = Request.Builder()
                    .url(currentUrl)
                    .method("PROPFIND", propfindBody)
                    .header("Depth", "1")
                    .header("Accept", "application/xml, text/xml;q=0.9, */*;q=0.1")
                    .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                if (!authHeader.isNullOrBlank() && isSameOrigin(initialUrl, currentUrl)) {
                    reqBuilder.header("Authorization", authHeader)
                }

                noRedirectClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location")
                        if (location.isNullOrBlank() || redirectCount >= MAX_PROPFIND_REDIRECTS) {
                            return@use NetworkBrowseResult(
                                items = emptyList(),
                                errorMessage = "WebDAV redirect failed (HTTP $code)"
                            )
                        }
                        val nextUrl = resolveWebDavHref(currentUrl, location)
                            ?: return@use NetworkBrowseResult(
                                items = emptyList(),
                                errorMessage = "Invalid WebDAV redirect location"
                            )
                        currentUrl = nextUrl
                        redirectCount++
                        return@use null
                    }

                    if (!response.isSuccessful && code != 207) {
                        val readable = when (code) {
                            401 -> "Authentication failed (HTTP 401) — verify username and password."
                            403 -> "Access denied (HTTP 403) — check WebDAV account permissions."
                            404 -> "WebDAV folder not found (HTTP 404) — check the URL."
                            405 -> "WebDAV PROPFIND not allowed (HTTP 405) — verify this is a WebDAV URL."
                            429 -> "Server rate-limited (HTTP 429) — wait a moment and retry."
                            else -> "WebDAV HTTP $code"
                        }
                        return@use NetworkBrowseResult(
                            items = emptyList(),
                            errorMessage = readable
                        )
                    }

                    val xml = response.body?.string().orEmpty()
                    val effectiveAuth = if (isSameOrigin(initialUrl, currentUrl)) authHeader else null
                    val items = parseWebDavPropfindXml(xml, currentUrl, effectiveAuth)
                    return@use NetworkBrowseResult(items = items)
                }?.let { finalResult ->
                    return@runCatching finalResult
                }
            }
            @Suppress("UNREACHABLE_CODE")
            NetworkBrowseResult(items = emptyList(), errorMessage = "WebDAV connection failed")
        }.getOrElse { err ->
            NetworkBrowseResult(
                items = emptyList(),
                errorMessage = err.message ?: "WebDAV connection failed"
            )
        }
    }

    private fun browseJellyfin(
        baseUrl: String,
        apiKey: String,
        parentId: String?
    ): NetworkBrowseResult {
        if (apiKey.isBlank()) {
            return NetworkBrowseResult(
                items = emptyList(),
                errorMessage = "Enter a Jellyfin API Key in Server Settings"
            )
        }
        return runCatching {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val url = if (parentId.isNullOrBlank()) {
                "$baseUrl/Items?IncludeItemTypes=CollectionFolder,Folder,Movie,Series,Episode,Video&Recursive=false&api_key=$encodedKey"
            } else {
                "$baseUrl/Items?ParentId=${URLEncoder.encode(parentId, "UTF-8")}&SortBy=SortName&SortOrder=Ascending&api_key=$encodedKey"
            }
            val request = Request.Builder()
                .url(url)
                .header("X-Emby-Token", apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val readable = when (response.code) {
                        401 -> "Jellyfin authentication failed (HTTP 401) — verify API key."
                        404 -> "Jellyfin endpoint not found (HTTP 404) — check Server URL."
                        else -> "Jellyfin HTTP ${response.code}"
                    }
                    return@use NetworkBrowseResult(
                        items = emptyList(),
                        errorMessage = readable
                    )
                }
                val json = response.body?.string().orEmpty()
                val items = parseJellyfinItemsJson(json, baseUrl, apiKey)
                NetworkBrowseResult(items = items)
            }
        }.getOrElse { err ->
            NetworkBrowseResult(items = emptyList(), errorMessage = err.message ?: "Jellyfin connection failed")
        }
    }
}
