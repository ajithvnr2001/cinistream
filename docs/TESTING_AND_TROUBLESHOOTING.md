# Testing & Troubleshooting Playbook (v1.39)

This document covers CineStream v1.39's **150-test automated JVM & Robolectric test suite (22 suites)**, how to read every indicator in **Stats for Nerds**, and actionable troubleshooting playbooks.

---

## 1. Automated Test Suite (150 Tests Across 22 Suites)

CineStream uses **JUnit 4**, **Robolectric (`4.16.1`)**, and **Jetpack Compose UI Test (`createComposeRule`)** so pure engines, HTTP/API flows, Room/DataStore logic, offline downloads, external audio pipelines, and Compose UI/TV focus interactions verify rapidly on the JVM without an emulator.

### Running Tests
```bash
# Run all 150 unit & Robolectric UI tests
gradle :app:testDebugUnitTest

# Run External Audio URLs, Unbounded ±60 min Sync, JSON Playlist Auto-Audio & Web Remote tests (10 tests)
gradle :app:testDebugUnitTest --tests "com.example.ExternalAudioUrlSyncTest"

# Run Movie Collections, Flat Grouping, Per-Movie IMDb & CollectionDetailSheet UI tests (6 tests)
gradle :app:testDebugUnitTest --tests "com.example.MovieCollectionsTest"

# Run 8× HLS / 4× Multi-Chunk Resumable Offline Downloads, External Audio Bundling (MP3/AC3/FLAC/WAV/M4A/HLS), Multi-Quality/Audio Picker & Mobile Player tests (20 tests)
gradle :app:testDebugUnitTest --tests "com.example.MobilePlayerDownloadTest"

# Run Multi-Account WebDAV, Room v4, PROPFIND Redirect/Auth & UI tests (6 tests)
gradle :app:testDebugUnitTest --tests "com.example.MultiAccountWebDavTest"

# Run HLS-segment AudioSyncMediaSource & SampleStream tests (4 tests)
gradle :app:testDebugUnitTest --tests "com.example.HlsAudioSyncSampleStreamTest"

# Run Direct ms/s/m Audio & Subtitle Sync Input UI + Math tests (5 tests)
gradle :app:testDebugUnitTest --tests "com.example.AudioSyncNumericInputTest"

# Run Mobile Vertical Brightness HUD & Android TV Brightness Control tests (5 tests)
gradle :app:testDebugUnitTest --tests "com.example.BrightnessControlTest"

# Run Media3 1.11.1 HLS external subtitle transcoding tests (6 tests)
gradle :app:testDebugUnitTest --tests "com.example.HlsSubtitleTranscodingTest"

# Run Ecosystem, Web Remote, Local Sync, EPG & Dual Subtitle tests (15 tests)
gradle :app:testDebugUnitTest --tests "com.example.CineStreamV24FeaturesTest"

# Run Frame-Drop, Continue Watching, Audio Delay & Auto-VPN tests (11 tests)
gradle :app:testDebugUnitTest --tests "com.example.CineStreamV23FeaturesTest"

# Run Private DNS, NordVPN WireGuard & CredentialVault tests (14 tests)
gradle :app:testDebugUnitTest --tests "com.example.PrivateDnsAndNordVpnTest"
```

### Complete Test Coverage Matrix (`app/src/test/java/com/example/`)

| Test Suite | Tests | Coverage |
| :--- | :---: | :--- |
| `MobilePlayerDownloadTest.kt` | 20 | `OfflineDownloadManager` 8× parallel HLS video+audio+subtitle downloads, 4× HTTP `Range` chunked progressive downloads, pause/resume checkpointing, Wi-Fi ↔ Cellular seamless handoff, HLS master variant × `#EXT-X-MEDIA` audio track inspection & picker dialog, loaded external audio offline bundling (`.mp3`, `.ac3`, `.flac`, `.wav`, `.m4a` & HLS `.m3u8` + `offline_audio.json` sidecar + `Loaded` badge UI), foreground service notification actions, and mobile player gesture/HUD ergonomics. |
| `CineStreamV24FeaturesTest.kt` | 15 | `QrCodeMatrixGenerator`, `LocalWebRemoteServer` (PIN auth, push, `/api/sync`), `DeviceSyncCodec` (credential exclusion), `SleepTimer`, `SecondarySubtitleEngine`, `XmltvEpgParser` (`.xml` + `.xml.gz`), `OfflineDownloadManager` basics, `LocalNetworkClient` (WebDAV/Jellyfin), `GlobalSearchEngine`, and Compose UI tests for overlays & sheets. |
| `PrivateDnsAndNordVpnTest.kt` | 14 | `DohDnsClient` (`1.1.1.1`/`8.8.8.8`), `FastDnsResolver` DoH routing, `CredentialVault` Keystore encryption, `NordVpnApiClient` key exchange + 118+ country/city search, `NordLynxEngine` WireGuard config builder, `NetworkPrivacySheet` UI & compact 360dp zero-overlap layout. |
| `CineStreamV23FeaturesTest.kt` | 11 | `FrameDropAnalyzer` (Healthy / Seek Burst / Stream PTS / SW CPU Load), `MediaCodecQueueingPolicy`, `ContinueWatchingHelper`, `DelayAudioProcessor` PCM ring buffer, `SmartVpnFailoverCoordinator`, and `ContinueWatchingRow` UI. |
| `AudioBoostAndOptimizationTest.kt` | 11 | `AudioBoostMath` (0–200% / +20 dB), heap-capped `LoadControl`, `largeHeap`, HTTP/2 pool, `FastDnsResolver`, `FastStreamingLoadErrorHandlingPolicy`, `MediaEdgeCache` TV RAM-Direct, `ParallelPrefetchEngine` (HLS live handoff, audio lane, byte-range), `QuicThroughputWatchdog`, and `TracksSheet` UI. |
| `JsonPlaylistAndImdbTest.kt` | 11 | Unified Movie + Series JSON parsing, flat episodes, header inheritance, `CdnMirrorResolver` swaps & cache rules, `ImdbMetadataClient`, `SeriesDetailSheet`, `JsonPlaylistSheet`, and `TracksSheet` CDN controls. |
| `ExternalAudioUrlSyncTest.kt` | 10 | `ExternalAudioMediaSource` (`AudioOnlyFilteringMediaSource` + `ExternalAudioSyncMediaSource` track stripping & timeline-safe PTS shifting), `AudioDelayMath` unbounded `±60 min` parsing (`ms`/`s`/`m`/`mm:ss`), `PlaylistAudioResolver` (`default: true` / implicit first / all `default: false` / deduplication / `delayMs` clamping), `JsonPlaylistParser` array/object/shorthand `audios` parsing on Movies, Collections & Series (including `eg-playlist-v3.json`), `LocalWebRemoteServer` `load_audio`, `TracksSheet` external audio URL composer + sync UI, and `BuildConfig` v1.39. |
| `DecoderFallbackTest.kt` | 6 | Qualcomm HEVC detection, `.low_latency` exclusion, `SmartMediaCodecSelector` FFmpeg delegation, H.264/lower-res track fallback, `PlayerErrorCard` actions, and `PlayerStatsCard` decoder telemetry. |
| `MultiAccountWebDavTest.kt` | 6 | Room v4 multi-account persistence on identical WebDAV URLs (`upsert`/`update`/`deleteById`), strict HTTP(S) normalization, byte-for-byte special-character UTF-8 Basic Auth, bracket/space href encoding, live local `HttpServer` `PROPFIND` `301` redirect + cross-origin `302` `Authorization` stripping + sibling `.srt` discovery + `testConnection()`, and `LocalNetworkSheet` TorBox preset / edit / quick switcher UI. |
| `HlsSubtitleTranscodingTest.kt` | 6 | `SubtitleMediaSourceBuilder` `ProgressiveMediaSource` + `SubtitleExtractor` wrapping for SRT/VTT/ASS/TTML, CEA-608 fallback, `application/x-media3-cues` cue sample verification, local sidecar file merging, and `formatSubtitleCodecLabel()`. |
| `MovieCollectionsTest.kt` | 6 | Explicit `"type": "collection"` (`movies[]`) and flat `"collection"` tag auto-grouping (`part`/`order` sorting), `eg-playlist-v2.json` & `eg-playlist-v3.json` validation, per-movie IMDb enrichment preservation, Global Search collection-film preservation (including `externalAudios`), and `CollectionDetailSheet` UI (zero `S/E` labels, Play/Resume Next, per-film downloads). |
| `BrightnessControlTest.kt` | 5 | `PlayerGestureMath` TV brightness clamping (`10%–150%`) + dim/boost overlay alphas, mobile vertical `BrightnessHudOverlay` gauge geometry (`height > width`), Android TV `PlayerTopBar` brightness pill placement immediately next to `Sub`, and `TvBrightnessControlPanel` (`−`/`+`/presets/reset/done). |
| `AudioSyncNumericInputTest.kt` | 5 | `AudioDelayMath.parseDelayInputMs` (`ms`/`s`/`m` units, decimals, inline suffixes, clamping `-3,600,000..+3,600,000 ms`, invalid rejection) and `TracksSheet` & `SubtitleSheet` numeric input + `±` sign toggle UI. |
| `HlsAudioSyncSampleStreamTest.kt` | 4 | `AudioOffsetSampleStream` bidirectional PTS shifting & sub-zero drop, `skipData` reverse translation, `AudioSyncMediaPeriod` audio-only wrapping + unwrap/rewrap on track switch, `discardBuffer` protection, negative-delay `getBufferedPositionUs` prefetch preservation, and `computeRealignSeekPositionMs`. |
| `LocalFilePickerTest.kt` | 4 | Zero-permission SAF local video, M3U, JSON, and subtitle picker URI handling. |
| `SeekBarScrubEngineTest.kt` | 4 | Precision scrub step calculation and progressive D-pad hold acceleration (`±1m` to `±10m`). |
| `HlsAndAppCheckTest.kt` | 3 | `MediaFormatDetector` format/MIME detection and `PlayerStatsCard` rendering & close action. |
| `MultiJsonPlaylistTest.kt` | 3 | `MultiJsonPlaylistReducer` multi-URL aggregation, ID namespacing, `"All"` vs single-list filtering, and removal. |
| `CinemaSeekBarRobolectricTest.kt` | 2 | Buffered seekbar rendering, touch drag scrubbing, and TV focus state. |
| `FocusHighlightRobolectricTest.kt` | 2 | Android TV `tvFocusHighlight` focus ring and scale behavior. |
| `ExampleRobolectricTest.kt` | 1 | App name resource verification (`CineStream`). |
| `ExampleUnitTest.kt` | 1 | Baseline JVM sanity check. |
| **Total** | **150** | **100% passing on local JVM (`gradle :app:testDebugUnitTest`).** |

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

### 3. External Audio URLs (`.m3u8` / Direct Audio Formats) & Multi-Minute Sync
- **Adding at Runtime:** Open **Tracks → Audio → Add External Audio URL** (or push via **Web Remote → External Audio URL** on TV) and paste any `.m3u8`, **`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`**, `.aac`, `.dts`, `.opus`, `.ogg`, or muxed `.mp4`/`.mkv` URL. `ExternalAudioUrlInspector` detects both extensions and `Content-Type` headers on extensionless URLs, while `AudioOnlyFilteringMediaSource` strips any video stream automatically and exposes only the external audio track.
- **Mismatched Intros / Cuts (`±60 min` Sync):** Open **Tracks → Audio Sync**, tap coarse/fine presets (`−5s`, `−1s`, `−250ms`, `−50ms`, `+50ms`, `+250ms`, `+1s`, `+5s`), or enter any exact offset in **`ms`**, **`s`**, **`m`**, or **`mm:ss`** (e.g. `1m30s`, `-45.5s`, `2:15`, `-1250ms`) up to **`±60 minutes`** (`±3,600,000 ms`). Sample-level PTS shifting realigns immediately in-buffer with zero extra PCM RAM and persists per video (including inside `offline_audio.json` when downloaded offline).
- **If an External Audio URL Fails (404 / Expired Token / CORS):** CineStream automatically drops only the failed external audio source, retains surviving tracks (and any surviving `autoSelectAudioId`), and continues video playback without interruption.

### 4. JSON Playlist External Audio Not Auto-Loading
- **Check URL Scheme:** Only valid `http://` and `https://` audio URLs are accepted by `PlaylistAudioResolver`. Relative paths or `file://` URIs in remote JSON feeds are skipped.
- **Check `default` Flag Behavior:** If every entry in `audios[]` explicitly sets `"default": false` (or `audioDefault: false`), tracks are pre-loaded into **Tracks → Audio** without overriding the primary embedded audio on start. Set `"default": true` on the preferred dub—or omit `"default"` to auto-select the first valid track.
- **Check Initial `delayMs` vs Saved History:** A JSON `delayMs` seeds the initial Audio Sync offset on first play. If you previously adjusted Audio Sync manually on that same video URL, your saved Room history offset takes precedence so your manual adjustment isn't lost on resume.

### 5. Offline Downloads Stalling, Switching Networks, or Including Loaded External Audio
- **Multi-Quality, Embedded Audio & Loaded External Audio Selection (v1.39):** Tapping **Download** on mobile/tablet inspects HLS `#EXT-X-STREAM-INF` × `#EXT-X-MEDIA:TYPE=AUDIO` renditions *and* merges all loaded external audio tracks (from v3 JSON playlists, Tracks → Audio, or Web Remote) with a cyan **`Loaded`** pill and combined estimated file sizes—even for single-variant HLS or progressive MP4/MKV streams. Supported offline external audio formats include **`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`** (plus `.aac`, `.opus`, `.ogg`, `.dts`, `.mka`, `.weba`) and external **HLS `.m3u8`** (audio-only or master playlists).
- **Automatic Offline Re-Attachment:** Downloaded external audio is stored in `<download_folder>/ext_audio/` alongside an atomic `offline_audio.json` sidecar preserving track label, language, format badge, and `delayMs`. Opening the saved video from **Downloads** or **Continue Watching** re-attaches the offline external audio automatically (and keeps the original embedded audio switchable inside **Tracks → Audio**).
- **Pause / Resume & Network Handoff:** Tap **Pause** anytime inside `DownloadsSheet` (or from the foreground notification) and **Resume** later—completed HLS segments, progressive `Range` chunks, and external audio state are checkpointed in `download_state.json` and never re-fetched. Switching between Wi-Fi and cellular automatically resumes in-flight transfers.
- **Note:** Offline Downloads are intentionally hidden on Android TV (`!isTvDevice`) to protect constrained TV eMMC storage.

### 6. Movie Collections & Per-Movie IMDb Metadata
- **Flat `"collection"` Tag Grouping:** Ensure every film in a franchise uses the exact same `"collection"` string (case-insensitive matching is supported, and optional `"part"`/`"order"` integers guarantee exact trilogy ordering).
- **Per-Movie Posters & Ratings:** Provide `"imdbId": "tt..."` on each film inside `movies[]` (or each flat film) so `ImdbMetadataClient` enriches every film individually inside `CollectionDetailSheet`.

### 7. External Subtitles on HLS Streams (Media3 `1.11.1`)
- `CinePlayerManager` builds sidecar subtitle sources through `SubtitleMediaSourceBuilder` (`ProgressiveMediaSource` + `SubtitleExtractor` → `application/x-media3-cues`) and sets `experimentalParseSubtitlesDuringExtraction(true)` on `DefaultHlsExtractorFactory`, supporting `.srt`, `.vtt`, `.ass`/`.ssa`, `.ttml`, and Dual Subtitles seamlessly across HLS, MKV, and MP4 streams.

### 8. `c2.qti.hevc.decoder` Crash on 4K 10-Bit HDR10
- CineStream automatically excludes `.low_latency` codecs and fails over to **NextLib native FFmpeg (`FfmpegVideoRenderer`)** with a clean `surfaceResetToken` surface reset. You can also choose **Tracks → Video Decoder → Software (FFmpeg)** directly.

### 9. Playback Freezes at One Specific Timestamp in HLS
- CineStream bypasses the disk cache automatically on the first decode error. If the upstream `.ts`/`.m4s` segment itself is corrupt, tap **`Skip Segment (+5s)`** on the error card to jump cleanly past the bad GOP.

### 10. Phone-to-TV Web Remote Cannot Connect
- Confirm both phone and Android TV are on the same local Wi-Fi subnet (disable AP/client isolation on guest routers), scan the QR code or type the displayed `http://<lan-ip>:<port>` URL, and enter the current 6-digit session PIN.

