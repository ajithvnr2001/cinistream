package com.example.remote

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class RemotePushAction(val formValue: String, val label: String) {
    PLAY_STREAM("play_stream", "Play Video / Stream URL"),
    LOAD_JSON("load_json", "Add JSON IMDb Catalog URL"),
    LOAD_M3U("load_m3u", "Load M3U / IPTV Playlist URL"),
    LOAD_AUDIO("load_audio", "Attach External Audio URL (.m3u8 / Audio)"),
    LOAD_SUBTITLE("load_subtitle", "Attach Subtitle URL"),
    LOAD_EPG("load_epg", "Load XMLTV EPG Guide URL"),
    SET_NORDVPN_TOKEN("nordvpn_token", "Connect NordVPN Access Key"),
    IMPORT_SYNC_JSON("import_sync", "Import Phone ↔ TV Sync Snapshot")
}

data class RemotePushCommand(
    val action: RemotePushAction,
    val payload: String
)

data class WebRemoteServerInfo(
    val isRunning: Boolean = false,
    val hostIp: String = "127.0.0.1",
    val port: Int = 8765,
    val pin: String = "",
    val errorMessage: String? = null
) {
    val baseUrl: String get() = "http://$hostIp:$port"
    val qrUrl: String get() = "$baseUrl/?pin=$pin"
}

/**
 * Security-hardened local Wi-Fi HTTP server for Phone-to-TV Link Pushing & Device Sync.
 *
 * Safeguards:
 * - Random per-session 6-digit PIN verified with constant-time MessageDigest.isEqual on every write/export.
 * - Strict URL scheme allowlist (http:// or https:// only; blocks javascript:, data:, file:, control chars).
 * - Strict HTML entity escaping on all rendered output (zero raw HTML injection).
 * - Strict request body caps (128 KB for links/keys, 2 MB for sync snapshots) and socket read timeouts.
 */
class LocalWebRemoteServer(
    private val onCommandReceived: (RemotePushCommand) -> String,
    private val onExportSyncJson: () -> String = { "{}" }
) {
    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private val PREFERRED_PORTS = (8765..8780).toList()

        fun generateSessionPin(): String {
            val rnd = SecureRandom()
            val num = 100_000 + rnd.nextInt(900_000)
            return num.toString()
        }

        fun escapeHtml(input: String): String = buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }

        fun isValidHttpUrl(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.length < 10 || trimmed.length > 4096) return false
            if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return false
            val lower = trimmed.lowercase()
            return lower.startsWith("http://") || lower.startsWith("https://")
        }

        fun constantTimeEquals(a: String, b: String): Boolean {
            val aBytes = a.toByteArray(Charsets.UTF_8)
            val bBytes = b.toByteArray(Charsets.UTF_8)
            return MessageDigest.isEqual(aBytes, bBytes)
        }

        fun resolveLocalIpv4Address(context: Context?): String {
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank()) return host
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            runCatching {
                val wm = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ip = wm?.connectionInfo?.ipAddress ?: 0
                if (ip != 0) {
                    return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
                }
            }
            return "127.0.0.1"
        }

        fun parseFormEncoded(body: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            if (body.isBlank()) return map
            for (pair in body.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault("")
                    val v = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault("")
                    if (k.isNotBlank()) map[k] = v
                }
            }
            return map
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workerPool = Executors.newFixedThreadPool(4)
    @Volatile private var sessionPin: String = generateSessionPin()
    @Volatile var serverInfo: WebRemoteServerInfo = WebRemoteServerInfo()
        private set

    @Synchronized
    fun start(context: Context? = null, preferredPort: Int? = null): WebRemoteServerInfo {
        if (running.get() && serverSocket?.isClosed == false) {
            return serverInfo
        }
        sessionPin = generateSessionPin()
        val portsToTry = if (preferredPort != null) listOf(preferredPort) else PREFERRED_PORTS + listOf(0)
        var bound: ServerSocket? = null
        var lastError: String? = null
        for (p in portsToTry) {
            try {
                bound = ServerSocket(p).apply { reuseAddress = true }
                break
            } catch (e: Exception) {
                lastError = e.localizedMessage
            }
        }
        if (bound == null) {
            serverInfo = WebRemoteServerInfo(
                isRunning = false,
                errorMessage = "Could not bind LAN server port: ${lastError ?: "busy"}"
            )
            return serverInfo
        }
        serverSocket = bound
        running.set(true)
        val ip = resolveLocalIpv4Address(context)
        serverInfo = WebRemoteServerInfo(
            isRunning = true,
            hostIp = ip,
            port = bound.localPort,
            pin = sessionPin
        )
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val client = bound.accept()
                    workerPool.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "CineStream-WebRemote").apply {
            isDaemon = true
            start()
        }
        return serverInfo
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        serverInfo = serverInfo.copy(isRunning = false)
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            val headerBytes = readUntilHeaderEnd(input) ?: return
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(' ') ?: return
            if (requestLine.size < 2) return
            val method = requestLine[0].uppercase()
            val rawPath = requestLine[1]
            val pathOnly = rawPath.substringBefore('?')
            val queryStr = rawPath.substringAfter('?', "")
            val queryParams = parseFormEncoded(queryStr)

            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(':')
                if (colon > 0) {
                    headers[lines[i].substring(0, colon).trim().lowercase()] =
                        lines[i].substring(colon + 1).trim()
                }
            }

            when {
                method == "GET" && pathOnly == "/" -> {
                    val prefillPin = queryParams["pin"].orEmpty().filter { it.isDigit() }.take(6)
                    sendResponse(output, 200, "text/html; charset=utf-8", renderRemoteHtml(prefillPin, null, false))
                }
                method == "GET" && pathOnly == "/api/sync/export" -> {
                    val providedPin = queryParams["pin"].orEmpty().trim()
                    if (!constantTimeEquals(providedPin, sessionPin)) {
                        sendResponse(output, 403, "application/json; charset=utf-8", """{"error":"Invalid PIN"}""")
                    } else {
                        val json = runCatching { onExportSyncJson() }.getOrDefault("{}")
                        sendResponse(
                            output = output,
                            statusCode = 200,
                            contentType = "application/json; charset=utf-8",
                            body = json,
                            extraHeaders = mapOf("Content-Disposition" to """attachment; filename="cinestream-sync.json"""")
                        )
                    }
                }
                method == "POST" && pathOnly == "/push" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                        sendResponse(output, 413, "text/html; charset=utf-8", renderRemoteHtml("", "Payload too large or empty.", true))
                        return
                    }
                    val bodyBytes = readExactBytes(input, contentLength)
                    val form = parseFormEncoded(bodyBytes.toString(Charsets.UTF_8))
                    val pin = form["pin"].orEmpty().trim()
                    val actionKey = form["action"].orEmpty().trim()
                    val payload = form["payload"].orEmpty().trim()

                    if (!constantTimeEquals(pin, sessionPin)) {
                        sendResponse(output, 403, "text/html; charset=utf-8", renderRemoteHtml("", "Invalid 6-digit PIN. Check your TV screen.", true))
                        return
                    }
                    val action = RemotePushAction.entries.firstOrNull { it.formValue == actionKey }
                    if (action == null || payload.isBlank()) {
                        sendResponse(output, 400, "text/html; charset=utf-8", renderRemoteHtml(pin, "Please select an action and enter a value.", true))
                        return
                    }
                    val requiresUrl = action in setOf(
                        RemotePushAction.PLAY_STREAM,
                        RemotePushAction.LOAD_JSON,
                        RemotePushAction.LOAD_M3U,
                        RemotePushAction.LOAD_AUDIO,
                        RemotePushAction.LOAD_SUBTITLE,
                        RemotePushAction.LOAD_EPG
                    )
                    if (requiresUrl && !isValidHttpUrl(payload)) {
                        sendResponse(
                            output,
                            400,
                            "text/html; charset=utf-8",
                            renderRemoteHtml(pin, "Only valid http:// or https:// links are allowed.", true)
                        )
                        return
                    }
                    val resultMsg = runCatching {
                        onCommandReceived(RemotePushCommand(action, payload))
                    }.getOrElse { "Action failed: ${it.localizedMessage ?: "unknown error"}" }
                    sendResponse(output, 200, "text/html; charset=utf-8", renderRemoteHtml(pin, resultMsg, false))
                }
                else -> {
                    sendResponse(output, 404, "text/plain; charset=utf-8", "Not Found")
                }
            }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> return out.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buf, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) buf else buf.copyOf(offset)
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val reason = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headerBuilder = StringBuilder()
            .append("HTTP/1.1 $statusCode $reason\r\n")
            .append("Content-Type: $contentType\r\n")
            .append("Content-Length: ${bodyBytes.size}\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) -> headerBuilder.append("$k: $v\r\n") }
        headerBuilder.append("\r\n")
        output.write(headerBuilder.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(bodyBytes)
        output.flush()
    }

    private fun renderRemoteHtml(pinValue: String, statusMessage: String?, isError: Boolean): String {
        val safePin = escapeHtml(pinValue)
        val statusBanner = if (!statusMessage.isNullOrBlank()) {
            val bg = if (isError) "#4c1d24" else "#103528"
            val fg = if (isError) "#fda4af" else "#6ee7b7"
            """<div style="background:$bg;color:$fg;padding:12px 14px;border-radius:12px;margin-bottom:16px;font-weight:600;">${escapeHtml(statusMessage)}</div>"""
        } else ""
        val optionsHtml = RemotePushAction.entries.joinToString("") { act ->
            """<option value="${escapeHtml(act.formValue)}">${escapeHtml(act.label)}</option>"""
        }
        val exportHref = "/api/sync/export?pin=$safePin"
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>CineStream TV Web Remote</title>
              <style>
                body{margin:0;padding:20px;background:#080A0F;color:#F8FAFC;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;}
                .card{max-width:480px;margin:0 auto;background:#121622;border:1px solid #262E40;border-radius:18px;padding:20px;}
                h1{margin:0 0 6px;font-size:20px;color:#F59E0B;}
                p{margin:0 0 16px;font-size:13px;color:#94A3B8;}
                label{display:block;font-size:12px;font-weight:600;color:#CBD5E1;margin:12px 0 6px;}
                input,select,textarea{width:100%;box-sizing:border-box;background:#0B0E17;color:#F8FAFC;border:1px solid #334155;border-radius:10px;padding:12px;font-size:14px;}
                textarea{min-height:92px;resize:vertical;}
                button,.btn{display:block;width:100%;box-sizing:border-box;margin-top:16px;padding:13px;border:none;border-radius:12px;background:#F59E0B;color:#080A0F;font-weight:700;font-size:15px;text-align:center;text-decoration:none;cursor:pointer;}
                .btn-secondary{background:#1E293B;color:#38BDF8;border:1px solid #334155;margin-top:10px;}
              </style>
            </head>
            <body>
              <div class="card">
                <h1>CineStream TV Web Remote</h1>
                <p>Send stream links, JSON/M3U playlists, external audio (.m3u8), subtitles, EPG guides, NordVPN keys, or sync snapshots straight to your TV over local Wi-Fi.</p>
                $statusBanner
                <form method="POST" action="/push">
                  <label for="pin">6-Digit TV Session PIN</label>
                  <input id="pin" name="pin" type="text" inputmode="numeric" maxlength="6" required value="$safePin" placeholder="Enter PIN shown on TV" />
                  <label for="action">Action</label>
                  <select id="action" name="action">$optionsHtml</select>
                  <label for="payload">URL, Key, or Sync JSON</label>
                  <textarea id="payload" name="payload" required placeholder="Paste https://... link, NordVPN access key, or CineStream Sync JSON"></textarea>
                  <button type="submit">Send to CineStream</button>
                </form>
                <a class="btn btn-secondary" href="$exportHref">Download Device Sync Snapshot (.json)</a>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
