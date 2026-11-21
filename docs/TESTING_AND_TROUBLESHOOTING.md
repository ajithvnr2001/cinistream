# Testing & Troubleshooting Playbook

This document covers CineStream v1.16's **41-test** automated JVM & Robolectric test suite, how to interpret the **Stats for Nerds** overlay, and concrete troubleshooting steps for common streaming, network transport, codec, and Android TV issues.

---

## 1. Automated Test Suite (41 Tests)

CineStream uses **JUnit 4** + **Robolectric (`4.16.1`)** + **Jetpack Compose UI Test (`createComposeRule`)** so both pure engine logic and Compose UI / TV focus flows verify rapidly on the JVM without requiring an attached physical emulator.

### Running Tests
```bash
# Run the complete 41-test unit + Robolectric UI suite
gradle :app:testDebugUnitTest

# Run a single targeted test class (example: v1.16 Network, Buffer & Audio Optimization tests)
gradle :app:testDebugUnitTest --tests "com.example.AudioBoostAndOptimizationTest"

# Run Decoder & FFmpeg fallback tests
gradle :app:testDebugUnitTest --tests "com.example.DecoderFallbackTest"
```

### Test Coverage Matrix (`app/src/test/java/com/example/`)

| Test File | What It Verifies |
| :--- | :--- |
| `AudioBoostAndOptimizationTest.kt` | `AudioBoostMath` 0%–200% (+20 dB) gain curve, `computeSafeTargetBufferBytes()` & `computeSafeBackBufferMs()` heap-cap clamping (48 MB / 80 MB / 384 MB), `largeHeap` manifest flag, HTTP/2 `sharedOkHttpClient` protocol check, **`FastDnsResolver`** (5-min TTL caching, IPv4-first ordering, IPv6-only preservation, non-cached failures, `clear()`), **`FastStreamingLoadErrorHandlingPolicy`** (500ms / 1200ms transient retry vs instant `C.TIME_UNSET` fail-fast on HTTP 404), **`MediaEdgeCache.shouldBypassDiskCacheForDevice`** (TV RAM-Direct vs phone UFS cache), **`StreamingNetworkEngine.extractOriginRootUrl`** & **`AdaptiveHttpDataSourceFactory`** OkHttp fallback, **`computeInitialWifiBitrateEstimate`** (`6 Mbps` TV / `10 Mbps` phone), and `TracksSheet` Volume Boost & Stats toggle UI. |
| `DecoderFallbackTest.kt` | Qualcomm `c2.qti.hevc.decoder` detection, `.low_latency` exclusion (`c2.qti.hevc.decoder.low_latency`), `SmartMediaCodecSelector` zero-recreation delegation to FFmpeg 10-bit, H.264 / lower-res track fallback picker, `PlayerErrorCard` `Try Software Decoder` & `Skip Bad Segment (+5s)` actions, `TracksSheet` decoder selector, and `PlayerStatsCard` active decoder row visibility. |
| `JsonPlaylistAndImdbTest.kt` | Unified Movie + Multi-Season Series JSON parsing, flat `episodes` grouping, custom HTTP header inheritance, `CdnMirrorResolver` path-preserving host swaps & cache eligibility rules, `ImdbMetadataClient` `tt*` ID extraction, and `SeriesDetailSheet` UI. |
| `CinemaSeekBarRobolectricTest.kt` | Buffered seekbar rendering, touch drag scrubbing, and TV focus state. |
| `SeekBarScrubEngineTest.kt` | Precision scrub step calculation and progressive D-pad hold acceleration. |
| `FocusHighlightRobolectricTest.kt` | Android TV `tvFocusHighlight` focus state and border rendering. |
| `HlsAndAppCheckTest.kt` | HLS / M3U8 / DASH / MKV format detection (`MediaFormatDetector`), MIME resolution, and `PlayerStatsCard` rendering & close action. |
| `LocalFilePickerTest.kt` | Zero-permission SAF local file & subtitle picker URI handling. |
| `ExampleRobolectricTest.kt` / `ExampleUnitTest.kt` | Baseline application & Compose environment sanity checks. |

---

## 2. Reading "Stats for Nerds" Telemetry

Enable **Stats for Nerds** from **Tracks → Stats for Nerds** during playback to inspect real-time diagnostics (zero CPU/GC overhead when disabled):

| Metric | What It Shows & Healthy Ranges |
| :--- | :--- |
| **Header Pills** | Live playback state (`PLAYING`, `BUFFERING`, `PAUSED`) plus compact network stack badge: **`HTTP/3`** (emerald — Google Play Services Cronet QUIC + TLS 1.3 active) or **`HTTP/2`** (blue — shared OkHttp HTTP/2 active). |
| **Stream** | Container/protocol badge (`HLS`, `M3U8`, `DASH`, `MKV`, `MP4`, `LOCAL`) and active origin host domain. |
| **Conn Speed** | Live downstream bandwidth estimate from `DefaultBandwidthMeter`. Should exceed the video bitrate by ≥1.3× for stall-free playback. On 5 GHz Wi-Fi to Cloudflare Workers, v1.16 should comfortably show **15–60+ Mbps** instead of the old 3.95 Mbps HTTP/1.1 cap. |
| **Net Activity (Sparkline)** | Real-time KB/s or MB/s download bursts over the last 24 seconds + total session bytes. Spiky bursts followed by flat `0 KB/s` are **normal** once the buffer reaches its 40 MB / 45s cap. |
| **Buffer Health (Sparkline)** | Seconds of playable media buffered ahead of the playhead + stall count. Healthy range: **15s–37s** on 1080p streams (4K 35+ Mbps streams naturally sit around **7s–9s** due to the strict 40 MB Java-heap OOM safety cap). |
| **Buffer / Heap** | ExoPlayer Java-heap allocator usage vs. `targetBufferBytes` cap (**12–40 MB**) and live ART JVM heap usage vs. max heap (e.g., `62 MB / 384 MB`). Guarantees the player never triggers an OOM. |
| **CDN / Cache** | Active mirror node (e.g., `Node 1/3`, failover count) plus **`RAM Direct (Zero eMMC)`** on Android TV / constrained devices, or `Edge used / 128 MB` on phones/tablets. |
| **Video / Codecs** | Active resolution, FPS, video codec + bitrate, and audio codec + channel layout (`Stereo` / `5.1` / `7.1`) + sample rate. On Android TV, verify audio shows **`Stereo`** when a 2-channel track exists. |
| **Decoder** | Active video decoder engine: `HW · c2.qti.hevc.decoder` / `HW · OMX.*` (GPU hardware), `SW · FFmpeg (libffmpeg)` (native 10-bit FFmpeg CPU), or `SW · c2.android.*` (Android built-in SW). |
| **Dropped** | Cumulative dropped video frames. High dropped frames under `SW · FFmpeg` on 4K indicates CPU saturation — switch to a lower quality or HW mode. |

---

## 3. Troubleshooting Playbook

### Issue 1: Slow Throughput / Periodic Buffering on Android 9 TV over Wi-Fi (Cloudflare Workers / High RTT)
- **Cause (Prior to v1.15/v1.16):** Android 9's stock `HttpURLConnection` dropped to serial HTTP/1.1 + OpenSSL TLS 1.2 + TCP CUBIC (~3.95 Mbps cap over 125 ms RTT), while `DEFAULT=YES` 7.1 AAC forced software decoding (`50,000+` underruns) and synchronous `SimpleCache` writes competed with OS swap on slow TV eMMC flash.
- **How CineStream v1.16 Fixes It:**
  1. Open **Stats for Nerds** on your TV and verify the header pill displays **`HTTP/3`** (or **`HTTP/2`** on non-GMS TVs).
  2. Verify **CDN / Cache** displays **`RAM Direct (Zero eMMC)`**.
  3. Verify **Codecs** displays **`Stereo`** audio (hardware DSP) rather than `7.1` SW AAC.
  4. Verify **Buffer Health** climbs smoothly to **20–35s** with **0 stalls**.
- **Tip:** If your TV lacks Google Play Services or has an outdated Play Services binary, CineStream automatically runs on its **HTTP/2 OkHttp + IPv4-first FastDns** engine with zero crashes.

### Issue 2: `Decoder failed: c2.qti.hevc.decoder` or `c2.qti.hevc.decoder.low_latency` on 4K 10-bit HDR10
- **Cause:** Mid-range Qualcomm Snapdragon hardware decoders can reject 4K 10-bit High-Tier HEVC (`hvc1.2.4.H150.B0`, 35–40 Mbps HDR10), and Android's built-in `c2.android.hevc.decoder` only supports 8-bit Main profile.
- **How CineStream v1.16 Fixes It:**
  1. `.low_latency` WebRTC codecs are automatically excluded.
  2. In **Auto (HW + FFmpeg SW)** mode, CineStream automatically fails over to **NextLib native FFmpeg (`FfmpegVideoRenderer`)**, which decodes **10-bit HEVC Main 10** natively.
  3. You can also open **Tracks → Video Decoder Engine → Software (FFmpeg SW)** directly.
- **Performance Tip:** On 37+ Mbps 4K 10-bit files decoded purely on CPU, if you notice dropped frames or phone warmth, select a **1080p** or **H.264** variant in **Tracks → Video Quality** if the stream provides one.

### Issue 3: Playback Stops at One Specific Timestamp on an HLS Stream
- **Cause:** A single corrupted `.ts` / `.m4s` segment or malformed GOP in the source stream, or a previously truncated chunk in the mobile disk cache.
- **Fix:**
  - CineStream automatically bypasses the local disk edge cache on the first decode error and fetches fresh bytes from origin.
  - If the upstream segment itself is corrupted, tap **`Skip Bad Segment (+5s)`** on the **Video Decoder Error** dialog to jump cleanly past the bad GOP.

### Issue 4: HTTP 403 Forbidden / 401 Unauthorized on Private S3 / Cloudflare Worker Streams
- **Cause:** The origin server requires `Referer`, `Origin`, `User-Agent`, or `Authorization` headers.
- **Fix:** Add a `"headers": { ... }` object at the playlist, series, season, or item level in your JSON playlist (see [JSON Playlists & CDN Guide](PLAYLIST_AND_CDN_GUIDE.md)). Headers are propagated automatically to both Cronet (HTTP/3) and OkHttp (HTTP/2).

### Issue 5: No Audio on MKV Movies (DTS / AC-3 / E-AC-3 / TrueHD)
- **Fix:** CineStream v1.16 bundles NextLib's native **`FfmpegAudioRenderer`** via `NextRenderersFactory` (`EXTENSION_RENDERER_MODE_ON`), so AC-3, E-AC-3, DTS, and TrueHD tracks decode automatically on both phones and Android TVs without any extra configuration. If dialogue is still quiet, swipe up on the right side of the screen (or use the Volume button) to boost audio up to **200% (+20 dB)**.

### Issue 6: Stream URL Doesn't End in `.m3u8` and Fails to Parse
- **Fix:** Tap **`Try as HLS / M3U8 Stream`** on the error dialog (or set `"format": "HLS"` in your JSON playlist) so CineStream explicitly routes the URL through `HlsMediaSource.Factory` with tolerant TS flags and `FastStreamingLoadErrorHandlingPolicy`.
