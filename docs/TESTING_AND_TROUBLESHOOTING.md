# Testing & Troubleshooting Playbook

This document covers CineStream's automated JVM & Robolectric test suite, how to interpret the **Stats for Nerds** overlay, and concrete troubleshooting steps for common streaming, codec, and Android TV issues.

---

## 1. Automated Test Suite

CineStream uses **JUnit 4** + **Robolectric (`4.16.1`)** + **Jetpack Compose UI Test (`createComposeRule`)** so both pure engine logic and Compose UI / TV focus flows verify rapidly on the JVM without requiring an attached physical emulator.

### Running Tests
```bash
# Run the complete unit + Robolectric UI test suite
gradle :app:testDebugUnitTest

# Run a single targeted test class (example: Decoder & FFmpeg fallback tests)
gradle :app:testDebugUnitTest --tests "com.example.DecoderFallbackTest"
```

### Test Coverage Matrix (`app/src/test/java/com/example/`)

| Test File | What It Verifies |
| :--- | :--- |
| `DecoderFallbackTest.kt` | Qualcomm `c2.qti.hevc.decoder` detection, `.low_latency` exclusion (`c2.qti.hevc.decoder.low_latency`), `SmartMediaCodecSelector` zero-recreation delegation to FFmpeg 10-bit, H.264 / lower-res track fallback picker, `PlayerErrorCard` `Try Software Decoder` & `Skip Bad Segment (+5s)` actions, `TracksSheet` decoder selector, and `PlayerStatsCard` active decoder row. |
| `AudioBoostAndOptimizationTest.kt` | `AudioBoostMath` 0%–200% (+20 dB) gain curve, `computeSafeTargetBufferBytes()` heap-cap clamping (80 MB / 384 MB / 512 MB), `PlayerNetworkStats` formatting, and `TracksSheet` Stats for Nerds toggle. |
| `JsonPlaylistAndImdbTest.kt` | Unified Movie + Multi-Season Series JSON parsing, flat `episodes` grouping, custom HTTP header inheritance, `CdnMirrorResolver` path-preserving host swaps & cache eligibility rules, `ImdbMetadataClient` `tt*` ID extraction, and `SeriesDetailSheet` UI. |
| `CinemaSeekBarRobolectricTest.kt` | Buffered seekbar rendering, touch drag scrubbing, and TV focus state. |
| `SeekBarScrubEngineTest.kt` | Precision scrub step calculation and progressive D-pad hold acceleration. |
| `FocusHighlightRobolectricTest.kt` | Android TV `tvFocusHighlight` focus state and border rendering. |
| `HlsAndAppCheckTest.kt` | HLS / M3U8 / DASH / MKV format detection (`MediaFormatDetector`) and MIME resolution. |
| `LocalFilePickerTest.kt` | Zero-permission SAF local file & subtitle picker URI handling. |
| `ExampleRobolectricTest.kt` / `ExampleUnitTest.kt` | Baseline application & Compose environment sanity checks. |

---

## 2. Reading "Stats for Nerds" Telemetry

Enable **Stats for Nerds** from **Tracks → Stats for Nerds** during playback to inspect real-time diagnostics (zero CPU/GC overhead when disabled):

| Metric | What It Shows & Healthy Ranges |
| :--- | :--- |
| **Stream / Host** | Active origin domain + container badge (`HLS`, `M3U8`, `MKV`, `MP4`, `LOCAL`) and playback state (`PLAYING`, `BUFFERING`, `PAUSED`). |
| **CDN / Cache** | Active mirror node (e.g., `Node 1/3`, failover count) and on-device disk edge cache usage (`used / 64 MB` on TV or `used / 128 MB` on mobile). |
| **Decoder** | Active video decoder engine: `HW · c2.qti.hevc.decoder` (GPU hardware), `SW · FFmpeg (libffmpeg)` (native 10-bit FFmpeg CPU), or `SW · c2.android.*` (Android built-in SW). |
| **Connection Speed** | Estimated downstream bandwidth from `DefaultBandwidthMeter`. Should exceed the video bitrate by ≥1.3× for stall-free playback. |
| **Network Activity (Sparkline)** | Real-time KB/s or MB/s download bursts over the last 24 seconds. Spiky bursts followed by flat 0 KB/s are **normal** once the buffer reaches its cap. |
| **Buffer Health (Sparkline)** | Seconds of playable media buffered ahead of the playhead. Healthy range: **5s–20s** (4K 35+ Mbps streams naturally sit around **4s–7s** due to the 32 MB heap safety cap). |
| **Buffer Alloc / Cap** | ExoPlayer Java-heap allocator usage vs. `targetBufferBytes` cap (12–32 MB). Guarantees the player never triggers an OOM. |
| **JVM Heap** | Live ART heap usage vs. max heap (e.g., `48 MB / 384 MB`). |
| **Rebuffers / Dropped** | Total mid-stream stall count, cumulative stall time, and dropped video frames. High dropped frames under `SW · FFmpeg` on 4K indicates CPU saturation — switch to a lower quality or HW mode. |
| **Video / Audio** | Active resolution, FPS, video codec + bitrate, audio codec + channel layout (`Stereo` / `5.1`) + sample rate. |

---

## 3. Troubleshooting Playbook

### Issue 1: `Decoder failed: c2.qti.hevc.decoder` or `c2.qti.hevc.decoder.low_latency` on 4K 10-bit HDR10
- **Cause:** Mid-range Qualcomm Snapdragon hardware decoders can reject 4K 10-bit High-Tier HEVC (`hvc1.2.4.H150.B0`, 35–40 Mbps HDR10), and Android's built-in `c2.android.hevc.decoder` only supports 8-bit Main profile.
- **How CineStream v1.14 Fixes It:**
  1. `.low_latency` WebRTC codecs are automatically excluded.
  2. In **Auto (HW + FFmpeg SW)** mode, CineStream automatically fails over to **NextLib native FFmpeg (`FfmpegVideoRenderer`)**, which decodes **10-bit HEVC Main 10** natively.
  3. You can also open **Tracks → Video Decoder Engine → Software (FFmpeg SW)** directly.
- **Performance Tip:** On 37+ Mbps 4K 10-bit files decoded purely on CPU, if you notice dropped frames or phone warmth, select a **1080p** or **H.264** variant in **Tracks → Video Quality** if the stream provides one.

### Issue 2: Playback Stops at One Specific Timestamp on an HLS Stream
- **Cause:** A single corrupted `.ts` / `.m4s` segment or malformed GOP in the source stream, or a previously truncated chunk in the disk cache.
- **Fix:**
  - CineStream automatically bypasses the local disk edge cache on the first decode error and fetches fresh bytes from origin.
  - If the upstream segment itself is corrupted, tap **`Skip Bad Segment (+5s)`** on the **Video Decoder Error** dialog to jump cleanly past the bad GOP.

### Issue 3: HTTP 403 Forbidden / 401 Unauthorized on Private S3 / Cloudflare Worker Streams
- **Cause:** The origin server requires `Referer`, `Origin`, `User-Agent`, or `Authorization` headers.
- **Fix:** Add a `"headers": { ... }` object at the playlist, series, season, or item level in your JSON playlist (see [JSON Playlists & CDN Guide](PLAYLIST_AND_CDN_GUIDE.md)).

### Issue 4: No Audio on MKV Movies (DTS / AC-3 / E-AC-3 / TrueHD)
- **Fix:** CineStream v1.14 bundles NextLib's native **`FfmpegAudioRenderer`** via `NextRenderersFactory` (`EXTENSION_RENDERER_MODE_ON`), so AC-3, E-AC-3, DTS, and TrueHD tracks decode automatically on both phones and Android TVs without any extra configuration. If dialogue is still quiet, swipe up on the right side of the screen (or use the Volume button) to boost audio up to **200% (+20 dB)**.

### Issue 5: Stuttering on a 32-Bit Android 9 TV
- **Checklist:**
  1. Open **Tracks → Video Decoder Engine** and verify it is set to **Auto (HW + FFmpeg SW)** (do not leave **Software (FFmpeg SW)** forced permanently on a low-power TV CPU).
  2. Open **Stats for Nerds** and check **Connection Speed** vs **Video Bitrate** and **Dropped Frames**.
  3. If the stream is 4K 30+ Mbps on an older TV, pick **1080p** or **720p** in **Tracks → Video Quality**.

### Issue 6: Stream URL Doesn't End in `.m3u8` and Fails to Parse
- **Fix:** Tap **`Try as HLS / M3U8 Stream`** on the error dialog (or set `"format": "HLS"` in your JSON playlist) so CineStream explicitly routes the URL through `HlsMediaSource.Factory` with tolerant TS flags.
