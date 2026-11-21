# CineStream — Universal Cinema Media Player for Android Mobile & Android TV

**CineStream** (`com.aistudio.cinestream.vqxkpm`, **v1.14 / versionCode 14**) is a high-performance, zero-OOM Android video streaming and local media player built with **Kotlin**, **Jetpack Compose (Material 3)**, **AndroidX Media3 (`1.11.1`)**, and **NextLib native FFmpeg (`1.11.1-0.16.0`)**.

It runs from a single universal APK (`cinistream-v14.apk`, **34 MB**) across modern 64-bit Android phones/tablets (`arm64-v8a`), legacy 32-bit Android TVs (`armeabi-v7a`, including low-RAM Android 9 TVs), and `x86_64` emulators.

---

## Key Features

### Playback & Codec Engine
- **Universal Stream & Local Playback:** Direct HTTP/HTTPS URLs (`HLS`, `M3U8`, `DASH`, `MKV`, `MP4`, `WEBM`, `TS`) and zero-permission local storage files via Android's Storage Access Framework (`OPEN_DOCUMENT`).
- **Hybrid Hardware + Native FFmpeg 10-bit Decoding:**
  - Hardware GPU/DSP decoding first (`MediaCodec` with synchronous queueing for Snapdragon stability).
  - Automatic zero-recreation failover to **NextLib native FFmpeg (`FfmpegVideoRenderer`)** supporting **10-bit HEVC (`Main 10`, e.g., 4K HDR10 `hvc1.2.4.H150.B0`)**, 8-bit HEVC, H.264, VP8/VP9, and AV1 (`dav1d`).
  - Automatic exclusion of Qualcomm `.low_latency` WebRTC codecs (`c2.qti.hevc.decoder.low_latency`) on VOD streams.
  - Native FFmpeg audio decoding (**DTS, AC-3, E-AC-3, TrueHD, FLAC, Opus, Vorbis, AAC**).
  - Automatic Dolby Vision (`video/dolby-vision`) → HEVC (`video/hevc`) compatibility fallback.
- **Tolerant HLS HEVC Parsing:** Configured with `FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS` to handle FFmpeg-muxed HLS `.ts`/fMP4 segments containing CRA/BLA keyframes.
- **Multi-Tier Self-Healing Error Recovery:** Automatic edge-cache bypass → bidirectional HW ↔ FFmpeg SW failover → automatic H.264/lower-resolution variant fallback → one-tap **Skip Bad Segment (+5s)**.

### Unified Catalogs, Series & In-App CDN
- **Unified Movie + Series JSON Playlists:** Load remote or local JSON catalogs containing movies, multi-season TV series, seasons, episodes, auto-advance to next episode, and in-player episode/catalog switching.
- **M3U / M3U8 IPTV Playlists:** Full `#EXTINF` parsing with category groups, channel logos, search, and live stream detection.
- **Automatic IMDb Enrichment:** Extracts `tt*` IMDb IDs from playlist items and fetches posters, ratings, and release years automatically.
- **3-Layer Hybrid In-App CDN Engine:**
  1. **Multi-CDN Mirror Resolver:** Per-item `mirrors`/`sources` + playlist-level path-preserving `cdnMirrors` host swaps with automatic mid-stream failover at the exact playback millisecond.
  2. **TV-Safe On-Device Edge Disk Cache:** LRU-capped Media3 `SimpleCache` (64 MB on TV/low-RAM, 128 MB on mobile) caching VOD segments with zero extra JVM heap, bypassing manifests/subtitles/local files, and auto-bypassing on decode errors.
  3. **Custom HTTP Origin Headers:** Inheritable `Authorization`, `Referer`, `Origin`, and `User-Agent` headers (Playlist → Series → Season → Episode).

### Audio, Subtitles & Telemetry
- **0%–200% Audio Volume Boost (+20 dB):** VLC/MX-Player-style hardware DSP amplification using `LoudnessEnhancer` paired with an API 28+ `DynamicsProcessing` soft-limiter to prevent clipping.
- **Rich External & Embedded Subtitles:** Load `.srt`, `.vtt`, `.ass`, `.ssa`, `.ttml` from local storage or remote URLs, with customizable font size, text color, and background presets persisted via DataStore.
- **YouTube-Style "Stats for Nerds" Overlay:** Live connection speed, network throughput & buffer health sparklines, allocator buffer usage vs cap, JVM heap telemetry, rebuffer count/duration, dropped frames, active CDN node & disk cache usage, and live active video decoder tag (`HW` vs `SW · FFmpeg`).

### Memory Safety & Android TV First-Class Support
- **Zero-OOM Engineering for 32-bit Android 9 TVs:** Heap-bounded `LoadControl` (12–32 MB cap, zero back-buffer), immediate `allocator.trim()` + `clearVideoSurface()` on stop/switch, RGB_565 bounded Coil poster caching, and aggressive `onTrimMemory` / `onLowMemory` cleanup.
- **Full D-Pad & Remote Control Support:** Leanback launcher icon & banner, animated `tvFocusHighlight` focus borders, D-pad scrubbing, remote shortcuts, and zero-touch dead-end navigation across every dialog and sheet.

---

## Documentation Index

| Document | Description |
| :--- | :--- |
| [**Architecture & Codebase Guide**](docs/ARCHITECTURE.md) | Package layout, MVVM data flow, isolated Compose state streams, persistence layer (Room & DataStore), and lifecycle diagrams. |
| [**Decoder & Playback Engine**](docs/DECODER_AND_PLAYBACK_ENGINE.md) | Media3 `1.11.1` + NextLib FFmpeg `1.11.1-0.16.0` internals, `SmartMediaCodecSelector`, `.low_latency` filtering, HW↔FFmpeg surface handoff, 5-layer error recovery, HLS TS flags, and 200% audio boost DSP. |
| [**JSON Playlists, Series & In-App CDN Guide**](docs/PLAYLIST_AND_CDN_GUIDE.md) | Complete JSON playlist schema reference (Movies, Series, Seasons, Episodes, Mirrors, Headers, Subtitles), M3U IPTV format, IMDb enrichment, and 3-layer CDN failover & disk edge caching. |
| [**Memory Management & Android TV Optimization**](docs/MEMORY_AND_TV_OPTIMIZATION.md) | Deep dive into 32-bit Android 9 TV OOM fixes, heap-capped buffering, surface reclamation, Coil RGB_565 optimization, ABI/APK compression, and Android TV D-pad ergonomics. |
| [**Testing & Troubleshooting Playbook**](docs/TESTING_AND_TROUBLESHOOTING.md) | Running unit & Robolectric tests, reading Stats for Nerds diagnostics, and step-by-step troubleshooting for Qualcomm HEVC, 10-bit HDR10, HLS, CDN, and Android TV issues. |

---

## Supported Formats & Codecs

| Category | Supported Formats / Codecs | Engine |
| :--- | :--- | :--- |
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
# Run all unit & Robolectric Compose UI tests
gradle :app:testDebugUnitTest

# Build universal debug APK (arm64-v8a + armeabi-v7a + x86_64)
gradle :app:assembleDebug

# Output APK location
# app/build/outputs/apk/debug/app-debug.apk
# Pre-packaged root artifact: ./cinistream-v14.apk (34 MB)
```

### Installing on Phone or Android TV
1. Transfer **`cinistream-v14.apk`** to your Android phone, tablet, or Android TV.
2. Enable **Install Unknown Apps** for your file manager or sideloading tool.
3. Install `cinistream-v14.apk` (universal build covers both 64-bit phones and 32-bit Android TVs).
4. Optional: load the bundled sample playlist **`eg-playlist.json`** (from a URL or local file) to test Movies, multi-season Series, IMDb posters, and multi-CDN mirrors immediately.

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
├── README.md                          # Project overview & quick start
├── eg-playlist.json                   # Sample unified Movie + Multi-Season Series + CDN playlist
├── cinistream-v14.apk                 # Latest universal Phone + Android TV APK (v1.14, 34 MB)
├── docs/
│   ├── ARCHITECTURE.md                # Full MVVM architecture, state isolation & persistence
│   ├── DECODER_AND_PLAYBACK_ENGINE.md # Media3 1.11.1 + NextLib FFmpeg 10-bit & recovery pipeline
│   ├── PLAYLIST_AND_CDN_GUIDE.md      # JSON/M3U schemas, Series structure & 3-layer CDN engine
│   ├── MEMORY_AND_TV_OPTIMIZATION.md  # Zero-OOM Android 9 TV engineering & D-pad guide
│   └── TESTING_AND_TROUBLESHOOTING.md # Test suite, Stats for Nerds guide & issue playbooks
├── gradle/libs.versions.toml          # Version catalog (Media3 1.11.1, NextLib 1.11.1-0.16.0, Compose, Room)
└── app/src/
    ├── main/java/com/example/
    │   ├── CineStreamApplication.kt   # Shared OkHttp pool, RGB_565 Coil loader & memory trim hooks
    │   ├── MainActivity.kt            # Edge-to-edge host, Navigation Compose & VIEW intent router
    │   ├── data/                      # Room history DB & DataStore subtitle/decoder preferences
    │   ├── model/                     # StreamFormat, JsonPlaylistItem, SeriesSeason, SeriesEpisode, Tracks
    │   ├── player/                    # CinePlayerManager, DecoderEngine, CdnMirrorResolver, MediaEdgeCache,
    │   │                              # AudioVolumeBooster, JsonPlaylistParser, M3uPlaylistParser,
    │   │                              # ImdbMetadataClient, PlayerNetworkStats, SeekBarScrubEngine, SubtitleHelper
    │   ├── ui/                        # HomeScreen, PlayerScreen, overlays, sheets, TV focus utils & theme
    │   └── viewmodel/                 # CineStreamViewModel (single source of truth)
    └── test/java/com/example/         # 10 JVM unit & Robolectric Compose UI test suites
```

---

## Version History Highlights

- **v1.14 (`cinistream-v14.apk`):** Upgraded Media3 to `1.11.1`, integrated **NextLib native FFmpeg (`1.11.1-0.16.0`)** for **10-bit HEVC Main 10**, AV1 (`dav1d`), and DTS/AC-3/TrueHD decoding; blocked `.low_latency` Qualcomm WebRTC codecs; added clean `surfaceResetToken` surface handoff; added deterministic `allocator.trim()` + `clearVideoSurface()` + OkHttp pool eviction; compressed universal APK to 34 MB.
- **v1.13 (`cinistream-v13.apk`):** Added tolerant HLS HEVC TS extraction (`FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS`), synchronous MediaCodec queueing (`forceDisableMediaCodecAsynchronousQueueing()`), automatic edge-cache bypass on decode errors, bidirectional HW↔SW + H.264/lower-res track fallback, and `Skip Bad Segment (+5s)`.
- **v1.12 (`cinistream-v12.apk`):** Introduced `DecoderEngine` (`VideoDecoderMode`, `SmartMediaCodecSelector`), Qualcomm `c2.qti.hevc.decoder` detection & automatic SW failover, Dolby Vision → HEVC fallback, Tracks decoder mode selector, and active Decoder row in Stats for Nerds.
- **v1.11 (`cinistream-v11.apk`):** Added unified Movies + Multi-Season Series JSON playlists (`SeriesDetailSheet`, `Next Ep`, auto-advance) and 3-layer hybrid in-app CDN engine (`CdnMirrorResolver`, `MediaEdgeCache`, custom HTTP headers).
- **v1.10 (`cinistream-v10.apk`):** Added YouTube-style **Stats for Nerds** telemetry with sparklines, removed Gemini dependencies cleanly, and hardened 32-bit Android 9 TV memory safety (Zero-OOM `LoadControl`, `largeHeap`, RGB_565 Coil, isolated `PlaybackProgressState`).
