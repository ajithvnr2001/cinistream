package com.example.player

import android.util.Base64
import com.example.data.NetworkServerEntity
import com.example.model.StreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

data class NetworkBrowserItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val pathOrParentId: String,
    val playbackUrl: String = "",
    val format: StreamFormat = StreamFormat.AUTO,
    val sizeBytes: Long = 0L,
    val posterUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap()
)

data class NetworkBrowseResult(
    val items: List<NetworkBrowserItem>,
    val errorMessage: String? = null
)

/**
 * Real LAN / Home NAS Media Server Client supporting:
 * 1. WebDAV (`PROPFIND` Depth: 1 XML directory listing + HTTP Basic Auth)
 * 2. Jellyfin REST API (API Key auth + library/folder browsing + direct static stream playback)
 *
 * Credentials are stored encrypted in Room via [CredentialVault] (Android Keystore AES-256-GCM).
 */
class LocalNetworkClient(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        const val TYPE_WEBDAV = "WEBDAV"
        const val TYPE_JELLYFIN = "JELLYFIN"

        private val PLAYABLE_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "m3u8", "ts", "mov", "avi", "m4v", "mpd", "flv", "3gp", "mp3", "aac", "flac"
        )

        fun normalizeBaseUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return withScheme.trimEnd('/')
        }

        fun buildBasicAuthHeader(username: String, password: String): String? {
            if (username.isBlank() && password.isBlank()) return null
            val token = Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return "Basic $token"
        }

        fun parseWebDavPropfindXml(
            xml: String,
            currentDirUrl: String,
            authHeader: String?
        ): List<NetworkBrowserItem> {
            val items = mutableListOf<NetworkBrowserItem>()
            val baseUri = runCatching { URI(currentDirUrl.trimEnd('/') + "/") }.getOrNull()
            val currentPathNormalized = baseUri?.path?.trimEnd('/') ?: ""

            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inResponse = false
            var href: String? = null
            var displayName: String? = null
            var isCollection = false
            var contentLength = 0L

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        when (name) {
                            "response" -> {
                                inResponse = true
                                href = null
                                displayName = null
                                isCollection = false
                                contentLength = 0L
                            }
                            "href" -> if (inResponse) href = parser.nextText().trim()
                            "displayname" -> if (inResponse) displayName = parser.nextText().trim()
                            "collection" -> if (inResponse) isCollection = true
                            "getcontentlength" -> if (inResponse) {
                                contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("response", ignoreCase = true) && inResponse) {
                            val rawHref = href
                            if (!rawHref.isNullOrBlank()) {
                                val resolvedUrl = runCatching {
                                    if (rawHref.startsWith("http://", true) || rawHref.startsWith("https://", true)) {
                                        rawHref
                                    } else {
                                        baseUri?.resolve(rawHref)?.toString() ?: rawHref
                                    }
                                }.getOrDefault(rawHref)
                                val entryPath = runCatching { URI(resolvedUrl).path?.trimEnd('/') ?: "" }.getOrDefault("")
                                val isSelf = entryPath == currentPathNormalized ||
                                    resolvedUrl.trimEnd('/') == currentDirUrl.trimEnd('/')
                                if (!isSelf) {
                                    val fallbackName = runCatching {
                                        URLDecoder.decode(entryPath.substringAfterLast('/'), "UTF-8")
                                    }.getOrDefault(entryPath.substringAfterLast('/'))
                                    val finalName = displayName?.takeIf { it.isNotBlank() } ?: fallbackName
                                    val ext = finalName.substringAfterLast('.', "").lowercase()
                                    if (isCollection || ext in PLAYABLE_EXTENSIONS) {
                                        val headers = if (!authHeader.isNullOrBlank()) {
                                            mapOf("Authorization" to authHeader)
                                        } else {
                                            emptyMap()
                                        }
                                        items.add(
                                            NetworkBrowserItem(
                                                id = resolvedUrl,
                                                name = finalName.ifBlank { "Item" },
                                                isFolder = isCollection,
                                                pathOrParentId = resolvedUrl,
                                                playbackUrl = if (isCollection) "" else resolvedUrl,
                                                format = if (isCollection) StreamFormat.AUTO else MediaFormatDetector.detectFormat(finalName),
                                                sizeBytes = contentLength,
                                                requestHeaders = headers
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
            return items.sortedWith(compareByDescending<NetworkBrowserItem> { it.isFolder }.thenBy { it.name.lowercase() })
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
            return result.sortedWith(compareByDescending<NetworkBrowserItem> { it.isFolder }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun browse(
        server: NetworkServerEntity,
        folderPathOrId: String? = null
    ): NetworkBrowseResult = withContext(Dispatchers.IO) {
        val username = CredentialVault.decrypt(server.usernameEnc)
        val secret = CredentialVault.decrypt(server.secretEnc)
        val baseUrl = normalizeBaseUrl(server.baseUrl)
        return@withContext when (server.serverType.uppercase()) {
            TYPE_JELLYFIN -> browseJellyfin(baseUrl, secret, folderPathOrId)
            else -> browseWebDav(baseUrl, username, secret, folderPathOrId ?: baseUrl)
        }
    }

    private fun browseWebDav(
        baseUrl: String,
        username: String,
        password: String,
        targetUrl: String
    ): NetworkBrowseResult {
        return runCatching {
            val urlToQuery = targetUrl.ifBlank { baseUrl }
            val authHeader = buildBasicAuthHeader(username, password)
            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:resourcetype/>
    <D:getcontentlength/>
  </D:prop>
</D:propfind>""".toRequestBody("application/xml; charset=utf-8".toMediaType())

            val reqBuilder = Request.Builder()
                .url(urlToQuery)
                .method("PROPFIND", propfindBody)
                .header("Depth", "1")
                .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
            if (!authHeader.isNullOrBlank()) {
                reqBuilder.header("Authorization", authHeader)
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 207) {
                    return@use NetworkBrowseResult(
                        items = emptyList(),
                        errorMessage = "WebDAV HTTP ${response.code}"
                    )
                }
                val xml = response.body?.string().orEmpty()
                val items = parseWebDavPropfindXml(xml, urlToQuery, authHeader)
                NetworkBrowseResult(items = items)
            }
        }.getOrElse { err ->
            NetworkBrowseResult(items = emptyList(), errorMessage = err.message ?: "WebDAV connection failed")
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
                    return@use NetworkBrowseResult(
                        items = emptyList(),
                        errorMessage = "Jellyfin HTTP ${response.code}"
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
