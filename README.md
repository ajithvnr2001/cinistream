# CineStream — Universal Cinema Media Player for Android Mobile & Android TV

**CineStream** (`com.aistudio.cinestream.vqxkpm`, **v1.25 / versionCode 25**) is a high-performance, zero-OOM Android video streaming and local media player built with **Kotlin**, **Jetpack Compose (Material 3)**, **AndroidX Media3 (`1.11.1`)**, **Google Play Services Cronet (`18.1.1`, HTTP/3 QUIC + TLS 1.3)**, **NextLib native FFmpeg (`1.11.1-0.16.0`)**, and **embedded WireGuard (`com.wireguard.android:tunnel`, `GoBackend`)**.

It ships as a single 36 MB universal APK (**`cinistream-v25.apk`** and **`CineStream-universal.apk`**) covering modern 64-bit phones and tablets (`arm64-v8a`), 32-bit Android TVs (`armeabi-v7a`, including 1 GB RAM Android 9 TVs), and `x86_64` emulators.

---

## Key Features

### Editorial Cinema UI (v1.25 — 35mm Screening Room)
- **Intentional Dark-Room Palette:** Warm carbon matte surfaces (`#09090B` / `#121215` / `#1A1A1F`), warm archival ivory ink (`#F4F1EA`), and projector tungsten gold (`#E8A435`) with whisper-thin `0.75dp` hairlines—eliminating glare and nested boxed-card clutter on both OLED phones and living-room TVs.
- **Bundled Editorial Typography (`res/font/`):** Pairs **Fraunces** (editorial display headlines), **DM Sans** (crisp UI and body copy), and **JetBrains Mono** (timestamps, codecs, bitrates, and technical telemetry).
- **Direct, Zero-Slop Copy:** All UI labels, dialogs, and empty states follow `no-ai-slop` writing rules—active voice, concrete terminology, and zero marketing filler.
- **Refined Home & Player Surfaces:** Compact atmospheric hero, unified hairline-divided Stream Dock, 2:3 editorial poster cards, 16:9 Continue Watching cinema stills with flush progress rails, poster-backed Watchlist cards, and smoked-glass player HUD overlays.

### High-Speed Adaptive Streaming Engine (HLS, DASH, MKV, MP4, WebM, TS)
- **Live-Streaming Parallel HLS & Progressive Byte-Range Prefetching (`ParallelPrefetchEngine`):**
  - Tees HLS media playlists (`.m3u8`) at 0 ms overhead and streams **2× focused parallel segments on Android TV** (`<= 14 MB` RAM cap, `12 MB` single-segment VBR ceiling) and **3× on Mobile/Tablet** (`<= 28 MB` RAM cap, `16 MB` ceiling), backed by a **dedicated non-blocking audio lane**.
  - **Zero-Wait Live Handoff (`StreamingPrefetchEntry`):** ExoPlayer attaches mid-download and reads 32 KB chunks live as bytes arrive, with foreground-first anti-starvation during seeks or low buffer (`< 8s`) and automatic CPU throttling during software decode.
  - Accelerates progressive `.mp4` / `.mkv` / `.webm` links via **2–3× parallel `206 Content-Range` lookahead** (1.5–2 MB slices) with automatic single-stream fallback.
- **Self-Healing HTTP/3 (QUIC) ↔ HTTP/2 (TCP) Engine (`StreamingNetworkEngine` + `QuicThroughputWatchdog`):**
  - Asynchronously initializes **Play Services Chromium Cronet** (`HTTP/3 QUIC + TLS 1.3 + BoringSSL + BBR + 0-RTT`) off the main thread, backed by an HTTP/2 OkHttp pool (`Dispatcher(maxRequests = 32, maxRequestsPerHost = 12)`, `ConnectionPool(12, 5m)`).
  - **`QuicThroughputWatchdog` (RAM-Hit Immune):** Monitors pure `BandwidthMeter` socket throughput and mid-playback rebuffer stalls, immediately demoting UDP-throttled QUIC connections to IPv4-first **HTTP/2 TCP** at the exact millisecond. Includes a 1-tap **Network Transport (`Auto` / `HTTP/2` / `HTTP/3`)** selector in Tracks.
- **Built-In Encrypted DNS (DoH) + Native In-App NordVPN NordLynx (WireGuard):**
  - 5-minute TTL IPv4-first DNS cache (`FastDnsResolver`) with 1-tap **Cloudflare `1.1.1.1`** and **Google `8.8.8.8`** DNS-over-HTTPS via direct bootstrap IPs.
  - Embedded **`NordLynxEngine`** (`GoBackend` WireGuard) exchanges your NordVPN Access Key for a Curve25519 private key (encrypted in **Android Keystore AES-256-GCM**), searches **118+ live NordVPN countries & cities**, and scopes the WireGuard tunnel per-app with `1.1.1.1` DNS—no external VPN app required.
  - **Smart Auto-VPN Failover (`SmartVpnFailoverCoordinator`):** Optional setting that starts on fast direct ISP and connects NordLynx automatically only when streams stall or fail.
- **Zero-eMMC-Stall TV RAM-Direct Mode & Mobile Edge Cache (`MediaEdgeCache`):**
  - Android TV and low-RAM devices stream directly into ExoPlayer's heap-capped RAM buffer with zero synchronous eMMC disk writes.
  - Modern phones and tablets use an LRU-capped **128 MB** edge disk cache with a **128 KB** `CacheDataSink` (6× fewer flash write syscalls), bypassing manifests, subtitles, and local files.
- **Instant HD Cold Start, Origin Pre-Warming & Fast Retry (`FastStreamingLoadErrorHandlingPolicy`):** Smart initial bitrate estimates (`6 Mbps` TV Wi-Fi, `10 Mbps` phone Wi-Fi, `12 Mbps` Ethernet/5G), debounced `HEAD` origin preconnects, `500 ms` initial transient retry, and instant `C.TIME_UNSET` 4xx fail-fast for Multi-CDN failover.

### Hybrid Hardware + Native FFmpeg 10-Bit Decoding & Frame-Drop Diagnostics
- **Hybrid GPU + Native FFmpeg Pipeline (`NextRenderersFactory` & `SmartMediaCodecSelector`):**
  - Hardware GPU decoding first with automatic zero-recreation failover to **NextLib native FFmpeg (`FfmpegVideoRenderer`)** supporting **10-bit HEVC (`Main 10`, 4K HDR10)**, 8-bit HEVC, H.264, VP8/VP9, and **AV1 (`dav1d`)**.
  - Automatic exclusion of Qualcomm `.low_latency` WebRTC codecs (`c2.qti.hevc.decoder.low_latency`) on VOD streams, Dolby Vision → HEVC base-layer fallback, and native FFmpeg audio (**DTS, AC-3, E-AC-3, TrueHD, FLAC, ALAC, Opus, Vorbis, AAC**).
  - Tolerant HLS TS extraction (`FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS`) and clean `surfaceResetToken` surface handoff between `MediaCodec` and `FfmpegVideoRenderer`.
- **Frame-Drop Root-Cause Diagnostics (`FrameDropAnalyzer` + `MediaCodecQueueingPolicy`):**
  - Live rolling 3s `drops/sec` telemetry in **Stats for Nerds** classifying drops as **Healthy**, **Seek/Start Burst** (harmless), **Stream PTS / Re-Sync** (URL/origin-side), or **SW CPU Load** (player-side).
  - Selective Android 12+ Async MediaCodec queueing (`Async`/`Sync` badge) and seamless display refresh-rate matching (`VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`).

### Multi-JSON Catalogs, Live TV EPG, Subtitles & Local Ecosystem
- **Unified Multi-JSON Movie & Series Catalogs (`MultiJsonPlaylistReducer`):** Persist multiple JSON playlist URLs + local files simultaneously, switch via `"All"` or per-playlist chips, auto-fetch IMDb posters/ratings/years, and fail over across per-item `mirrors` and path-preserving `cdnMirrors` with inherited HTTP headers.
- **Continue Watching, Watchlist & Global Search (Room v3):** Deduplicated per-series Continue Watching rail, per-episode resume + `Watched` badges, poster-backed Watchlist, and instant search across Watchlist, Catalogs, Live TV, Downloads, and History.
- **OpenSubtitles REST API v1 (with `/api/v1/login` JWT Auth) & Dual Subtitles:** Search and download OpenSubtitles captions directly (using `OPENSUBTITLES_API_KEY` from AI Studio Secrets + optional encrypted account sign-in unlocking 20/day free or 1,000/day VIP quotas with live remaining-quota tracking), plus simultaneous top-screen secondary `.srt`/`.vtt` rendering (`SecondarySubtitleEngine`).
- **Audio Precision & Playback Comfort:** `0%–200%` (+20 dB) Volume Boost, Night Mode dialogue boost DSP, zero-reload PCM Audio Delay (`-500 ms..+500 ms` via `DelayAudioProcessor`), `±100ms`/`±500ms` Subtitle Sync, Sleep Timer (`15m/30m/45m/60m/End of Ep` with 5s fade-out), Up-Next 10s countdown, Skip Intro (`+85s`), Hold-to-2× speed, Configurable Skip Interval (`5s/10s/15s/30s`), Chapter Markers & A-B Loop, Mobile PiP & Background Audio (`MediaPlaybackService`), and touch brightness/volume/pinch-to-zoom (`1.0×–2.5×`) gestures.
- **Live TV XMLTV EPG & Channel Zapping:** Streams `.xml`/`.xml.gz` XMLTV guides (auto-detected from `#EXTM3U url-tvg`), renders Now/Next progress bars, and supports `CH-`/`CH+` and TV remote `ChannelUp`/`ChannelDown` zapping.
- **Phone-to-TV Web Remote, Local Wi-Fi Sync, Offline Downloads & NAS/Jellyfin:** Built-in LAN HTTP server with pure-Kotlin QR code + 6-digit PIN (`LocalWebRemoteServer`), credential-free Wi-Fi & SAF JSON sync (`DeviceSyncCodec`), mobile offline MP4/MKV/HLS downloads (`OfflineDownloadManager`), and LAN **WebDAV / Jellyfin** streaming (`LocalNetworkClient`) with Keystore-encrypted credentials.

---

## Documentation Index

| Document | Description |
| :--- | :--- |
| [**Architecture & Codebase Guide**](docs/ARCHITECTURE.md) | Complete v1.25 MVVM architecture, end-to-end streaming/prefetch/VPN pipeline, full 60+ file package inventory, Room v3 + Keystore persistence, state-frequency isolation, and UI design tokens. |
| [**Decoder, Transport & Playback Engine**](docs/DECODER_AND_PLAYBACK_ENGINE.md) | Media3 `1.11.1`, Play Services Cronet HTTP/3 + `QuicThroughputWatchdog`, `ParallelPrefetchEngine`, `NordLynxEngine` WireGuard + DoH, NextLib FFmpeg 10-bit, `FrameDropAnalyzer`, `DelayAudioProcessor`, Night Mode DSP, and OpenSubtitles JWT client. |
| [**Playlists, Live TV EPG, NAS & CDN Guide**](docs/PLAYLIST_AND_CDN_GUIDE.md) | Multi-JSON Movies & Series schema, field aliases, IMDb enrichment, 5-layer in-app CDN, M3U + XMLTV EPG format, WebDAV/Jellyfin streaming, and Phone-to-TV Web Remote / Wi-Fi Sync API. |
| [**Memory Management & Android TV Optimization**](docs/MEMORY_AND_TV_OPTIMIZATION.md) | Zero-OOM engineering for 32-bit Android 9 TVs, bounded prefetch & heap budgets, TV RAM-Direct mode, APK compression (36 MB universal), and v1.25 10-foot TV ergonomics. |
| [**Testing & Troubleshooting Playbook**](docs/TESTING_AND_TROUBLESHOOTING.md) | Running all **90 unit & Robolectric tests across 14 suites**, interpreting every Stats for Nerds indicator, and troubleshooting Wi-Fi buffering, QUIC throttling, frame drops, HEVC 10-bit, VPN, OpenSubtitles auth, and audio lip-sync. |

---

## Supported Formats, Protocols & Codecs

| Category | Supported Formats / Protocols / Codecs | Engine |
| :--- | :--- | :--- |
| **Network Transports** | **HTTP/3 (QUIC + TLS 1.3 + BBR)**, **HTTP/2 Multiplexed**, Parallel HLS/Byte-Range Prefetch, DoH (`1.1.1.1`/`8.8.8.8`), WireGuard NordLynx | **Play Services Cronet `18.1.1`**, **OkHttp `4.10.0`**, **`GoBackend` WireGuard** |
| **Streaming Protocols** | HLS (`.m3u8` TS & fMP4/CMAF), DASH (`.mpd`), Progressive HTTP/HTTPS, WebDAV, Jellyfin | Media3 ExoPlayer `1.11.1` + `LocalNetworkClient` |
| **Containers** | MP4, MKV (Matroska), WebM, MPEG-TS, FLV, OGG, Local `content://` / `file://` | Media3 Extractors + Tolerant TS |
| **Video (Hardware)** | H.264/AVC, HEVC/H.265 (8-bit & 10-bit HDR10), VP8, VP9, AV1, Dolby Vision (HEVC fallback) | Android `MediaCodec` (Selective Async/Sync) |
| **Video (Software Fallback)** | **HEVC/H.265 8-bit & 10-bit Main 10**, H.264, VP8, VP9, **AV1 (`dav1d`)** | **NextLib Native FFmpeg (`FfmpegVideoRenderer`)** |
| **Audio** | AAC, MP3, Opus, Vorbis, FLAC, PCM, **AC-3, E-AC-3, DTS, TrueHD, ALAC** | Platform `MediaCodec` + **NextLib FFmpeg (`FfmpegAudioRenderer`)** |
| **Subtitles** | SRT, WebVTT (`.vtt`), ASS/SSA, TTML, Embedded CC, Dual Subtitles, OpenSubtitles REST v1 | Media3 `SubtitleView` + `SecondarySubtitleEngine` |
| **Playlists & Guides** | Multi-JSON (Movies + Multi-Season Series), M3U / M3U8 IPTV, XMLTV EPG (`.xml`/`.xml.gz`) | `JsonPlaylistParser`, `M3uPlaylistParser`, `XmltvEpgParser` |

---

## Quick Start

### Requirements
- **Android SDK:** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24` (Android 7.0+)
- **JDK:** Java 17
- **Build Tool:** Gradle (Kotlin DSL)

### Build & Verify
```bash
# Run all 90 unit & Robolectric Compose UI tests across 14 suites
gradle :app:testDebugUnitTest

# Build universal debug APK (arm64-v8a + armeabi-v7a + x86_64)
gradle :app:assembleDebug

# Pre-packaged root APKs (v1.25 / 36 MB):
#   ./cinistream-v25.apk
#   ./CineStream-universal.apk
```

### Installing on Phone or Android TV
1. Use AI Studio's **Settings → Generate APK** (recommended if you configured `OPENSUBTITLES_API_KEY` or `NORDVPN_ACCESS_TOKEN` in the AI Studio Secrets panel so your keys are injected) or sideload **`cinistream-v25.apk`**.
2. Enable **Install Unknown Apps** for your file manager or sideloader and install.
3. **Optional — OpenSubtitles Sign-In:** In **Player → CC (Subtitles) → OpenSubtitles Search**, sign in once with your OpenSubtitles username & password to unlock authenticated free (20/day) or VIP (1,000/day) downloads.
4. **Optional — DNS & NordVPN:** Open **DNS & VPN** from the Home header to enable Cloudflare `1.1.1.1` DoH, connect any of **118+ NordVPN countries/cities** via embedded WireGuard NordLynx, or enable **Auto-VPN Failover**.

---

## Controls Reference (Mobile Touch & Android TV Remote)

| Action | Mobile / Tablet Touch | Android TV D-Pad / Remote |
| :--- | :--- | :--- |
| **Show / Hide Controls** | Single tap on video | `DPAD_CENTER` / `OK` / `MENU` |
| **Play / Pause** | Center Play/Pause button | `MEDIA_PLAY_PAUSE` or `OK` on Play button |
| **Seek −10s / +10s** *(configurable 5–30s)* | Double-tap left / right side | `DPAD_LEFT` / `DPAD_RIGHT` or `MEDIA_REWIND` / `MEDIA_FAST_FORWARD` |
| **Hold 2.0× Speed** | Press and hold anywhere on video | Speed menu in player bar |
| **Precision Timeline Scrub** | Drag `CinemaSeekBar` or tap `-1m`/`+1m` | Focus SeekBar + hold `DPAD_LEFT` / `DPAD_RIGHT` |
| **Brightness / Volume / Zoom** | Left swipe (brightness), Right swipe (0–200% volume), Pinch (`1.0×–2.5×`) | Focus Volume button → `DPAD_LEFT` / `DPAD_RIGHT` |
| **Live TV Channel Zap** | `CH-` / `CH+` buttons in top bar | Remote `CHANNEL_UP` / `CHANNEL_DOWN` keys |
| **Playback, Tracks & Audio DSP** | Tap **Tracks** icon | Focus **Tracks** → Quality, Audio, Decoder, Transport, Delay, Night Mode, Sleep Timer, A-B Loop, Stats |
| **Subtitles & OpenSubtitles** | Tap **CC** icon | Focus **CC** → Tracks, OpenSubtitles Search/Sign-In, Dual Subtitles, Sync, Style |
| **Exit Player Safely** | Back button / gesture | `BACK` key (saves position, clears surface & trims buffers) |

---

## Project Structure

```text
.
├── README.md                          # Project overview, quick start & complete version history
├── cinistream-v25.apk                 # Universal Phone + Android TV APK (v1.25 / 36 MB)
├── CineStream-universal.apk           # Latest universal APK alias (v1.25 / 36 MB)
├── eg-playlist.json                   # Sample unified Movie + Multi-Season Series + CDN playlist
├── .env.example                       # Optional secret placeholders (OPENSUBTITLES_API_KEY, NORDVPN_ACCESS_TOKEN)
├── docs/
│   ├── ARCHITECTURE.md                # Full v1.25 MVVM architecture, pipeline & complete module inventory
│   ├── DECODER_AND_PLAYBACK_ENGINE.md # Cronet HTTP/3, ParallelPrefetch, NordLynx, FFmpeg 10-bit, FrameDropAnalyzer & Audio DSP
│   ├── PLAYLIST_AND_CDN_GUIDE.md      # Multi-JSON/M3U/XMLTV schemas, NAS/Jellyfin, Web Remote & 5-layer CDN
│   ├── MEMORY_AND_TV_OPTIMIZATION.md  # Zero-OOM Android 9 TV engineering, APK sizing & v1.25 10-foot UI
│   └── TESTING_AND_TROUBLESHOOTING.md # 90-test suite (14 files), Stats for Nerds reference & issue playbooks
├── gradle/libs.versions.toml          # Version catalog (Media3 1.11.1, Cronet 18.1.1, NextLib, WireGuard, Room)
└── app/src/
    ├── main/
    │   ├── res/font/                  # Bundled Fraunces, DM Sans & JetBrains Mono (.ttf)
    │   ├── res/values/strings.xml     # Zero-slop UI strings
    │   └── java/com/example/
    │       ├── CineStreamApplication.kt   # Async Cronet HTTP/3, FastDns/DoH + OkHttp pool, RGB_565 Coil, onTrimMemory
    │       ├── MainActivity.kt            # Edge-to-edge host, Navigation Compose, PiP, VPN consent & VIEW intents
    │       ├── data/                      # Room v3 DB (History, Favorites, Downloads, NAS), DataStore & DeviceSyncCodec
    │       ├── model/                     # MediaModels & V24Models (EPG, SleepTimer, AbLoop, Chapters, OpenSubtitles)
    │       ├── player/                    # 26 playback, transport, prefetch, VPN, decoder, DSP, subtitle & NAS engines
    │       ├── remote/                    # LocalWebRemoteServer (LAN HTTP + Sync) & QrCodeMatrixGenerator
    │       ├── ui/                        # 35mm Screening Room theme, Home, Player, 15 sheets/overlays & TV utilities
    │       └── viewmodel/                 # CineStreamViewModel, MultiJsonPlaylistState, NetworkPrivacyState, V24UiStates
    └── test/java/com/example/             # 14 JVM unit & Robolectric Compose UI test suites (90 tests)
```

---

## Version History Highlights

- **v1.25 (`cinistream-v25.apk`, 36 MB):** Complete **"No-AI-Slop" Editorial Cinema UI Revamp (35mm Screening Room)** + **OpenSubtitles Account Sign-In (`/api/v1/login` JWT Auth)**.
  - Replaced generic Tailwind-slate dark SaaS colors with warm film-black carbon surfaces (`#09090B`/`#121215`/`#1A1A1F`), archival warm ivory ink (`#F4F1EA`), projector tungsten gold (`#E8A435`), and `0.75dp` hairlines.
  - Upgraded bundled typography to **Fraunces** (editorial display), **DM Sans** (UI/body), and **JetBrains Mono** (technical telemetry).
  - Applied `petergyang/no-ai-slop` writing rules across `strings.xml` and composables—cutting filler, passive phrasing, and marketing clichés.
  - Unboxed the Home Stream Dock (hairline-separated Subtitle & JSON rows instead of nested cards), upgraded Watchlist cards with 2:3 poster artwork thumbnails, refined 2:3 poster cards and 16:9 Continue Watching cinema stills with flush bottom progress rails, and harmonized player seekbar & HUD overlays into warm smoked-glass chrome.
  - Added full OpenSubtitles `/api/v1/login` JWT Bearer authentication, VIP/standard dynamic base URL routing, live remaining daily download quota display, and Android Keystore AES-256-GCM encrypted account persistence.
  - Verified with **90/90** passing unit & Robolectric Compose UI tests across 14 suites.
- **v1.24:** Shipped 13 end-to-end features across Phone and Android TV: Phone-to-TV Web Remote & QR Link Sender, Local Wi-Fi Sync & SAF JSON Backup, Up-Next 10s Episode Countdown + Skip Intro (`+85s`), Sleep Timer with 5s fade-out, Night Mode / Dialogue Boost DSP, Preferred Audio & Subtitle Language Defaults, Hold-to-2× Fast Forward & Configurable Skip Interval, Chapter Markers & A-B Loop, Global Search & Unified Watchlist (Room v3), Live TV XMLTV EPG Guide & Channel Zapping, Online OpenSubtitles Search & Dual Subtitles, Mobile Offline Downloads (MP4/MKV & HLS), and Local Network Browser (WebDAV + Jellyfin).
- **v1.23:** Added Frame-Drop Root-Cause Diagnostics (`FrameDropAnalyzer` + selective Android 12+ Async MediaCodec queueing + seamless display refresh-rate matching + SW-decode prefetch CPU throttling), Continue Watching & Per-Episode Resume (Room v2), Mobile PiP & Background Audio Playback, Real-Time Audio Delay (`DelayAudioProcessor`) & Subtitle Sync, Left-Side Brightness Swipe & Pinch-to-Zoom (`1.0×–2.5×`), and Smart Auto-VPN Failover.
- **v1.22:** Embedded native in-app WireGuard (`GoBackend`) NordLynx engine supporting all 118+ NordVPN countries & cities directly inside CineStream with searchable location picker and per-app `1.1.1.1` DNS.
- **v1.21:** Added 1-tap Direct ISP disable button in Privacy sheet and separated the Home `Resume` banner cleanly below the header on compact phones and Android TV.
- **v1.20:** Integrated Cloudflare `1.1.1.1` / Google `8.8.8.8` Encrypted DNS-over-HTTPS (DoH), NordVPN Access Token login + Keystore encryption (`CredentialVault`), and live Cloudflare trace route testing.
- **v1.19:** Added Multi-JSON Playlist persistence, concurrent loading, collision-free item namespacing (`MultiJsonPlaylistReducer`), `"All"` + per-playlist switcher chips, and cross-playlist series navigation.
- **v1.18:** Upgraded `ParallelPrefetchEngine` with zero-wait live progressive streaming handoff (`StreamingPrefetchEntry`), 12–16 MB VBR ceilings, dedicated audio lane, and RAM-hit-immune `QuicThroughputWatchdog`.
- **v1.17:** Added `ParallelPrefetchEngine` (parallel HLS segments + progressive `206 Content-Range` lookahead), high-concurrency OkHttp pooling, and persistent Network Transport selector (`Auto`/`HTTP/2`/`HTTP/3`).
- **v1.16:** Added `StreamingNetworkEngine` (asynchronous Play Services Cronet HTTP/3 QUIC), 5-min IPv4-first `FastDnsResolver`, Android TV RAM-Direct mode, origin pre-warming, and `FastStreamingLoadErrorHandlingPolicy`.
- **v1.15:** Switched streaming transport to HTTP/2 `OkHttpDataSource`, prioritized Stereo 2-ch hardware DSP audio on Android TV, tuned faster TV ABR downshifts, and upgraded heap-capped `LoadControl` to 20–45s / 12–40 MB.
- **v1.14:** Upgraded Media3 to `1.11.1`, integrated NextLib native FFmpeg (`1.11.1-0.16.0`) for 10-bit HEVC Main 10, AV1, and DTS/AC-3/TrueHD, blocked `.low_latency` Qualcomm WebRTC codecs, and added `surfaceResetToken` surface handoff.
- **v1.13:** Added tolerant HLS HEVC TS flags, synchronous MediaCodec queueing, automatic cache bypass on decode error, bidirectional HW↔SW fallback, and `Skip Bad Segment (+5s)`.
- **v1.12:** Introduced `DecoderEngine` (`VideoDecoderMode`, `SmartMediaCodecSelector`), Qualcomm HEVC failover, Dolby Vision → HEVC fallback, and Decoder telemetry.
- **v1.11:** Added unified Movies + Multi-Season Series JSON playlists (`SeriesDetailSheet`, `Next Ep`) and hybrid in-app CDN (`CdnMirrorResolver`, `MediaEdgeCache`, custom HTTP headers).
- **v1.10:** Added YouTube-style **Stats for Nerds** telemetry with sparklines and hardened 32-bit Android 9 TV Zero-OOM memory safety.
