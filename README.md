# CineStream — Universal Cinema Media Player for Android Mobile & Android TV

**CineStream** (`com.aistudio.cinestream.vqxkpm`, **v1.19 / versionCode 19**) is a high-performance, zero-OOM Android video streaming and local media player built with **Kotlin**, **Jetpack Compose (Material 3)**, **AndroidX Media3 (`1.11.1`)**, **Google Play Services Cronet (`18.1.1`, HTTP/3 QUIC + TLS 1.3)**, and **NextLib native FFmpeg (`1.11.1-0.16.0`)**.

It runs from a single universal APK (`cinistream-v19.apk`, **36 MB**) across modern 64-bit Android phones/tablets (`arm64-v8a`), legacy 32-bit Android TVs (`armeabi-v7a`, including 1 GB RAM Android 9 TVs), and `x86_64` emulators.

---

## Key Features

### High-Speed Adaptive Streaming Engine (HLS, DASH, MKV, MP4, WebM, TS)
- **Live-Streaming Parallel HLS & Progressive Byte-Range Prefetching (`ParallelPrefetchEngine`):**
  - Tees HLS media playlists (`.m3u8` / `mpegurl`) with 0 ms overhead and streams **2× focused parallel segments on Android TV** (`<= 14 MB` RAM cap, `12 MB` single-segment VBR ceiling) and **3× on Mobile/Tablet** (`<= 28 MB` RAM cap, `16 MB` ceiling), backed by a **dedicated non-blocking audio lane** so demuxed 5.1/7.1 audio never queues behind video.
  - **Zero-Wait Live Streaming Handoff (`StreamingPrefetchEntry`):** If ExoPlayer requests a segment already mid-download, it attaches immediately and streams 32 KB chunks live as they arrive — zero blocking wait, zero mid-download discard, and foreground-first anti-starvation on seeks/low buffer (`< 8s`).
  - Accelerates direct progressive `.mp4` / `.mkv` / `.webm` URLs via **2–3× parallel `206 Content-Range` lookahead** (1.5–2 MB slices) with automatic single-stream fallback for non-range servers.
- **Self-Healing HTTP/3 (QUIC) ↔ HTTP/2 (TCP) Engine (`StreamingNetworkEngine` + `QuicThroughputWatchdog`):**
  - Asynchronously initializes **Google Play Services Chromium Cronet** (`HTTP/3 QUIC + TLS 1.3 + BoringSSL + BBR + 0-RTT`) off the main thread, backed by a high-concurrency OkHttp HTTP/2 pool (`Dispatcher(maxRequests = 32, maxRequestsPerHost = 12)`, `ConnectionPool(12, 5m)`).
  - **`QuicThroughputWatchdog` (RAM-Hit Immune):** Monitors pure `BandwidthMeter` socket throughput (`< 2.5 Mbps` for 3 windows) and mid-playback rebuffer stalls (`>= 2` stalls at `< 2.5 Mbps`) — immune to `1 ms` RAM prefetch hits — and immediately tears down throttled `udp6` QUIC sockets to reconnect over IPv4-first **HTTP/2 TCP** at the exact millisecond. Includes a persistent 1-tap **Network Engine (`Auto` / `HTTP/2` / `HTTP/3`)** selector in Tracks.
- **In-Memory DNS Cache + IPv4-First Dual-Stack Ordering (`FastDnsResolver`):** 5-minute TTL bounded DNS cache (128 hosts) that orders IPv4 ahead of IPv6 to prevent dual-stack ISP fallback delays (e.g., JioFiber → Cloudflare) while preserving full IPv6 fallback.
- **Zero-eMMC-Stall TV RAM-Direct Mode & Buffered Mobile Edge Cache (`MediaEdgeCache`):**
  - On Android TV and constrained-RAM devices, streams directly into ExoPlayer's heap-capped RAM buffer with **zero synchronous eMMC disk/SQLite writes** on the loader thread.
  - On modern phones and tablets, caches VOD chunks in an LRU-capped **128 MB** edge disk cache with a **128 KB** buffered `CacheDataSink` (6× fewer flash write syscalls), bypassing manifests, subtitles, and local files.
- **Instant HD Cold Start & Background Origin Pre-Warming:** Smart per-network initial bitrate estimates (`6 Mbps` TV Wi-Fi, `10 Mbps` phone Wi-Fi, `12 Mbps` Ethernet/5G) prevent blurry 480p first chunks, while debounced background `HEAD scheme://host/` preconnects warm DNS + TLS/QUIC sockets when opening playlists, Series drawers, or watching episodes.
- **Fast Transient Wi-Fi Recovery (`FastStreamingLoadErrorHandlingPolicy`):** Retries momentary Wi-Fi drops in **`500 ms`** (half of ExoPlayer's default) while failing fast (`C.TIME_UNSET`) on permanent HTTP 4xx or parser errors so Multi-CDN mirror failover triggers immediately.

### Hybrid Hardware + Native FFmpeg 10-Bit Decoding
- **Universal Stream & Local Playback:** Direct HTTP/HTTPS URLs (`HLS`, `M3U8`, `DASH`, `MKV`, `MP4`, `WEBM`, `TS`) and zero-permission local storage files via Android's Storage Access Framework (`OPEN_DOCUMENT`).
- **Hybrid GPU + Native FFmpeg Pipeline (`NextRenderersFactory` & `SmartMediaCodecSelector`):**
  - Hardware GPU/DSP decoding first (`MediaCodec` with synchronous queueing for Snapdragon Codec 2.0 stability).
  - Automatic zero-recreation failover to **NextLib native FFmpeg (`FfmpegVideoRenderer`)** supporting **10-bit HEVC (`Main 10`, e.g., 4K HDR10 `hvc1.2.4.H150.B0`)**, 8-bit HEVC, H.264, VP8/VP9, and **AV1 (`dav1d`)**.
  - Automatic exclusion of Qualcomm `.low_latency` WebRTC codecs (`c2.qti.hevc.decoder.low_latency`) on VOD streams.
  - Native FFmpeg audio decoding (**DTS, AC-3, E-AC-3, TrueHD, FLAC, ALAC, Opus, Vorbis, AAC**).
  - Automatic Dolby Vision (`video/dolby-vision`) → HEVC (`video/hevc`) compatibility fallback.
- **Tolerant HLS HEVC Parsing:** Configured with `FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS` to handle FFmpeg-muxed HLS `.ts`/fMP4 segments containing CRA/BLA non-IDR keyframes.
- **Multi-Tier Self-Healing Error Recovery:** Automatic edge-cache bypass → bidirectional HW ↔ FFmpeg SW failover (with clean `surfaceResetToken` surface handoff) → automatic H.264/lower-resolution variant fallback → one-tap **Skip Bad Segment (+5s)**.

### Unified Multi-JSON Catalogs, Series & Multi-CDN Resilience
- **Multi-JSON Playlist Support (`MultiJsonPlaylistReducer`):** Load and persist **multiple** remote JSON playlist URLs (and local JSON files) simultaneously with automatic v1.18 single-URL migration, bounded-concurrency auto-loading on startup, collision-free item ID namespacing, instant **"All" + per-playlist switcher chips** in both Home and Player, inline `+` Add URL dialogs, per-playlist or Refresh-All syncing, and individual playlist removal.
- **Unified Movie + Series JSON Playlists:** Each JSON catalog supports movies, multi-season TV series, seasons, episodes, auto-advance to next episode across playlists, and in-player episode/catalog switching.
- **M3U / M3U8 IPTV Playlists:** Full `#EXTINF` parsing with category groups, channel logos, search, and live stream detection.
- **Automatic IMDb Enrichment:** Extracts `tt*` IMDb IDs from playlist items and fetches posters, ratings, and release years automatically.
- **Multi-CDN Mirror Failover & Inheritable HTTP Headers:** Per-item `mirrors`/`sources` + playlist-level path-preserving `cdnMirrors` host swaps with mid-stream failover at the exact millisecond, plus inheritable `Authorization`, `Referer`, `Origin`, and `User-Agent` headers (Playlist → Series → Season → Episode) propagated across both HTTP/3 Cronet and HTTP/2 OkHttp.

### Audio, Subtitles, Telemetry & Android TV Ergonomics
- **0%–200% Audio Volume Boost (+20 dB):** Hardware DSP amplification via `LoudnessEnhancer` paired with an API 28+ `DynamicsProcessing` soft-limiter to prevent clipping.
- **Rich External & Embedded Subtitles:** Load `.srt`, `.vtt`, `.ass`, `.ssa`, `.ttml` from local storage or remote URLs, with customizable font size, text color, and background presets persisted via DataStore (plus automatic 1.35× TV legibility boost).
- **YouTube-Style "Stats for Nerds" Overlay:** Live `HTTP/3` / `HTTP/2` transport pill, connection speed, network activity & buffer health sparklines, allocator buffer usage vs cap, JVM heap telemetry, rebuffer count/duration, dropped frames, active CDN node & `RAM Direct` / Edge cache status, and active video decoder tag (`HW` vs `SW · FFmpeg`).
- **Zero-OOM Engineering for 32-bit Android 9 TVs:** Heap-bounded `LoadControl` (20–45s buffer window strictly capped at 12–40 MB, 2.5s start / 6s post-rebuffer cushion, 0–5s back-buffer), Stereo 2-ch audio priority on TV (eliminates 7.1 SW AAC underruns), faster TV ABR downshifts (`10s` decrease, `0.70f`, `1080p / 9.5 Mbps` cap), immediate `allocator.trim()` + `clearVideoSurface()`, RGB_565 Coil caching, and `onTrimMemory` socket/DNS/bitmap eviction.
- **Full D-Pad & Remote Control Support:** Leanback launcher icon & banner, animated `tvFocusHighlight` borders, D-pad scrubbing, remote shortcuts, and zero-touch dead-end navigation across every dialog and sheet.

---

## Documentation Index

| Document | Description |
| :--- | :--- |
| [**Architecture & Codebase Guide**](docs/ARCHITECTURE.md) | End-to-end MVVM architecture, `StreamingNetworkEngine` request pipeline, state-frequency isolation, package/file inventory, Room/DataStore persistence, and resource lifecycle. |
| [**Decoder & Playback Engine**](docs/DECODER_AND_PLAYBACK_ENGINE.md) | Media3 `1.11.1` + Play Services Cronet HTTP/3 + NextLib FFmpeg `1.11.1-0.16.0` internals, `FastDnsResolver`, `FastStreamingLoadErrorHandlingPolicy`, `SmartMediaCodecSelector`, `.low_latency` filtering, HW↔FFmpeg surface handoff, 5-layer error recovery, HLS TS flags, and 200% audio boost DSP. |
| [**JSON Playlists, Series & In-App CDN Guide**](docs/PLAYLIST_AND_CDN_GUIDE.md) | Complete JSON playlist schema reference (Movies, Series, Seasons, Episodes, Mirrors, Headers, Subtitles), M3U IPTV format, IMDb enrichment, Multi-CDN failover, origin pre-warming, and TV RAM-Direct vs Mobile edge disk caching. |
| [**Memory Management & Android TV Optimization**](docs/MEMORY_AND_TV_OPTIMIZATION.md) | Deep dive into 32-bit Android 9 TV OOM & buffering fixes, HTTP/3 Cronet on Android 9, TV RAM-Direct eMMC bypass, Stereo DSP priority, heap-capped buffering, surface reclamation, Coil RGB_565 optimization, ABI/APK compression, and Android TV D-pad ergonomics. |
| [**Testing & Troubleshooting Playbook**](docs/TESTING_AND_TROUBLESHOOTING.md) | Running all 41 unit & Robolectric tests, reading Stats for Nerds diagnostics (`HTTP/3`, `RAM Direct`, `Decoder`, `Buffer Health`), and step-by-step troubleshooting for Android 9 TV Wi-Fi buffering, Qualcomm HEVC, 10-bit HDR10, HLS, and CDN issues. |

---

## Supported Formats, Protocols & Codecs

| Category | Supported Formats / Protocols / Codecs | Engine |
| :--- | :--- | :--- |
| **Network Transports** | **HTTP/3 (QUIC + TLS 1.3 + BBR + 0-RTT)**, **HTTP/2 Multiplexed**, HTTP/1.1 Fallback, IPv4-First Cached DNS | **Play Services Cronet `18.1.1`** + **OkHttp `4.10.0`** |
| **Streaming Protocols** | HLS (`.m3u8` TS & fMP4/CMAF), DASH (`.mpd`), Progressive HTTP/HTTPS | Media3 ExoPlayer `1.11.1` |
| **Containers** | MP4, MKV (Matroska), WebM, MPEG-TS, FLV, OGG, Local `content://` / `file://` | Media3 Extractors + Tolerant TS |
| **Video (Hardware)** | H.264/AVC, HEVC/H.265 (8-bit & 10-bit HDR10), VP8, VP9, AV1, Dolby Vision (with HEVC fallback) | Android `MediaCodec` (Sync Queueing) |
| **Video (Software Fallback)** | **HEVC/H.265 8-bit & 10-bit Main 10**, H.264, VP8, VP9, **AV1 (`dav1d`)** | **NextLib Native FFmpeg (`FfmpegVideoRenderer`)** + Android SW |
| **Audio** | AAC, MP3, Opus, Vorbis, FLAC, PCM, **AC-3, E-AC-3, DTS, TrueHD, ALAC** | Platform `MediaCodec` + **NextLib FFmpeg (`FfmpegAudioRenderer`)** |
| **Subtitles** | SRT, WebVTT (`.vtt`), ASS/SSA, TTML, Embedded Closed Captions | Media3 `SubtitleView` + `MergingMediaSource` |
| **Playlists** | Unified JSON (Movies + Multi-Season Series), M3U / M3U8 IPTV | `JsonPlaylistParser` & `M3uPlaylistParser` |

---

## Quick Start

### Requirements
- **Android SDK:** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24` (Android 7.0+)
- **JDK:** Java 11+
- **Build Tool:** Gradle (Kotlin DSL)

### Build & Verify
```bash
# Run all 48 unit & Robolectric Compose UI tests
gradle :app:testDebugUnitTest

# Build universal debug APK (arm64-v8a + armeabi-v7a + x86_64)
gradle :app:assembleDebug

# Output APK location
# app/build/outputs/apk/debug/app-debug.apk
# Pre-packaged root artifact: ./cinistream-v19.apk (36 MB)
```

### Installing on Phone or Android TV
1. Transfer **`cinistream-v19.apk`** to your Android phone, tablet, or Android TV.
2. Enable **Install Unknown Apps** for your file manager or sideloading tool.
3. Install `cinistream-v19.apk` (universal build covers both 64-bit phones and 32-bit Android TVs).
4. Optional: load one or more JSON playlist URLs (or the bundled **`eg-playlist.json`**) to test multi-playlist switching, Movies, multi-season Series, IMDb posters, and multi-CDN mirrors immediately.

---

## Controls Reference (Mobile Touch & Android TV Remote)

| Action | Mobile / Tablet Touch | Android TV D-Pad / Remote |
| :--- | :--- | :--- |
| **Show / Hide Controls** | Single tap on video | `DPAD_CENTER` / `OK` / `MENU` |
| **Play / Pause** | Center Play/Pause button | `MEDIA_PLAY_PAUSE` or `OK` on Play button |
| **Seek −10s / +10s** | Double-tap left / right side | `DPAD_LEFT` / `DPAD_RIGHT` (or `MEDIA_REWIND` / `MEDIA_FAST_FORWARD`) |
| **Precision Timeline Scrub** | Drag CinemaSeekBar | Focus SeekBar + hold `DPAD_LEFT` / `DPAD_RIGHT` |
| **Volume Boost (0%–200%)** | Vertical swipe on right side OR Volume button | Focus Volume button → `DPAD_LEFT` / `DPAD_RIGHT` |
| **Decoder / Quality / Audio / Stats** | Tap **Tracks** icon in top bar | Focus **Tracks** icon → select Video Quality, Audio, **Video Decoder Engine**, or **Stats for Nerds** |
| **Subtitles** | Tap **CC** icon | Focus **CC** icon → toggle, load local/URL subtitle, or style font/colors |
| **Next Episode / Series Drawer** | Tap **Next Ep** or **Episodes / Catalog** icon | Focus **Next Ep** or **Playlist** button |
| **Exit Player Safely** | Back button / system back gesture | `BACK` key (automatically stops playback, detaches surface & trims buffers) |

---

## Project Structure

```text
.
├── README.md                          # Project overview, quick start & version history
├── eg-playlist.json                   # Sample unified Movie + Multi-Season Series + CDN playlist
├── cinistream-v19.apk                 # Latest universal Phone + Android TV APK (v1.19, 36 MB)
├── docs/
│   ├── ARCHITECTURE.md                # Full MVVM architecture, request pipeline & state isolation
│   ├── DECODER_AND_PLAYBACK_ENGINE.md # Cronet HTTP/3, Media3 1.11.1 + NextLib FFmpeg 10-bit & recovery
│   ├── PLAYLIST_AND_CDN_GUIDE.md      # JSON/M3U schemas, Series structure, CDN failover & pre-warming
│   ├── MEMORY_AND_TV_OPTIMIZATION.md  # Zero-OOM Android 9 TV engineering, RAM-Direct & D-pad guide
│   └── TESTING_AND_TROUBLESHOOTING.md # 48-test suite, Stats for Nerds guide & issue playbooks
├── gradle/libs.versions.toml          # Version catalog (Media3 1.11.1, Cronet 18.1.1, NextLib 1.11.1-0.16.0)
└── app/src/
    ├── main/java/com/example/
    │   ├── CineStreamApplication.kt   # Async Cronet HTTP/3 init, FastDns + OkHttp pool, RGB_565 Coil & trim hooks
    │   ├── MainActivity.kt            # Edge-to-edge host, Navigation Compose & VIEW intent router
    │   ├── data/                      # Room history DB & DataStore subtitle/decoder/transport/multi-URL prefs
    │   ├── model/                     # StreamFormat, JsonPlaylistItem, SeriesSeason, SeriesEpisode, Tracks
    │   ├── player/                    # CinePlayerManager, ParallelPrefetchEngine, StreamingNetworkEngine,
    │   │                              # DecoderEngine, CdnMirrorResolver, MediaEdgeCache, AudioVolumeBooster,
    │   │                              # JsonPlaylistParser, M3uPlaylistParser, ImdbMetadataClient,
    │   │                              # MediaFormatDetector, PlayerNetworkStats, SeekBarScrubEngine, SubtitleHelper
    │   ├── ui/                        # HomeScreen, PlayerScreen, overlays, sheets, TV focus utils & theme
    │   └── viewmodel/                 # CineStreamViewModel & MultiJsonPlaylistReducer (multi-catalog state)
    └── test/java/com/example/         # 11 JVM unit & Robolectric Compose UI test suites (48 tests)
```

---

## Version History Highlights

- **v1.19 (`cinistream-v19.apk`, 36 MB):** Added full **Multi-JSON Playlist** support across Phone and Android TV: persist multiple remote JSON playlist URLs in DataStore (with automatic upgrade migration from v1.18's single saved URL) and load local JSON files alongside them; concurrent bounded startup loading (silent per-URL failure tolerance so one offline feed never blocks others); collision-free item ID namespacing (`MultiJsonPlaylistReducer`) so combined `"All"` catalogs never collide; TV-focusable **`All (N)` + individual playlist switcher chips** in both `HomeScreen` and `JsonPlaylistSheet` (with source badges in `"All"` view); inline **`+` Add JSON URL** controls in both the Home launcher card and `JsonPlaylistSheet`, per-playlist or Refresh-All syncing, individual playlist removal, and cross-playlist Series/Next-Episode resolution.
- **v1.18 (`cinistream-v18.apk`, 36 MB):** Upgraded `ParallelPrefetchEngine` with **live progressive streaming handoff (`StreamingPrefetchEntry`)** so ExoPlayer attaches mid-download at 0 ms and reads 32 KB chunks live (eliminating blocking waits and the 6 MB VBR segment discard cliff); raised single-segment ceilings to **12 MB (TV) / 16 MB (Mobile)**; added **foreground-first anti-starvation** (`1×` lookahead during seeks/low buffer `< 8s`, focused `2×` TV / `3×` Mobile steady-state) and a **dedicated non-blocking audio prefetch lane**; fixed `QuicThroughputWatchdog` to ignore `1 ms` RAM prefetch hits and monitor pure `BandwidthMeter` samples + rebuffer stalls (`>= 2` stalls at `< 2.5 Mbps`) with immediate HTTP/2 IPv4 TCP socket reconnection; tightened TV `Stats for Nerds` vertical spacing so it never overlaps the bottom-left seekbar timestamp when controls are open.
- **v1.17 (`cinistream-v17.apk`, 36 MB):** Added `ParallelPrefetchEngine` (`HlsSegmentIndex`, `ParallelHlsPrefetchManager`, `ParallelPrefetchDataSource`) to download **3× parallel HLS segments on Android TV** (`<= 12 MB` consume-once RAM cap) and **4× on Mobile** (`<= 24 MB` cap), plus **2–3× parallel `206 Content-Range` lookahead** for progressive `.mp4`/`.mkv`/`.webm` streams, multiplying cold Cloudflare/S3 origin throughput 2×–4× while staying 100% OOM-proof; raised OkHttp `Dispatcher` (`maxRequests = 32, maxRequestsPerHost = 12`) and `ConnectionPool(12, 5m)`; added `QuicThroughputWatchdog` (auto-demotes Cronet HTTP/3 to HTTP/2 TCP after 3 slow `< 2 Mbps` segments or QUIC I/O errors when ISPs throttle UDP-443); added persistent 1-tap **Network Engine (`Auto` / `HTTP/2` / `HTTP/3`)** selector in `TracksSheet`; added inline `3×/4× Prefetch (hit%)` telemetry in Stats for Nerds and OS `onTrimMemory` prefetch RAM eviction.
- **v1.16 (`cinistream-v16.apk`, 36 MB):** Added `StreamingNetworkEngine` with asynchronous **Google Play Services Cronet (`HTTP/3 QUIC + TLS 1.3 + BoringSSL + BBR + 0-RTT`)** and automatic zero-crash fallback to HTTP/2 OkHttp (strictly excluding slow Java HTTP/1.1 Cronet fallback); added 5-minute IPv4-first `FastDnsResolver`; switched constrained Android TVs to **RAM-Direct mode** (eliminating slow eMMC `SimpleCache` flash-write stalls on ExoPlayer's loader thread) while upgrading mobile disk edge cache writes to `128 KB` buffers; added smart Wi-Fi/Ethernet/5G initial bitrate estimates (`6 Mbps` TV / `10 Mbps` phone) + debounced `HEAD` origin pre-warming; added `FastStreamingLoadErrorHandlingPolicy` (`500 ms` initial transient retry + fail-fast 4xx Multi-CDN failover); added compact `HTTP/3` / `HTTP/2` transport pill and `RAM Direct` telemetry in Stats for Nerds.
- **v1.15 (`cinistream-v15.apk`, 34 MB):** Switched Media3 streaming transport to HTTP/2 multiplexed `OkHttpDataSource` (fixing 3.95 Mbps Android 9 TV HTTP/1.1 bottleneck on demuxed fMP4 HLS), prioritized 2-channel Stereo hardware DSP audio on Android TV (eliminating 7.1 SW AAC `AudioTrack` underruns), tuned faster TV ABR downshifts (`0.70f` bandwidth fraction, 10s decrease window, 9.5 Mbps 1080p TV cap), and upgraded the heap-capped `LoadControl` to 20–45s / 12–40 MB / 2.5s play / 6s rebuffer / 0–5s back-buffer.
- **v1.14 (`cinistream-v14.apk`, 34 MB):** Upgraded Media3 to `1.11.1`, integrated **NextLib native FFmpeg (`1.11.1-0.16.0`)** for **10-bit HEVC Main 10**, AV1 (`dav1d`), and DTS/AC-3/TrueHD decoding; blocked `.low_latency` Qualcomm WebRTC codecs; added clean `surfaceResetToken` surface handoff; added deterministic `allocator.trim()` + `clearVideoSurface()` + OkHttp pool eviction; compressed universal APK to 34 MB.
- **v1.13 (`cinistream-v13.apk`, 23 MB):** Added tolerant HLS HEVC TS extraction (`FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS`), synchronous MediaCodec queueing (`forceDisableMediaCodecAsynchronousQueueing()`), automatic edge-cache bypass on decode errors, bidirectional HW↔SW + H.264/lower-res track fallback, and `Skip Bad Segment (+5s)`.
- **v1.12 (`cinistream-v12.apk`):** Introduced `DecoderEngine` (`VideoDecoderMode`, `SmartMediaCodecSelector`), Qualcomm `c2.qti.hevc.decoder` detection & automatic SW failover, Dolby Vision → HEVC fallback, Tracks decoder mode selector, and active Decoder row in Stats for Nerds.
- **v1.11 (`cinistream-v11.apk`):** Added unified Movies + Multi-Season Series JSON playlists (`SeriesDetailSheet`, `Next Ep`, auto-advance) and 3-layer hybrid in-app CDN engine (`CdnMirrorResolver`, `MediaEdgeCache`, custom HTTP headers).
- **v1.10 (`cinistream-v10.apk`):** Added YouTube-style **Stats for Nerds** telemetry with sparklines, removed Gemini dependencies cleanly, and hardened 32-bit Android 9 TV memory safety (Zero-OOM `LoadControl`, `largeHeap`, RGB_565 Coil, isolated `PlaybackProgressState`).
