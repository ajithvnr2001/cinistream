# CineStream Architecture & Codebase Guide

This document provides an end-to-end architectural walkthrough of **CineStream v1.16**, explaining how the application coordinates Jetpack Compose UI, adaptive HTTP/3 Cronet + HTTP/2 OkHttp networking, Media3 + NextLib FFmpeg playback, persistence, and strict zero-OOM memory management across both Android smartphones and resource-constrained Android TVs.

---

## 1. High-Level Architecture

CineStream follows **MVVM with Unidirectional Data Flow (UDF)** and **strict state-frequency isolation**:

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           CineStreamApplication                                  │
│  • Async Play Services Cronet HTTP/3 (QUIC + TLS 1.3 + BBR) init off main thread │
│  • Shared HTTP/2 OkHttpClient (ConnectionPool(6, 5m) + 5-min IPv4-first FastDns) │
│  • Bounded RGB_565 Coil ImageLoader (10% JVM heap cap, 24 MB disk cache)         │
│  • onTrimMemory / onLowMemory -> clears Coil cache, idle sockets & DNS cache     │
└─────────────────────────────────────────┬────────────────────────────────────────┘
                                          │
┌─────────────────────────────────────────▼────────────────────────────────────────┐
│                               MainActivity                                       │
│  • enableEdgeToEdge() + orientation/TV configChanges handling                    │
│  • Handles external ACTION_VIEW intents (http/https/content/file/m3u8/mkv)       │
│  • Navigation Compose: ROUTE_HOME <──> ROUTE_PLAYER                              │
└─────────────────────────────────────────┬────────────────────────────────────────┘
                                          │
┌─────────────────────────────────────────▼────────────────────────────────────────┐
│                            CineStreamViewModel                                   │
│  • Owns CinePlayerManager, Repositories, Parsers, and IMDb Client                │
│  • Triggers debounced background origin pre-warming (playlist/series/next-ep)    │
│  • Exposes 4 frequency-isolated StateFlows:                                      │
│      1. uiState / jsonPlaylistState (low frequency: library, playlists, sheets)  │
│      2. playbackState               (medium frequency: play/pause, tracks, dec)  │
│      3. progressState               (high frequency: 500ms timeline ticks ONLY)  │
│      4. networkStats                (on-demand 1s ticks ONLY when overlay open)  │
└───────────┬─────────────────────────────┬────────────────────────────┬───────────┘
            │                             │                            │
┌───────────▼──────────────┐  ┌───────────▼───────────┐  ┌─────────────▼───────────┐
│   Playback & Net Engine  │  │  Parsing & Metadata   │  │    Local Persistence    │
│ • CinePlayerManager      │  │ • JsonPlaylistParser  │  │ • Room (AppDatabase)    │
│ • StreamingNetworkEngine │  │ • M3uPlaylistParser   │  │   MediaHistoryDao       │
│ • DecoderEngine          │  │ • ImdbMetadataClient  │  │ • DataStore Prefs       │
│ • NextRenderersFactory   │  │ • MediaFormatDetector │  │   SubtitlePrefsRepo     │
│ • CdnMirrorResolver      │  │ • SubtitleHelper      │  │   (style, volume, dec)  │
│ • MediaEdgeCache         │  └───────────────────────┘  └─────────────────────────┘
│ • AudioVolumeBooster     │
│ • SeekBarScrubEngine     │
└──────────────────────────┘
```

---

## 2. End-to-End Streaming Request & Startup Pipeline (v1.16)

Every remote stream (HLS, DASH, MKV, MP4, WebM, TS) flows through this layered data pipeline from catalog browsing to frame rendering:

```text
Catalog Loaded / Series Opened
  └─► StreamingNetworkEngine.prewarmOrigin(url)
        • Debounced 60s per origin root (HEAD https://<host>/)
        • Warms FastDnsResolver (IPv4-first) + TLS/HTTP2/QUIC socket pool before Play

User Taps Play -> CinePlayerManager.loadMedia()
  │
  ├─► AdaptiveHttpDataSourceFactory
  │     ├── Primary: CronetDataSource (Play Services Chromium: HTTP/3 QUIC + TLS 1.3 + BBR)
  │     └── Fallback: OkHttpDataSource (HTTP/2 multiplexed + 5-min IPv4-first FastDnsResolver)
  │           (Strictly rejects Java HTTP/1.1 Cronet fallback; custom headers synced to both)
  │
  ├─► DefaultDataSource.Factory (seamlessly merges remote HTTP(S) + local content:// subtitles)
  │
  ├─► MediaEdgeCache.createSmartDataSourceFactory()
  │     ├── Android TV / Low-RAM (<=400 MB heap): RAM-Direct (0 eMMC writes, 0 SQLite fsyncs)
  │     └── Phones / Tablets: 128 MB SimpleCache w/ 128 KB CacheDataSink (bypasses manifests/subs)
  │
  ├─► HlsMediaSource.Factory / DefaultMediaSourceFactory
  │     ├── Tolerant TS flags (FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS)
  │     └── FastStreamingLoadErrorHandlingPolicy (500ms transient retry; instant 4xx fail-fast)
  │
  ├─► DefaultBandwidthMeter (Smart initial Wi-Fi/Eth/5G bitrate estimates: 6 Mbps TV / 10 Mbps Phone)
  │     + DefaultTrackSelector (TV: Stereo 2ch DSP priority, 1080p/9.5 Mbps cap, fast 10s ABR decrease)
  │     + DefaultLoadControl (20–45s window strictly capped at 12–40 MB Java heap, 0–5s back-buffer)
  │
  └─► NextRenderersFactory (EXTENSION_RENDERER_MODE_ON, Sync MediaCodec Queueing)
        ├── Video: MediaCodec (HW, .low_latency excluded) ──fallback──► NextLib FFmpeg 10-bit / AV1
        └── Audio: MediaCodec (HW Stereo DSP) ────────────fallback──► NextLib FFmpeg (DTS/AC-3/TrueHD)
```

---

## 3. Package & Module Inventory

### Application & Entry (`com.example`)
| File | Responsibility |
| :--- | :--- |
| `CineStreamApplication.kt` | Asynchronously initializes Google Play Services Cronet HTTP/3 in `onCreate()`, configures a singleton HTTP/2 `OkHttpClient` backed by `FastDnsResolver` and `ConnectionPool(6, 5m)`, configures Coil's memory-bounded RGB_565 `ImageLoader`, and hooks `onTrimMemory` / `onLowMemory` to reclaim bitmap memory, close idle sockets, and flush DNS cache. |
| `MainActivity.kt` | Single-activity Compose host with `Navigation Compose` (`ROUTE_HOME`, `ROUTE_PLAYER`), Android TV Leanback intent handling, external `ACTION_VIEW` video/M3U8 deep linking, and clean `stopAndTrimBuffers()` calls on back navigation. |

### ViewModel (`com.example.viewmodel`)
| File | Responsibility |
| :--- | :--- |
| `CineStreamViewModel.kt` | Central coordinator owning `CinePlayerManager`, Room history persistence, DataStore preferences, JSON/M3U playlist loading, series episode navigation (including next-episode auto-advance), IMDb metadata hydration, debounced origin pre-warming (`prewarmFirstItemOrigin`, Series sheet open, next-episode lookahead), subtitle importing, and toast/event messaging. |

### Playback & Streaming Engine (`com.example.player`)
| File | Responsibility |
| :--- | :--- |
| `CinePlayerManager.kt` | Wraps Media3 `ExoPlayer` (`1.11.1`) with `NextRenderersFactory`, `AdaptiveHttpDataSourceFactory`, smart-estimate `DefaultBandwidthMeter`, `FastStreamingLoadErrorHandlingPolicy`, heap-capped `DefaultLoadControl`, `DefaultAllocator`, TV-aware `DefaultTrackSelector` (Stereo DSP + fast ABR), `SmartMediaCodecSelector`, tolerant HLS/TS extractors, `MediaEdgeCache`, multi-tier decoder recovery, multi-CDN failover, and `AnalyticsListener` telemetry. |
| `StreamingNetworkEngine.kt` | Encapsulates v1.16 network speed primitives: asynchronous Play Services Cronet HTTP/3 (QUIC) engine builder (rejecting `PROVIDER_NAME_FALLBACK`), `AdaptiveHttpDataSourceFactory` (dynamic Cronet ↔ OkHttp routing + unified header propagation), `FastDnsResolver` (5-min IPv4-first DNS cache), `FastStreamingLoadErrorHandlingPolicy` (`500 ms` transient retry / instant 4xx fail-fast), and debounced background `HEAD` origin pre-warming. |
| `DecoderEngine.kt` | Pure codec classification (`DecoderFallbackHelper`), `VideoDecoderMode` (`AUTO`, `HARDWARE`, `SOFTWARE`), `.low_latency` exclusion, Dolby Vision → HEVC fallback, H.264/lower-res track candidate picker, and `SmartMediaCodecSelector` FFmpeg delegation. |
| `CdnMirrorResolver.kt` | Pure URL candidate builder (explicit mirrors + path-preserving host swaps), disk cache eligibility filter (caches VOD chunks, skips `.m3u8`/`.mpd`/subtitles/local files), and TV vs Mobile storage caps. |
| `MediaEdgeCache.kt` | Device-aware caching router: runs **RAM-Direct** (zero eMMC flash writes / zero SQLite overhead) on Android TV & low-RAM devices (`FEATURE_LEANBACK || isLowRamDevice || maxMemory <= 400 MB`), and manages a `128 KB`-buffered `SimpleCache` (`cacheDir/media_edge_cache`, 128 MB LRU cap) on modern phones/tablets with dynamic per-session bypass on decoder failures. |
| `AudioVolumeBooster.kt` | Pure gain math (`AudioBoostMath`, 0%–200% / +20 dB) and session-bound `LoudnessEnhancer` + API 28+ `DynamicsProcessing` soft-limiter. |
| `JsonPlaylistParser.kt` | Resilient streaming JSON parser (capped at 4 MB read) supporting Movies, nested `seasons`/`episodes` Series, flat `episodes` Series, categories, custom HTTP headers, mirrors, subtitles, and IMDb fields. |
| `M3uPlaylistParser.kt` | Streaming `#EXTM3U` / `#EXTINF` parser supporting `tvg-logo`, `group-title`, and live channel extraction. |
| `ImdbMetadataClient.kt` | Extracts `tt\d+` IMDb IDs from URLs/strings and fetches poster artwork, ratings, and release years asynchronously using the shared `OkHttpClient`. |
| `MediaFormatDetector.kt` | Detects stream formats (`HLS`, `M3U8`, `DASH`, `MKV`, `MP4`, `WEBM`, `M3U`, `LOCAL`, `AUTO`), validates links, infers human-readable titles, and maps formats to Media3 MIME types. |
| `PlayerNetworkStats.kt` | Immutable telemetry snapshot (`PlayerNetworkStats`, including `networkTransportLabel` and `isRamDirectCacheMode`) and human-readable formatters (`PlayerStatsFormatter`) for Stats for Nerds. |
| `SeekBarScrubEngine.kt` | Pure scrubbing & D-pad acceleration math for smooth timeline seeking across long movies without jerky jumps. |
| `SubtitleHelper.kt` | Resolves local and remote `.srt`/`.vtt`/`.ass`/`.ssa`/`.ttml` subtitles into playable URIs and Media3 MIME types. |

### Data Persistence (`com.example.data`)
| File | Responsibility |
| :--- | :--- |
| `AppDatabase.kt` | Room SQLite database storing watch history, resume timestamps, format metadata, and IMDb artwork. |
| `MediaHistoryEntity.kt` / `MediaHistoryDao.kt` / `MediaHistoryRepository.kt` | Stores recently played streams & local videos so users can resume at their exact last position. |
| `SubtitlePrefsRepository.kt` | Jetpack DataStore (`Preferences`) storing subtitle appearance (size, text color, background opacity), saved JSON playlist URL, volume boost level, aspect ratio, Stats for Nerds toggle, and preferred `VideoDecoderMode` (`AUTO`, `HARDWARE`, `SOFTWARE`). |

### Domain Models (`com.example.model`)
| File | Responsibility |
| :--- | :--- |
| `MediaModels.kt` | Immutable data models: `StreamFormat`, `MediaContentType` (`MOVIE`, `SERIES`), `SeriesEpisode`, `SeriesSeason`, `JsonPlaylistItem`, `M3uEntry`, `ExternalSubtitle`, `TrackOption`, `SubtitleAppearance`, `AspectRatioMode`. |

### UI Layer (`com.example.ui`)
- **Home (`ui/home/`):** `HomeScreen.kt` (hero banner, stream launcher, Continue Watching, Movies & Series catalog shelves, M3U channels) and `JsonPlaylistUi.kt` (playlist loader dialogs & cards).
- **Player (`ui/player/`):**
  - `PlayerScreen.kt` — Fullscreen video surface (`key(surfaceResetToken)`), touch gesture detector (tap, double-tap seek, right-side vertical volume swipe), TV remote key handler, and modal sheet orchestration.
  - `PlayerControlsOverlay.kt` — Top bar, playback transport controls, isolated `PlayerTimestampBadge` & `PlayerIsolatedSeekBar`, speed selector, aspect ratio toggle, and `PlayerErrorCard` (with `Try Software/Hardware Decoder`, `Skip Bad Segment (+5s)`, `Retry`, `Try as HLS`, and `Back`).
  - `PlayerStatsOverlay.kt` — Compact YouTube-style Stats for Nerds card with header `HTTP/3` / `HTTP/2` transport pill, `RAM Direct (Zero eMMC)` / Edge cache row, active video decoder row, and custom Canvas sparklines (`Network Activity` & `Buffer Health`).
  - `TracksSheet.kt` — Video Quality tracks, Audio tracks, **Video Decoder Engine** (`Auto / HW / FFmpeg SW`), **Volume Boost** presets, and **Stats for Nerds** toggle.
  - `SubtitleSheet.kt` — Subtitle track switcher, local file picker, remote URL loader, and live style customizer.
  - `SeriesDetailSheet.kt` — Season tabs + episode list modal for browsing and playing TV series on both phone and Android TV.
  - `JsonPlaylistSheet.kt` & `M3uPlaylistSheet.kt` — In-player catalog & channel switchers.
  - `CinemaSeekBar.kt` & `VolumeBoostUi.kt` — Custom buffered seekbar and 0%–200% boost HUD/slider.
- **Shared Components & Theme (`ui/components/`, `ui/theme/`):** `PosterMediaCard.kt`, `FormatBadge.kt`, `LocalFilePicker.kt`, `TvUtils.kt` (`tvFocusHighlight`, `isTelevisionDevice()`), and Obsidian/CinemaAmber M3 theme (`Color.kt`, `Theme.kt`, `Type.kt`).

---

## 4. State-Frequency Isolation Pattern (Critical Performance Design)

A major cause of Compose jank and OOMs on low-RAM Android TVs is attaching high-frequency playback timestamps (updating every 500ms) to a monolithic screen state object, causing the entire `PlayerScreen` tree to recompose twice per second.

CineStream eliminates this by splitting player state into **three isolated `StateFlow`s**:

1. **`CinePlayerManager.state: StateFlow<PlayerPlaybackState>`**
   - Emits **only** on discrete state transitions (play/pause, buffering start/end, track list change, decoder change, error state, surface reset token).
   - Almost zero emissions during steady playback.
2. **`CinePlayerManager.progressState: StateFlow<PlaybackProgressState>`**
   - Updated every 500ms while playing (`currentPositionMs`, `bufferedPositionMs`, `durationMs`, `isLiveStream`).
   - Collected **exclusively** inside two leaf composables (`PlayerTimestampBadge` and `PlayerIsolatedSeekBar`) so 98% of `PlayerScreen` never recomposes during playback ticks.
3. **`CinePlayerManager.networkStats: StateFlow<PlayerNetworkStats>`**
   - Polled **only** when the user enables **Stats for Nerds** (`statsOverlayVisible == true`).
   - Overhead is strictly **zero** while hidden.

---

## 5. Navigation & Resource Lifecycle

```text
HomeScreen ──(Select Movie / Episode / URL / Local File)──► CineStreamViewModel.startPlayback()
                                                                 │
                                                                 ▼
                                                         Navigate -> ROUTE_PLAYER
                                                                 │
                                           (Active playback, progress ticks, optional stats)
                                                                 │
                                           ◄──(User presses Back)┘
                                           │
                                           ▼
                       1. Save resume position to Room DB
                       2. CinePlayerManager.stopAndTrimBuffers():
                            • player.playWhenReady = false
                            • player.clearVideoSurface()   (frees 4K GPU/CPU ION buffers)
                            • player.stop()
                            • player.clearMediaItems()
                            • allocator.trim()             (returns 64 KB heap segments)
                            • volumeBooster.release()      (releases native AudioFX)
                       3. Pop backstack -> HomeScreen (clean heap, zero leaked buffers)
```

This guarantees that returning to `HomeScreen` never leaves 30–40 MB of video buffers or 4K hardware surfaces alive during Compose transitions.
