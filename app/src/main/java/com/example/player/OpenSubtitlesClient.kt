package com.example.player

import android.content.Context
import android.net.Uri
import com.example.BuildConfig
import com.example.model.OnlineSubtitleCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

sealed class OnlineSubtitleSearchResult {
    data class Success(val candidates: List<OnlineSubtitleCandidate>) : OnlineSubtitleSearchResult()
    data class MissingApiKey(val message: String) : OnlineSubtitleSearchResult()
    data class Error(val message: String) : OnlineSubtitleSearchResult()
}

sealed class OpenSubtitlesLoginResult {
    data class Success(
        val username: String,
        val allowedDownloads: Int,
        val isVip: Boolean
    ) : OpenSubtitlesLoginResult()
    data class Error(val message: String) : OpenSubtitlesLoginResult()
}

data class DownloadedSubtitleFile(
    val uri: Uri,
    val remainingDownloads: Int? = null
)

/**
 * Real OpenSubtitles REST API v1 client using BuildConfig.OPENSUBTITLES_API_KEY (injected via
 * AI Studio Secrets / .env.example — never hardcoded), with optional OpenSubtitles account login
 * (`/api/v1/login` JWT Bearer token) to unlock full 20/day free or 1000/day VIP download quotas.
 */
class OpenSubtitlesClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKeyProvider: () -> String = { configuredApiKey() }
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1"
        const val APP_USER_AGENT = "CineStream v1.25"

        fun configuredApiKey(): String {
            val raw = BuildConfig.OPENSUBTITLES_API_KEY.trim()
            return if (raw.isBlank() || raw.equals("YOUR_OPENSUBTITLES_API_KEY", ignoreCase = true)) {
                ""
            } else {
                raw
            }
        }

        fun extractApiErrorMessage(responseCode: Int, rawBody: String?): String {
            val parsedMessage = runCatching {
                val json = JSONObject(rawBody.orEmpty())
                json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optJSONArray("errors")?.optString(0)?.takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
            }.getOrNull()
            return if (!parsedMessage.isNullOrBlank()) {
                "OpenSubtitles (HTTP $responseCode): $parsedMessage"
            } else {
                when (responseCode) {
                    401 -> "OpenSubtitles requires account sign-in or valid credentials (HTTP 401)."
                    403 -> "OpenSubtitles rejected the API key (HTTP 403). Verify OPENSUBTITLES_API_KEY in AI Studio Secrets."
                    406 -> "OpenSubtitles daily download quota reached or sign-in required (HTTP 406)."
                    429 -> "OpenSubtitles rate limit exceeded (HTTP 429). Please wait a moment."
                    else -> "OpenSubtitles returned HTTP $responseCode."
                }
            }
        }

        fun parseSearchResponse(rawJson: String): List<OnlineSubtitleCandidate> {
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
            val data = root.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val attrs = item.optJSONObject("attributes") ?: continue
                    val files = attrs.optJSONArray("files") ?: continue
                    val firstFile = files.optJSONObject(0) ?: continue
                    val fileId = firstFile.optLong("file_id", 0L)
                    if (fileId <= 0L) continue
                    val fileName = firstFile.optString("file_name", "").ifBlank {
                        attrs.optString("release", "subtitle_$fileId.srt")
                    }
                    add(
                        OnlineSubtitleCandidate(
                            fileId = fileId,
                            fileName = fileName,
                            language = attrs.optString("language", "en").uppercase(),
                            releaseName = attrs.optString("release", fileName),
                            downloadCount = attrs.optInt("download_count", 0),
                            isHearingImpaired = attrs.optBoolean("hearing_impaired", false)
                        )
                    )
                }
            }.sortedByDescending { it.downloadCount }
        }
    }

    @Volatile private var authToken: String? = null
    @Volatile private var activeBaseUrl: String = baseUrl.trimEnd('/')
    @Volatile var loggedInUsername: String? = null
        private set
    @Volatile var lastRemainingDownloads: Int? = null
        private set

    val hasApiKey: Boolean get() = apiKeyProvider().trim().isNotBlank()
    val isLoggedIn: Boolean get() = !authToken.isNullOrBlank()

    fun logout() {
        authToken = null
        loggedInUsername = null
        lastRemainingDownloads = null
        activeBaseUrl = baseUrl.trimEnd('/')
    }

    /**
     * Authenticates an OpenSubtitles.com user account via `POST /api/v1/login` to obtain a JWT
     * Bearer token and assigned VIP/standard `base_url` for authenticated subtitle downloads.
     */
    suspend fun login(username: String, password: String): OpenSubtitlesLoginResult =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isBlank()) {
                return@withContext OpenSubtitlesLoginResult.Error(
                    "OPENSUBTITLES_API_KEY is missing. Add it in AI Studio Secrets first."
                )
            }
            val cleanUser = username.trim()
            if (cleanUser.isBlank() || password.isBlank()) {
                return@withContext OpenSubtitlesLoginResult.Error("Enter your OpenSubtitles username and password.")
            }
            runCatching {
                val payload = JSONObject()
                    .put("username", cleanUser)
                    .put("password", password)
                    .toString()
                val reqBody = payload.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/login")
                    .header("Api-Key", apiKey)
                    .header("User-Agent", APP_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .post(reqBody)
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext OpenSubtitlesLoginResult.Error(
                            extractApiErrorMessage(resp.code, raw)
                        )
                    }
                    val json = JSONObject(raw)
                    val token = json.optString("token", "").trim()
                    if (token.isBlank()) {
                        return@withContext OpenSubtitlesLoginResult.Error("OpenSubtitles login did not return a token.")
                    }
                    val returnedBaseHost = json.optString("base_url", "").trim()
                    // Only switch to an official opensubtitles.com host if using default production baseUrl
                    if (baseUrl == DEFAULT_BASE_URL && returnedBaseHost.isNotBlank() &&
                        returnedBaseHost.endsWith("opensubtitles.com", ignoreCase = true)
                    ) {
                        val normalizedHost = returnedBaseHost.removePrefix("https://").removePrefix("http://").trimEnd('/')
                        activeBaseUrl = "https://$normalizedHost/api/v1"
                    } else {
                        activeBaseUrl = baseUrl.trimEnd('/')
                    }
                    val userObj = json.optJSONObject("user")
                    val allowed = userObj?.optInt("allowed_downloads", 20) ?: 20
                    val isVip = userObj?.optBoolean("vip", false) ?: false
                    authToken = token
                    loggedInUsername = cleanUser
                    lastRemainingDownloads = allowed
                    OpenSubtitlesLoginResult.Success(
                        username = cleanUser,
                        allowedDownloads = allowed,
                        isVip = isVip
                    )
                }
            }.getOrElse { err ->
                OpenSubtitlesLoginResult.Error(err.localizedMessage ?: "OpenSubtitles sign-in failed.")
            }
        }

    suspend fun searchSubtitles(
        query: String,
        imdbId: String? = null,
        languageCode: String = "en"
    ): OnlineSubtitleSearchResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            return@withContext OnlineSubtitleSearchResult.MissingApiKey(
                "Add OPENSUBTITLES_API_KEY in the AI Studio Secrets panel and rebuild to enable OpenSubtitles search."
            )
        }
        val cleanQuery = query.trim().lowercase()
        val numericImdb = imdbId?.trim()?.removePrefix("tt")?.filter { it.isDigit() }
        if (cleanQuery.isBlank() && numericImdb.isNullOrBlank()) {
            return@withContext OnlineSubtitleSearchResult.Error("Enter a movie or episode title to search.")
        }

        runCatching {
            // Keep query parameters alphabetically sorted & lowercase to avoid OpenSubtitles 301 redirects
            val urlBuilder = "${activeBaseUrl.trimEnd('/')}/subtitles".toHttpUrl().newBuilder()
            if (!numericImdb.isNullOrBlank()) {
                urlBuilder.addQueryParameter("imdb_id", numericImdb)
            }
            val cleanLang = languageCode.trim().lowercase().takeIf { it.isNotBlank() && it != "all" }
            if (cleanLang != null) {
                urlBuilder.addQueryParameter("languages", cleanLang)
            }
            if (cleanQuery.isNotBlank()) {
                urlBuilder.addQueryParameter("query", cleanQuery)
            }

            val reqBuilder = Request.Builder()
                .url(urlBuilder.build())
                .header("Api-Key", apiKey)
                .header("User-Agent", APP_USER_AGENT)
                .header("Accept", "*/*")
            authToken?.takeIf { it.isNotBlank() }?.let { token ->
                reqBuilder.header("Authorization", "Bearer $token")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext OnlineSubtitleSearchResult.Error(
                        extractApiErrorMessage(resp.code, body)
                    )
                }
                OnlineSubtitleSearchResult.Success(parseSearchResponse(body))
            }
        }.getOrElse { err ->
            OnlineSubtitleSearchResult.Error(err.localizedMessage ?: "Subtitle search failed.")
        }
    }

    /**
     * Requests a temporary direct download link from OpenSubtitles REST API v1 and saves the
     * caption file into app-private cache so it can be attached as Primary or Secondary subtitle.
     */
    suspend fun downloadSubtitleToCache(candidate: OnlineSubtitleCandidate): Result<DownloadedSubtitleFile> =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("OPENSUBTITLES_API_KEY is not configured."))
            }
            runCatching {
                val payload = JSONObject().put("file_id", candidate.fileId).toString()
                val reqBody = payload.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaType())
                val reqBuilder = Request.Builder()
                    .url("${activeBaseUrl.trimEnd('/')}/download")
                    .header("Api-Key", apiKey)
                    .header("User-Agent", APP_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .post(reqBody)
                authToken?.takeIf { it.isNotBlank() }?.let { token ->
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                var remaining: Int? = null
                val downloadLink = okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        error(extractApiErrorMessage(resp.code, raw))
                    }
                    val json = JSONObject(raw)
                    if (json.has("remaining") && !json.isNull("remaining")) {
                        remaining = json.optInt("remaining")
                        lastRemainingDownloads = remaining
                    }
                    json.optString("link", "").takeIf {
                        it.startsWith("http://", true) || it.startsWith("https://", true)
                    } ?: error(json.optString("message").ifBlank { "Missing subtitle download URL" })
                }

                val content = okHttpClient.newCall(
                    Request.Builder().url(downloadLink).header("User-Agent", APP_USER_AGENT).build()
                ).execute().use { dlResp ->
                    if (!dlResp.isSuccessful) error("Failed fetching subtitle file (HTTP ${dlResp.code})")
                    dlResp.body?.bytes() ?: error("Empty subtitle body")
                }

                val dir = File(context.cacheDir, "online_subtitles").apply { mkdirs() }
                val safeName = candidate.fileName.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
                    .let { if (it.endsWith(".srt", true) || it.endsWith(".vtt", true)) it else "$it.srt" }
                val outFile = File(dir, "${candidate.fileId}_$safeName")
                outFile.writeBytes(content)
                DownloadedSubtitleFile(uri = Uri.fromFile(outFile), remainingDownloads = remaining)
            }
        }
}
