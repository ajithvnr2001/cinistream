# CineStream — Universal Cinema Media Player for Android Mobile & Android TV

**CineStream** (`com.aistudio.cinestream.vqxkpm`, **v1.39 / versionCode 39**) is a high-performance, zero-OOM Android video streaming and local media player built with **Kotlin**, **Jetpack Compose (Material 3)**, **AndroidX Media3 (`1.11.1`)**, **Google Play Services Cronet (`18.1.1`, HTTP/3 QUIC + TLS 1.3)**, **NextLib native FFmpeg (`1.11.1-0.16.0`)**, and **embedded WireGuard (`com.wireguard.android:tunnel`, `GoBackend`)**.

It ships as a compact **13.62 MB** R8-shrunk ARM APK (**`cinistream-v39.apk`**) covering modern 64-bit phones and tablets (`arm64-v8a`) and 32-bit Android TVs (`armeabi-v7a`, including 1 GB RAM Android 9 TVs), alongside **`eg-playlist-v3.json`** demonstrating automatic external audio URLs.

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
- **Unified Multi-JSON Movie, Collection & Series Catalogs (`MultiJsonPlaylistReducer`, `JsonPlaylistParser`):** Persist multiple JSON playlist URLs + local files simultaneously, switch via `"All"` or per-playlist chips, organize franchises into first-class **Movie Collections** (`type: "collection"` or flat `"collection"` tags with per-movie IMDb auto-fetch in `CollectionDetailSheet`), auto-load per-item **external audio URLs** (`audios`, `audioTracks`, `audio`, `audioUrl` with optional `label`, `language`, `default`, and `delayMs` — see **`eg-playlist-v3.json`**), and fail over across per-item `mirrors` and path-preserving `cdnMirrors` with inherited HTTP headers.
- **Continue Watching, Watchlist & Global Search (Room v4):** Deduplicated per-series Continue Watching rail, per-episode and per-collection-film resume + `Watched` badges (preserving playlist external audio URLs on resume), poster-backed Watchlist, and instant search across Watchlist, Catalogs (including individual collection films), Live TV, Downloads, and History.
- **Local, URL & Dual Subtitles (`SubtitleMediaSourceBuilder`, `SecondarySubtitleEngine`):** Load `.srt`, `.vtt`, `.ass`, `.ssa`, and `.ttml` sidecar captions from device storage or URLs—with Media3 `1.11.1` extraction-time transcoding (`ProgressiveMediaSource` + `SubtitleExtractor` → `application/x-media3-cues`) preventing HLS `Legacy decoding is disabled` crashes—plus simultaneous top-screen secondary `.srt`/`.vtt` rendering (`SecondarySubtitleEngine`), direct `ms`/`s` numeric sync offset (`-10s..+10s`), and appearance customization.
- **External Audio URLs, Unbounded Audio Sync & Playback Comfort:** Attach remote HLS `.m3u8` (audio-only or muxed master—foreign video/text tracks stripped automatically), DASH `.mpd`, or direct HTTP audio URLs manually via **Tracks → Audio → + Add Audio URL**, via **Phone-to-TV Web Remote (`load_audio`)**, or automatically from **JSON playlists (`eg-playlist-v3.json`)**. Sync both external and built-in audio with **unbounded custom delay (`±60 min` / `±3,600,000 ms`)** using coarse (`±1s`/`±5s`), fine (`±25ms`/`±50ms`), or direct `ms`/`s`/`m` numeric input—even across mismatched video/audio durations (`MergingMediaSource(adjustPeriodTimeOffsets = true, clipDurations = true)` + `ExternalAudioSyncMediaSource`). Also includes `0%–200%` (+20 dB) Volume Boost, Night Mode dialogue boost DSP, direct `ms`/`s` Subtitle Sync, Sleep Timer (`15m/30m/45m/60m/End of Ep` with 5s fade-out), Up-Next 10s countdown, Skip Intro (`+85s`), Hold-to-2× speed, Configurable Skip Interval (`5s/10s/15s/30s`), Chapter Markers & A-B Loop, Mobile PiP & Background Audio (`MediaPlaybackService`), and touch brightness/volume/pinch-to-zoom (`1.0×–2.5×`) gestures.
- **Live TV XMLTV EPG & Channel Zapping:** Streams `.xml`/`.xml.gz` XMLTV guides (auto-detected from `#EXTM3U url-tvg`), renders Now/Next progress bars, and supports `CH-`/`CH+` and TV remote `ChannelUp`/`ChannelDown` zapping.
- **Multi-Account Cloud WebDAV (TorBox / Nextcloud / NAS), Jellyfin, Web Remote & High-Speed Resumable Offline Downloads (with External Audio Bundling):** Save, test, edit, and 1-tap switch unlimited **WebDAV** accounts—even multiple accounts on the same server URL (with a 1-tap **TorBox WebDAV** `https://webdav.torbox.app/` preset)—encrypting credentials via **Android Keystore AES-256-GCM** in Room v4, preserving special-character passwords byte-for-byte, stripping `Authorization` on cross-origin CDN redirects, bypassing unencrypted disk cache for authenticated streams, and auto-attaching sibling `.srt`/`.vtt`/`.ass` subtitles during direct playback. Also includes LAN Jellyfin streaming, Phone-to-TV Web Remote (`LocalWebRemoteServer` supporting stream, playlist, external audio, subtitle, EPG, and NordVPN key pushes), credential-free Wi-Fi/SAF sync (`DeviceSyncCodec`), and an **8× parallel HLS / 4× multi-chunk progressive resumable offline downloader** (`OfflineDownloadManager`) with automatic Wi-Fi ↔ Cellular recovery, a **Multi-Quality × Multi-Audio picker** (`DownloadQualitySheet`) displaying both embedded and **`Loaded`** external audio tracks (`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`, and external HLS `.m3u8`) with estimated combined sizes, and automatic offline re-attachment with saved `delayMs` via `offline_audio.json`.

---

## Documentation Index

| Document | Description |
| :--- | :--- |
| [**Architecture & Codebase Guide**](docs/ARCHITECTURE.md) | Complete v1.39 MVVM architecture, end-to-end streaming/prefetch/external-audio/offline-sidecar/VPN pipeline, full module inventory (27 player engines, 17 player UI sheets), Room v4 + Keystore persistence, state-frequency isolation, and UI design tokens. |
| [**Decoder, Transport & Playback Engine**](docs/DECODER_AND_PLAYBACK_ENGINE.md) | Media3 `1.11.1`, Play Services Cronet HTTP/3 + `QuicThroughputWatchdog`, `ParallelPrefetchEngine`, `NordLynxEngine` WireGuard + DoH, NextLib FFmpeg 10-bit, `FrameDropAnalyzer`, `AudioSyncMediaSource`, `ExternalAudioMediaSource` + `PlaylistAudioResolver` (unbounded `±60 min` sync & muxed `.m3u8` audio stripping), Night Mode DSP, HLS subtitle transcoding, and 8×/4× resumable offline downloads with external audio bundling (`MP3`/`AC3`/`FLAC`/`WAV`/`M4A`/`HLS`). |
| [**Playlists, Live TV EPG, WebDAV & CDN Guide**](docs/PLAYLIST_AND_CDN_GUIDE.md) | Multi-JSON Movies, **Movie Collections (`eg-playlist-v2.json`)**, **Automatic External Audio URLs (`eg-playlist-v3.json`)** (online playback + mobile offline downloads), complete field alias reference, per-movie IMDb enrichment, 5-layer in-app CDN, M3U + XMLTV EPG format, Multi-Account WebDAV (TorBox/NAS) & Jellyfin streaming, and Phone-to-TV Web Remote / Wi-Fi Sync API. |
| [**Memory Management & Android TV Optimization**](docs/MEMORY_AND_TV_OPTIMIZATION.md) | Zero-OOM engineering for 32-bit Android 9 TVs, bounded prefetch & heap budgets, TV RAM-Direct mode, **13.62 MB** combined ARM64+ARMv7 R8 sideload APK sizing (`cinistream-v39.apk`), and 10-foot TV ergonomics. |
| [**Testing & Troubleshooting Playbook**](docs/TESTING_AND_TROUBLESHOOTING.md) | Running all **150 unit & Robolectric tests across 22 suites**, interpreting every Stats for Nerds indicator, and troubleshooting Wi-Fi buffering, QUIC throttling, frame drops, HEVC 10-bit, HLS subtitles, external audio URLs & multi-minute sync, JSON playlist audio auto-loading, offline downloads with loaded audio, VPN, and WebDAV connections. |

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
| **External Audio (Online & Offline)** | Remote & offline-bundled HLS `.m3u8` (audio-only & muxed master), DASH `.mpd`, and direct HTTP audio (**`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`**, `.aac`, `.dts`, `.opus`, `.ogg`, `.mka`, `.weba`) via **Tracks**, **Web Remote**, **JSON Playlist**, or **Offline Download Sidecar (`offline_audio.json`)** | `ExternalAudioUrlInspector` + `AudioOnlyFilteringMediaSource` + `ExternalAudioSyncMediaSource` + `PlaylistAudioResolver` + `OfflineDownloadManager` |
| **Subtitles** | SRT, WebVTT (`.vtt`), ASS/SSA, TTML, Embedded CC, Dual Subtitles | `SubtitleMediaSourceBuilder` (`x-media3-cues`) + `SecondarySubtitleEngine` |
| **Playlists & Guides** | Multi-JSON (Movies, **Collections**, Multi-Season Series, **External Audio URLs**), M3U / M3U8 IPTV, XMLTV EPG (`.xml`/`.xml.gz`) | `JsonPlaylistParser`, `M3uPlaylistParser`, `XmltvEpgParser` |

---

## Quick Start

### Requirements
- **Android SDK:** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24` (Android 7.0+)
- **JDK:** Java 17
- **Build Tool:** Gradle (Kotlin DSL)

### Build & Verify
```bash
# Run all 150 unit & Robolectric Compose UI tests across 22 suites
gradle :app:testDebugUnitTest

# Build compact combined ARM64 + ARMv7 R8-shrunk sideload APK (13.62 MB)
gradle :app:assembleSideload -x lintVitalSideload

# Pre-packaged root APK (v1.39, arm64-v8a + armeabi-v7a, 13.62 MB) & sample v3 playlist:
#   ./cinistream-v39.apk
#   ./eg-playlist-v3.json
```

### Installing on Phone or Android TV
1. Sideload **`cinistream-v39.apk`**, or build via AI Studio **Settings → Generate APK** if injecting `NORDVPN_ACCESS_TOKEN` via the AI Studio Secrets panel.
2. Enable **Install Unknown Apps** for your file manager or sideloader and install.
3. **Optional — DNS & NordVPN:** Open **DNS & VPN** from the Home header to enable Cloudflare `1.1.1.1` DoH, connect any of **118+ NordVPN countries/cities** via embedded WireGuard NordLynx, or enable **Auto-VPN Failover**.

---

## Controls Reference (Mobile Touch & Android TV Remote)

| Action | Mobile / Tablet Touch | Android TV D-Pad / Remote |
| :--- | :--- | :--- |
| **Show / Hide Controls** | Single tap on video | `DPAD_CENTER` / `OK` / `MENU` |
| **Play / Pause** | Center Play/Pause button | `MEDIA_PLAY_PAUSE` or `OK` on Play button |
| **Seek −10s / +10s** *(configurable 5–30s)* | Double-tap left / right side | `DPAD_LEFT` / `DPAD_RIGHT` or `MEDIA_REWIND` / `MEDIA_FAST_FORWARD` |
| **Hold 2.0× Speed** | Press and hold anywhere on video | Speed menu in player bar |
| **Precision Timeline Scrub** | Drag `CinemaSeekBar` or tap `-1m`/`+1m` | Focus SeekBar + hold `DPAD_LEFT` / `DPAD_RIGHT` |
| **Brightness / Volume / Zoom** | Left vertical swipe (vertical brightness bar), Right vertical swipe (vertical 0–200% volume bar), Pinch (`1.0×–2.5×`) | Focus **Brightness** pill next to **Sub** (`−`/`+` 10%–150% dim/boost & presets); Focus Volume pill → `DPAD_LEFT` / `DPAD_RIGHT` |
| **Live TV Channel Zap** | `CH-` / `CH+` buttons in top bar | Remote `CHANNEL_UP` / `CHANNEL_DOWN` keys |
| **Playback, Tracks & Audio DSP** | Tap **Tracks** icon | Focus **Tracks** → Quality, Audio, Decoder, Transport, Delay, Night Mode, Sleep Timer, A-B Loop, Stats |
| **Subtitles & Dual Captions** | Tap **CC** icon | Focus **CC** → Load File/URL, Tracks, Dual Subtitles, Sync Delay, Caption Style |
| **Exit Player Safely** | Back button / gesture | `BACK` key (saves position, clears surface & trims buffers) |

---

## Project Structure

```text
.
├── README.md                          # Project overview, quick start & complete version history
├── cinistream-v39.apk                 # Compact combined ARM64 + ARMv7 Phone & Android TV APK (v1.39, 13.62 MB)
├── eg-playlist.json                   # Sample unified Movie + Multi-Season Series + CDN playlist
├── eg-playlist-v2.json                # Sample Movies + Movie Collections (nested & flat-grouped) + Series playlist
├── eg-playlist-v3.json                # Sample v3 playlist with auto-loaded External Audio URLs (Movies, Collections & Series)
├── .env.example                       # Optional secret placeholder (NORDVPN_ACCESS_TOKEN)
├── docs/
│   ├── ARCHITECTURE.md                # Full v1.39 MVVM architecture, pipeline & complete module inventory
│   ├── DECODER_AND_PLAYBACK_ENGINE.md # Cronet HTTP/3, ParallelPrefetch, NordLynx, FFmpeg 10-bit, AudioSync, ExternalAudio & Downloads
│   ├── PLAYLIST_AND_CDN_GUIDE.md      # Multi-JSON (v1–v3), Collections, Auto External Audio, M3U/XMLTV, WebDAV/Jellyfin, Web Remote & CDN
│   ├── MEMORY_AND_TV_OPTIMIZATION.md  # Zero-OOM Android 9 TV engineering, 13.62 MB R8 ARM APK sizing & 10-foot UI
│   └── TESTING_AND_TROUBLESHOOTING.md # 150-test suite (22 files), Stats for Nerds reference & issue playbooks
├── gradle/libs.versions.toml          # Version catalog (Media3 1.11.1, Cronet 18.1.1, NextLib, WireGuard, Room)
└── app/src/
    ├── main/
    │   ├── res/font/                  # Bundled Fraunces, DM Sans & JetBrains Mono (.ttf)
    │   ├── res/values/strings.xml     # Zero-slop UI strings
    │   └── java/com/example/
    │       ├── CineStreamApplication.kt   # Async Cronet HTTP/3, FastDns/DoH + OkHttp pool, RGB_565 Coil, onTrimMemory
    │       ├── MainActivity.kt            # Edge-to-edge host, Navigation Compose, PiP, VPN consent & VIEW intents
    │       ├── data/                      # Room v4 DB (History, Favorites, Downloads, Multi-Account WebDAV/NAS), DataStore & DeviceSyncCodec
    │       ├── model/                     # MediaModels (incl. CollectionMovie, ExternalAudioTrack, PlaylistAudioSpec) & V24Models
    │       ├── player/                    # 27 playback, transport, prefetch, VPN, decoder, AudioSync, ExternalAudio, DSP, subtitle, download & WebDAV engines
    │       ├── remote/                    # LocalWebRemoteServer (LAN HTTP + Sync + load_audio) & QrCodeMatrixGenerator
    │       ├── ui/                        # 35mm Screening Room theme, Home, Player, 17 sheets/overlays & TV utilities
    │       └── viewmodel/                 # CineStreamViewModel, MultiJsonPlaylistState, NetworkPrivacyState, V24UiStates
    └── test/java/com/example/             # 22 JVM unit & Robolectric Compose UI test suites (150 tests)
```

---

## Version History Highlights

- **v1.39 (`cinistream-v39.apk`, 13.62 MB):** **Mobile Offline Download Support for Loaded External Audio (`.mp3`, `.ac3`, `.flac`, `.wav`, `.m4a` & HLS `.m3u8`)**.
  - Fixed mobile **Download Options (`DownloadQualitySheet`)** so all loaded external audio tracks (from v3 JSON playlists, Tracks → Audio, or Web Remote) always appear in the Audio Track filter bar and quality rows with a cyan **`Loaded`** pill—for multi-variant HLS, single-variant HLS, and progressive MP4/MKV/WebM streams alike—using a wrapping `FlowRow` layout so badges never clip on narrow screens.
  - Added resumable offline external audio bundling in **`OfflineDownloadManager`**: downloads direct **`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`** (plus `.aac`, `.opus`, `.ogg`, `.dts`, `.mka`, `.weba`) and external HLS `.m3u8` (master & audio-only media playlists) into `<download_folder>/ext_audio/` with an atomic `offline_audio.json` sidecar storing label, language, format badge, and saved `delayMs` (zero Room schema changes).
  - Expanded **`ExternalAudioUrlInspector`** `Content-Type` sniffing for `.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, and `.m4a`, and wired **`CineStreamViewModel`** (`playOfflineDownload` & `playHistoryItem`) to automatically re-attach saved offline external audio + sync offset on playback, verified by **150/150** unit & Robolectric tests across 22 suites.
- **v1.38 (`cinistream-v38.apk`, 13.57 MB):** **Automatic External Audio URLs in JSON Playlists & `eg-playlist-v3.json`**.
  - Added `PlaylistAudioSpec` across `JsonPlaylistItem`, `CollectionMovie`, and `SeriesEpisode` plus full extraction in `JsonPlaylistParser` supporting multi-track arrays (`audios`, `audioTracks`, `audio_tracks`, `externalAudios`, `audioUrls`), single object shorthands (`audio`, `externalAudio`), and single URL shorthands (`audioUrl`, `dubUrl` with optional `audioLabel`, `audioLang`, `audioDefault`, and `audioDelayMs`).
  - Added **`PlaylistAudioResolver`** in `ExternalAudioMediaSource.kt` and wired automatic attachment into `CineStreamViewModel` (`playJsonPlaylistItem`, `playCollectionMovie`, `playSeriesEpisode`, `playHistoryItem` Continue Watching resume, `resumeActiveSession`, and `GlobalSearchEngine` collection-film search) — auto-selecting `"default": true` (or the first external audio URL unless all explicitly set `"default": false`), applying any item-defined initial `delayMs` when no user override exists in Room, preserving surviving tracks during load-failure recovery, and displaying cyan **`AUDIO` / `N AUDIO`** badges in `JsonPlaylistSheet`, `CollectionDetailSheet`, and `SeriesDetailSheet`.
  - Shipped **`eg-playlist-v3.json`** demonstrating automatic external `.m3u8` audio URLs across standalone movies, collection films, and series episodes, plus expanded `ExternalAudioUrlSyncTest.kt` (**146/146** tests across 22 suites).
- **v1.37 (`cinistream-v37.apk`, 13.57 MB):** **Custom External Audio URLs (HLS `.m3u8` / Direct), Unbounded Audio Sync (`±60 min`), & Web Remote Audio Push**.
  - Added **"+ Add Audio URL"** inside **Tracks → Audio** (`ExternalAudioUrlInspector` + `AudioOnlyFilteringMediaSource` + `MergingMediaSource(adjustPeriodTimeOffsets = true, clipDurations = true)`) supporting audio-only `.m3u8` playlists, muxed HLS master `.m3u8` streams (automatically stripping foreign video/text tracks), DASH `.mpd`, and direct audio URLs (`.aac`, `.m4a`, `.mp3`, `.ac3`, `.eac3`, `.dts`, `.opus`, `.flac`, `.ogg`, `.wav`, `.mka`) with extensionless `#EXTM3U` sniffing, auto-selection, `[HLS]`/`[EXT]` badges, one-tap removal, and automatic fallback to original audio on network failure.
  - Added **`ExternalAudioSyncMediaSource`** + expanded **`AudioDelayMath`** (`±60 min` practical bound, `-5s`/`-1s`/`+1s`/`+5s` + `±25ms`/`±50ms` steppers, and custom `ms`/`s`/`m` numeric input) so external audio syncs independently (`seekToUs = pos - delayUs`) without jumping the video frame even across multi-minute offsets or mismatched durations.
  - Added **`LOAD_AUDIO`** (`Attach External Audio URL (.m3u8 / Audio)`) to the PIN-protected Phone-to-TV **`LocalWebRemoteServer`**, plus `ExternalAudioUrlSyncTest.kt`.
- **v1.36 (`cinistream-v36.apk`, 13.60 MB):** **True Movie Collections UI with Per-Movie IMDb Auto-Fetch & `eg-playlist-v2.json`**.
  - Added first-class **`MediaContentType.COLLECTION`** + `CollectionMovie` support in `JsonPlaylistParser`, accepting both dedicated `"type": "collection"` (`"movies": [...]`) entries and flat `"type": "movie"` items tagged with `"collection"` (auto-grouped and sorted by `"part"`/`"year"`).
  - Added **`CollectionDetailSheet`** (zero `S1 E1`/`E1/E2` labels) displaying stacked collection artwork, `"COLLECTION · N FILMS"` badges, a smart **"Play / Resume Next"** CTA, individual per-movie IMDb posters/ratings/years/runtimes, watched/resume tracking, per-film offline downloads, and **Films / Next Film** player controls.
  - Upgraded `ImdbMetadataClient` & `CineStreamViewModel` to auto-fetch IMDb posters, ratings, years, and synopses for **every individual movie inside a Collection**, backfilling the collection cover and average rating live, plus added a **Collections** filter chip in `JsonPlaylistSheet`, child-film matching in `GlobalSearchEngine`, and **`eg-playlist-v2.json`** showcasing *Resident Evil*, *Final Destination*, and *Evil Dead* collections.
- **v1.35 (`cinistream-v35.apk`, 13.58 MB):** **Multi-Audio × Multi-Quality Offline Download Picker with Per-Combination Size Estimates**.
  - Expands HLS master playlists (`#EXT-X-STREAM-INF` × `#EXT-X-MEDIA:TYPE=AUDIO`, including muxed and external audio tracks) into every selectable **(Video Quality × Audio Track)** combination—even for single-resolution multi-audio streams—and automatically probes `#EXTINF` duration when runtime duration is unavailable so every combination displays accurate estimated sizes (including 5.1 surround vs. 2.0 stereo audio bitrate differences).
  - Upgraded `DownloadQualitySheet` to **"Choose Quality & Audio"** with horizontal audio track filter chips (`All`, language/channel tracks), audio pill badges, and monospace size badges; safely probes extensionless HLS URLs via bounded 512 KB peeks; persists the exact chosen audio track in `download_state.json`; and downloads only the selected video + chosen audio track.
- **v1.34 (`cinistream-v34.apk`, 13.58 MB):** **Resumable Offline Downloads, Seamless Network-Off & Wi-Fi ↔ Mobile Recovery, and Multi-Quality Download Picker**.
  - **True Checkpoint Resume (HLS & Multi-Chunk Progressive):** Persists per-download progress (`download_state.json`) and writes HLS segments atomically via `.part` files so completed segments and `206` progressive byte ranges are never wiped or re-downloaded from 0% after network loss, pause, or app restart.
  - **Automatic Network-Off & Wi-Fi ↔ Mobile Switch Recovery:** Uses `ConnectivityManager.registerDefaultNetworkCallback` to evict stale sockets across Wi-Fi ↔ Cellular handovers, auto-pause cleanly into `PAUSED` (*"Paused · Waiting for network"*) when offline while preserving all saved progress, and auto-resume from the exact saved segment/byte once connectivity returns—plus manual **Pause** and **Resume** buttons in `DownloadsSheet`, `TracksSheet`, and the mobile player top bar.
  - **Multi-Quality HLS Download Picker (`DownloadQualitySheet`):** Probes HLS master playlists for all `#EXT-X-STREAM-INF` resolutions, bitrates, audio tracks, and estimated file sizes, prompting the user to pick their preferred quality whenever 2+ variants exist while starting single-quality/progressive files immediately.
- **v1.33 (`cinistream-v33.apk`, 13.55 MB):** **Mobile Player Download Visibility Fix & High-Speed Parallel Offline Downloader (8× HLS + 4× Multi-Chunk MP4/MKV)**.
  - Redesigned the mobile `PlayerTopBar` into a zero-overflow 2-row header with a scrollable quick-action strip placing the **Download** pill (`Download` / `XX%` / `Queued` / `Saved` / `Retry`) right next to **Sub** so it is always visible on 360dp portrait and landscape phones without clipping off-screen, plus added an **Offline Download** card inside `TracksSheet` and 1-tap Download icons on playable files inside `LocalNetworkSheet` (WebDAV/Jellyfin).
  - Upgraded `OfflineDownloadManager` from single-threaded sequential downloads to an **8-way parallel HLS segment engine** (supporting up to 4,000 segments, automatic per-segment retries, AES-128 keys, `#EXT-X-MAP`, `#EXT-X-BYTERANGE`, and separate `#EXT-X-MEDIA:TYPE=AUDIO` tracks) and a **4-way parallel `206 Partial Content` multi-chunk progressive engine** for MP4/MKV/WebDAV files with cross-origin `Authorization` redirect stripping and live **MB/s** speed telemetry in `DownloadsSheet` and `TracksSheet`.
- **v1.32 (`cinistream-v32.apk`):** **Multi-Account Cloud WebDAV (TorBox, Nextcloud, NAS) & Direct Authenticated Playback**.
  - Migrated Room to **v4 (`MIGRATION_3_4`)**, removing the unique constraint on `(baseUrl, serverType)` and adding `@Update` so unlimited WebDAV accounts can coexist on the exact same server URL (e.g. multiple TorBox accounts on `https://webdav.torbox.app/`).
  - Hardened `LocalNetworkClient`: RFC 4918 `PROPFIND` manual same-origin redirect handling (preserving `PROPFIND` + `Depth: 1` + XML body across `301`/`302`/`307`/`308` while stripping `Authorization` on cross-origin redirects), byte-for-byte UTF-8 Basic Auth preserving special punctuation and whitespace, safe percent-encoding for spaces/brackets (`[2024]`, `#`, `{}`), XXE-disabled multi-`propstat` XML parsing, full video/audio MIME & extension filtering, sibling `.srt`/`.vtt`/`.ass`/`.ttml` subtitle auto-attachment, and live **Test Connection** diagnostics.
  - Updated `CinePlayerManager` to route `Authorization`-bearing WebDAV streams through `OkHttpDataSource` (stripping `Authorization` cleanly on cross-origin CDN `302` redirects) and bypass unencrypted `MediaEdgeCache` disk storage for authenticated media.
  - Upgraded `LocalNetworkSheet` with a 1-tap **TorBox WebDAV** URL preset, account username badges, inline Edit Account, password visibility toggle, live **Test** button, Quick Account Switcher bar, folder Refresh, instant search filter, and file-size + subtitle badges.
  - Added `MultiAccountWebDavTest.kt` (**114/114** tests passing across 19 suites).
- **v1.31:** **HLS-Segment Bidirectional Audio Sync (`AudioSyncMediaSource`) & Direct `ms`/`s` Numeric Sync Input (`-10s..+10s`)**.
  - Replaced the PCM-only `DelayAudioProcessor` sink chain with **`AudioSyncMediaSource`** (`AudioSyncMediaPeriod` + `AudioOffsetSampleStream`), shifting `C.TRACK_TYPE_AUDIO` sample timestamps directly at the `SampleStream` boundary before decoding. Works identically across **HLS muxed `.ts` segments**, **HLS demuxed `#EXT-X-MEDIA:TYPE=AUDIO` separate audio segments (`.ts`/`.aac`/`.m4s`)**, DASH, MKV, MP4, PCM, FFmpeg, and HDMI AC-3/E-AC-3 passthrough—with zero PCM ring-buffer RAM overhead, zero silence injection, and exact in-buffer seek realignment (`SeekParameters.EXACT` + anchor-locking).
  - Added direct numeric input in **milliseconds (`ms`) or seconds (`s`)** (`SyncOffsetNumericInputRow` with `ms`/`s` unit chips, dedicated `±` sign toggle for phone/TV keyboards, inline suffix parsing, and validation) to both **Audio Delay** (`TracksSheet`) and **Subtitle Delay** (`SubtitleSheet`) across `-10s..+10s`.
  - Added `AudioSyncNumericInputTest.kt` and `HlsAudioSyncSampleStreamTest.kt` (**108/108** tests passing across 18 suites).
- **v1.29:** **Android TV Brightness Control Next to Sub**, **Mobile Vertical Brightness Bar**, & **Dedicated R8 Sideload Build (20 MB)**.
  - Added an Android TV D-Pad **Brightness** icon button (`player_brightness_button`) immediately next to **Sub** in the player top bar, opening a non-dimming floating control popover (`TvBrightnessControlPanel`) with `−`/`+` 10% step buttons, slider, `30%`/`60%`/`100%`/`125%`/`150%` quick presets, and reset. Drives both `WindowManager.LayoutParams.screenBrightness` and a live software dim/boost video overlay so brightness adjusts visibly on all Android TVs and HDMI streaming boxes.
  - Redesigned the mobile left-swipe `BrightnessHudOverlay` from a horizontal progress bar into a vertical gauge (`10dp × 132dp`) matching the mobile `VerticalVolumeBoostHud` sound control.
  - Added a dedicated `sideload` R8-shrunk buildType signed with `debugConfig` (shrinking the universal APK further to **20 MB**) while keeping `debug` unminified for clean incremental builds, plus `BrightnessControlTest.kt` (**99/99** tests passing across 16 suites).
- **v1.28:** **Complete OpenSubtitles Removal**, **Media3 1.11.1 HLS External Subtitle Transcoding Fix**, & **R8 APK Size Optimization**.
  - Removed the OpenSubtitles integration cleanly across networking (`OpenSubtitlesClient.kt` deleted), `.env.example` (`OPENSUBTITLES_API_KEY`), encrypted DataStore keys, `V24Models` / `V24UiStates`, `CineStreamViewModel`, `MainActivity`, `PlayerScreen`, `SubtitleSheet`, strings, and tests—while preserving full local file, URL, sync delay, appearance styling, and Dual Subtitles (`SecondarySubtitleEngine`) workflows.
  - Fixed Media3 `1.11.1` `Legacy decoding is disabled, can't handle application/x-subrip samples` crash when loading external `.srt`/`.vtt`/`.ass`/`.ttml` subtitles on HLS streams (`MergingMediaSource`) via `SubtitleMediaSourceBuilder` (`ProgressiveMediaSource` + `SubtitleExtractor` extraction-time transcoding to `application/x-media3-cues`) and enabled `.experimentalParseSubtitlesDuringExtraction(true)` on `DefaultHlsExtractorFactory`.
  - Enabled R8 code and resource shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`, tuned `app/proguard-rules.pro`), shrinking the universal signed APK from 36 MB to a compact **25 MB** for fast sideloading and workspace persistence.
  - Dynamically renders `BuildConfig.VERSION_NAME` in `HomeScreen` and adds `HlsSubtitleTranscodingTest.kt` (**94/94** tests passing across 15 suites).
- **v1.25:** Complete **"No-AI-Slop" Editorial Cinema UI Revamp (35mm Screening Room)**—replaced cold slate colors with `#09090B`/`#121215`/`#1A1A1F` warm carbon matte surfaces, warm ivory ink (`#F4F1EA`), projector tungsten gold (`#E8A435`), bundled Fraunces + DM Sans + JetBrains Mono typography, unboxed Home Stream Dock, poster-backed Watchlist, and smoked-glass player HUD overlays.
- **v1.24:** Shipped end-to-end features across Phone and Android TV: Phone-to-TV Web Remote & QR Link Sender, Local Wi-Fi Sync & SAF JSON Backup, Up-Next 10s Episode Countdown + Skip Intro (`+85s`), Sleep Timer with 5s fade-out, Night Mode / Dialogue Boost DSP, Preferred Audio & Subtitle Language Defaults, Hold-to-2× Fast Forward & Configurable Skip Interval, Chapter Markers & A-B Loop, Global Search & Unified Watchlist (Room v3), Live TV XMLTV EPG Guide & Channel Zapping, Dual Subtitles, Mobile Offline Downloads (MP4/MKV & HLS), and Local Network Browser (WebDAV + Jellyfin).
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
