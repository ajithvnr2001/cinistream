# Testing & Troubleshooting Playbook (v1.25)

This document covers CineStream v1.25's **90-test automated JVM & Robolectric test suite (14 suites)**, how to read every indicator in **Stats for Nerds**, and actionable troubleshooting playbooks.

---

## 1. Automated Test Suite (90 Tests Across 14 Suites)

CineStream uses **JUnit 4**, **Robolectric (`4.16.1`)**, and **Jetpack Compose UI Test (`createComposeRule`)** so pure engines, HTTP/API flows, Room/DataStore logic, and Compose UI/TV focus interactions verify rapidly on the JVM without an emulator.

### Running Tests
```bash
# Run all 90 unit & Robolectric UI tests
gradle :app:testDebugUnitTest

# Run v1.24/v1.25 Ecosystem & OpenSubtitles JWT Auth tests (17 tests)
gradle :app:testDebugUnitTest --tests "com.example.CineStreamV24FeaturesTest"

# Run v1.23 Frame-Drop, Continue Watching, Audio Delay & Auto-VPN tests (11 tests)
gradle :app:testDebugUnitTest --tests "com.example.CineStreamV23FeaturesTest"

# Run Private DNS, NordVPN WireGuard & CredentialVault tests (14 tests)
gradle :app:testDebugUnitTest --tests "com.example.PrivateDnsAndNordVpnTest"
```

### Complete Test Coverage Matrix (`app/src/test/java/com/example/`)

| Test Suite | Tests | Coverage |
| :--- | :---: | :--- |
| `CineStreamV24FeaturesTest.kt` | 17 | `QrCodeMatrixGenerator`, `LocalWebRemoteServer` (PIN auth, push, `/api/sync`), `DeviceSyncCodec` (credential exclusion), `SleepTimer`, `SecondarySubtitleEngine`, `XmltvEpgParser` (`.xml` + `.xml.gz`), `OpenSubtitlesClient` (`POST /api/v1/login` JWT Bearer auth + search + download + remaining quota + error parsing), `OfflineDownloadManager`, `LocalNetworkClient` (WebDAV/Jellyfin), `GlobalSearchEngine`, and Compose UI tests for all v1.24/v1.25 overlays & sheets. |
| `PrivateDnsAndNordVpnTest.kt` | 14 | `DohDnsClient` (`1.1.1.1`/`8.8.8.8`), `FastDnsResolver` DoH routing, `CredentialVault` Keystore encryption, `NordVpnApiClient` key exchange + 118+ country/city search, `NordLynxEngine` WireGuard config builder, `NetworkPrivacySheet` UI & compact 360dp zero-overlap layout. |
| `CineStreamV23FeaturesTest.kt` | 11 | `FrameDropAnalyzer` (Healthy / Seek Burst / Stream PTS / SW CPU Load), `MediaCodecQueueingPolicy`, `ContinueWatchingHelper`, `DelayAudioProcessor` PCM ring buffer, `SmartVpnFailoverCoordinator`, and `ContinueWatchingRow` UI. |
| `AudioBoostAndOptimizationTest.kt` | 11 | `AudioBoostMath` (0–200% / +20 dB), heap-capped `LoadControl`, `largeHeap`, HTTP/2 pool, `FastDnsResolver`, `FastStreamingLoadErrorHandlingPolicy`, `MediaEdgeCache` TV RAM-Direct, `ParallelPrefetchEngine` (HLS live handoff, audio lane, byte-range), `QuicThroughputWatchdog`, and `TracksSheet` UI. |
| `JsonPlaylistAndImdbTest.kt` | 11 | Unified Movie + Series JSON parsing, flat episodes, header inheritance, `CdnMirrorResolver` swaps & cache rules, `ImdbMetadataClient`, `SeriesDetailSheet`, `JsonPlaylistSheet`, and `TracksSheet` CDN controls. |
| `DecoderFallbackTest.kt` | 6 | Qualcomm HEVC detection, `.low_latency` exclusion, `SmartMediaCodecSelector` FFmpeg delegation, H.264/lower-res track fallback, `PlayerErrorCard` actions, and `PlayerStatsCard` decoder telemetry. |
| `LocalFilePickerTest.kt` | 4 | Zero-permission SAF local video, M3U, JSON, and subtitle picker URI handling. |
| `SeekBarScrubEngineTest.kt` | 4 | Precision scrub step calculation and progressive D-pad hold acceleration (`±1m` to `±10m`). |
| `HlsAndAppCheckTest.kt` | 3 | `MediaFormatDetector` format/MIME detection and `PlayerStatsCard` rendering & close action. |
| `MultiJsonPlaylistTest.kt` | 3 | `MultiJsonPlaylistReducer` multi-URL aggregation, ID namespacing, `"All"` vs single-list filtering, and removal. |
| `CinemaSeekBarRobolectricTest.kt` | 2 | Buffered seekbar rendering, touch drag scrubbing, and TV focus state. |
| `FocusHighlightRobolectricTest.kt` | 2 | Android TV `tvFocusHighlight` focus ring and scale behavior. |
| `ExampleRobolectricTest.kt` | 1 | App name resource verification (`CineStream`). |
| `ExampleUnitTest.kt` | 1 | Baseline JVM sanity check. |
| **Total** | **90** | **100% passing on local JVM (`gradle :app:testDebugUnitTest`).** |

---

## 2. Reading "Stats for Nerds" Telemetry

Toggle **Stats for Nerds** from **Tracks → Stats for Nerds** during playback (0% overhead when hidden):

| Metric | What It Shows & Healthy Ranges |
| :--- | :--- |
| **Header Pills** | State (`PLAYING`/`BUFFERING`/`PAUSED`), transport (`HTTP/3` emerald Cronet QUIC vs `HTTP/2` blue OkHttp), active Private DNS (`1.1.1.1`/`8.8.8.8`), and active VPN (`NORD` WireGuard / `VPN` system). |
| **Stream** | Format (`HLS`, `M3U8`, `DASH`, `MKV`, `MP4`, `LOCAL`) and active origin host. |
| **Conn Speed** | Live `DefaultBandwidthMeter` estimate (should be ≥1.3× video bitrate). |
| **Net Activity** | 24-second live throughput sparkline + session bytes downloaded. Spikes followed by `0 KB/s` are normal once the 40 MB / 45s buffer is full. |
| **Buffer Health** | Seconds buffered ahead + stall count. Healthy: **15s–37s** on 1080p (**7s–9s** on 35+ Mbps 4K due to the 40 MB OOM cap). |
| **Buffer / Heap** | ExoPlayer allocator usage vs `targetBufferBytes` cap (12–40 MB) + live JVM heap vs max heap. |
| **CDN / Cache** | Active mirror (`Node 1/3`), **`RAM Direct`** (TV) or `Edge used / 128 MB` (Mobile), and **`2×`/`3× Prefetch (hit%)`**. |
| **Video / Codecs** | Resolution, FPS, video bitrate, and audio codec/channels/sample rate (`Stereo` preferred on TV). |
| **Decoder** | Active engine (`HW · c2.*` / `SW · FFmpeg`) + queueing mode (`Async` / `Sync`). |
| **Dropped** | Total drops + rolling `drops/sec` + diagnosis: **Healthy**, **Seek Burst**, **Stream PTS / Re-Sync** (URL-side), or **SW CPU Load** (player-side). |

---

## 3. Troubleshooting Playbook

### 1. Dropped Frames Showing in Stats for Nerds
- **If badge says `Seek Burst`:** Normal transient drops during seek/start—no action needed.
- **If badge says `SW CPU Load` (Red):** Software FFmpeg is saturating CPU (common on 4K 10-bit HEVC). Open **Tracks → Video Decoder** and try **Hardware (HW)** or select a **1080p / H.264** quality track.
- **If badge says `Stream PTS / Re-Sync` (Amber):** Hardware decoding is fine, but the stream URL/origin has uneven PTS timestamps or bad segment splices. Try switching **CDN Mirror** or using **Skip Segment (+5s)** if it occurs at a single point.

### 2. Slow Speed or Stalling on ISP Wi-Fi (QUIC UDP-443 Throttling)
- `QuicThroughputWatchdog` automatically demotes throttled HTTP/3 QUIC to IPv4-first HTTP/2 TCP after repeated slow segments or stalls.
- You can also force **HTTP/2** permanently in **Tracks → Network Transport → HTTP/2**, enable **Cloudflare 1.1.1.1 DoH**, or enable **Auto-VPN Failover** in **DNS & VPN**.

### 3. OpenSubtitles Search Works, But Downloads Return HTTP 401 / 406
- **Cause:** Free OpenSubtitles API consumers (`Package: -`) require user JWT login (`POST /api/v1/login`) for reliable downloads.
- **Fix:**
  1. Ensure **`OPENSUBTITLES_API_KEY`** is saved in the **Secrets panel in AI Studio** and build via AI Studio preview or **Settings → Generate APK**.
  2. Inside **Player → CC (Subtitles) → OpenSubtitles Search**, tap **Sign In** and enter your OpenSubtitles username & password once (stored encrypted via Android Keystore) to unlock **20/day free** (or **1,000/day VIP**) downloads with live quota tracking.

### 4. Bluetooth / Soundbar Audio Out of Sync
- Open **Tracks → Audio Delay** and tap `-25ms` / `-50ms` / `+25ms` / `+50ms` (range `-500ms` to `+500ms`). `DelayAudioProcessor` applies the lip-sync shift instantaneously with zero video reload and remembers it per video.

### 5. `c2.qti.hevc.decoder` Crash on 4K 10-Bit HDR10
- CineStream automatically excludes `.low_latency` codecs and fails over to **NextLib native FFmpeg (`FfmpegVideoRenderer`)** with a clean `surfaceResetToken` surface reset. You can also choose **Tracks → Video Decoder → Software (FFmpeg)** directly.

### 6. Playback Freezes at One Specific Timestamp in HLS
- CineStream bypasses the disk cache automatically on the first decode error. If the upstream `.ts`/`.m4s` segment itself is corrupt, tap **`Skip Segment (+5s)`** on the error card to jump cleanly past the bad GOP.

### 7. Phone-to-TV Web Remote Cannot Connect
- Confirm both phone and Android TV are on the same local Wi-Fi subnet (disable AP/client isolation on guest routers), scan the QR code or type the displayed `http://<lan-ip>:<port>` URL, and enter the current 6-digit session PIN.
