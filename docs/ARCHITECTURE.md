# CineStream Architecture & Codebase Guide

This document provides an in-depth architectural walkthrough of **CineStream v1.14**, explaining how the application coordinates Jetpack Compose UI, Media3 + NextLib FFmpeg playback, persistence, and strict memory management across both Android smartphones and resource-constrained Android TVs.

---

## 1. High-Level Architecture

CineStream follows **MVVM with Unidirectional Data Flow (UDF)** and **strict state-frequency isolation**:

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                        CineStreamApplication                                 │
│  • Shared OkHttpClient (5-conn pool, zero body logging)                      │
│  • Bounded RGB_565 Coil ImageLoader (10% JVM heap cap, 24 MB disk cache)     │
│  • onTrimMemory / onLowMemory -> clears Coil cache + evicts idle sockets     │
└───────────────────────────────────────┬──────────────────────────────────────┘
                                        │
┌───────────────────────────────────────▼──────────────────────────────────────┐
│                             MainActivity                                     │
│  • enableEdgeToEdge() + orientation/TV configChanges handling                │
│  • Handles external ACTION_VIEW intents (http/https/content/file/m3u8/mkv)   │
│  • Navigation Compose: ROUTE_HOME <──> ROUTE_PLAYER                          │
└───────────────────────────────────────┬──────────────────────────────────────┘
                                        │
┌───────────────────────────────────────▼──────────────────────────────────────┐
│                         CineStreamViewModel                                  │
│  • Owns CinePlayerManager, Repositories, Parsers, and IMDb Client            │
│  • Exposes 3 frequency-isolated StateFlows:                                  │
│      1. uiState            (low frequency: library, playlists, sheets)       │
│      2. playbackState      (medium frequency: play/pause, tracks, decoder)   │
│      3. progressState      (high frequency: 500ms timeline ticks ONLY)       │
│      4. networkStats       (on-demand 1s ticks ONLY when overlay is open)    │
└───────────┬───────────────────────────┬──────────────────────────┬───────────┘
            │                           │                          │
┌───────────▼───────────┐   ┌───────────▼───────────┐   ┌──────────▼───────────┐
│   Playback Engine     │   │  Parsing & Metadata   │   │  Local Persistence   │
│ • CinePlayerManager   │   │ • JsonPlaylistParser  │   │ • Room (AppDatabase) │
│ • DecoderEngine       │   │ • M3uPlaylistParser   │   │   MediaHistoryDao    │
│ • NextRenderersFactory│   │ • ImdbMetadataClient  │   │ • DataStore Prefs    │
│ • CdnMirrorResolver   │   │ • MediaFormatDetector │   │   SubtitlePrefsRepo  │
│ • MediaEdgeCache      │   │ • SubtitleHelper      │   │   (style + decoder)  │
│ • AudioVolumeBooster  │   └───────────────────────┘   └──────────────────────┘
│ • SeekBarScrubEngine  │
└───────────────────────┘
```

---

## 2. Package & Module Inventory

### Application & Entry (`com.example`)
| File | Responsibility |
| :--- | :--- |
| `CineStreamApplication.kt` | Provides a singleton `OkHttpClient`, configures Coil's memory-bounded RGB_565 `ImageLoader`, and hooks `onTrimMemory` / `onLowMemory` to reclaim bitmap memory and close idle sockets. |
| `MainActivity.kt` | Single-activity Compose host with `Navigation Compose` (`ROUTE_HOME`, `ROUTE_PLAYER`), Android TV Leanback intent handling, external `ACTION_VIEW` video/M3U8 deep linking, and clean `stopAndTrimBuffers()` calls on back navigation. |

### ViewModel (`com.example.viewmodel`)
| File | Responsibility |
| :--- | :--- |
| `CineStreamViewModel.kt` | Central coordinator owning `CinePlayerManager`, Room history persistence, DataStore preferences, JSON/M3U playlist loading, series episode navigation (including next-episode auto-advance), IMDb metadata hydration, subtitle importing, and toast/event messaging. |

### Playback & Media Engine (`com.example.player`)
| File | Responsibility |
| :--- | :--- |
| `CinePlayerManager.kt` | Wraps Media3 `ExoPlayer` (`1.11.1`) with `NextRenderersFactory`, heap-capped `DefaultLoadControl`, `DefaultAllocator`, `DefaultTrackSelector`, `SmartMediaCodecSelector`, tolerant HLS/TS extractors, `MediaEdgeCache`, multi-tier decoder recovery, multi-CDN failover, and `AnalyticsListener` telemetry. |
| `DecoderEngine.kt` | Pure codec classification (`DecoderFallbackHelper`), `VideoDecoderMode` (`AUTO`, `HARDWARE`, `SOFTWARE`), `.low_latency` exclusion, Dolby Vision → HEVC fallback, H.264/lower-res track candidate picker, and `SmartMediaCodecSelector` FFmpeg delegation. |
| `CdnMirrorResolver.kt` | Pure URL candidate builder (explicit mirrors + path-preserving host swaps), disk cache eligibility filter (caches VOD chunks, skips `.m3u8`/`.mpd`/subtitles/local files), and TV vs Mobile storage caps. |
| `MediaEdgeCache.kt` | Process-wide singleton wrapping Media3 `SimpleCache` (`cacheDir/media_edge_cache`) and `SmartEdgeCacheDataSource` with dynamic per-session bypass on decoder failures. |
| `AudioVolumeBooster.kt` | Pure gain math (`AudioBoostMath`, 0%–200% / +20 dB) and session-bound `LoudnessEnhancer` + API 28+ `DynamicsProcessing` soft-limiter. |
| `JsonPlaylistParser.kt` | Resilient streaming JSON parser (capped at 4 MB read) supporting Movies, nested `seasons`/`episodes` Series, flat `episodes` Series, categories, custom HTTP headers, mirrors, subtitles, and IMDb fields. |
| `M3uPlaylistParser.kt` | Streaming `#EXTM3U` / `#EXTINF` parser supporting `tvg-logo`, `group-title`, and live channel extraction. |
| `ImdbMetadataClient.kt` | Extracts `tt\d+` IMDb IDs from URLs/strings and fetches poster artwork, ratings, and release years asynchronously using the shared `OkHttpClient`. |
| `MediaFormatDetector.kt` | Detects stream formats (`HLS`, `M3U8`, `DASH`, `MKV`, `MP4`, `WEBM`, `M3U`, `LOCAL`, `AUTO`), validates links, infers human-readable titles, and maps formats to Media3 MIME types. |
| `PlayerNetworkStats.kt` | Immutable telemetry snapshot (`PlayerNetworkStats`) and human-readable formatters (`PlayerStatsFormatter`) for Stats for Nerds. |
| `SeekBarScrubEngine.kt` | Pure scrubbing & D-pad acceleration math for smooth timeline seeking across long movies without jerky jumps. |
| `SubtitleHelper.kt` | Resolves local and remote `.srt`/`.vtt`/`.ass`/`.ssa`/`.ttml` subtitles into playable URIs and Media3 MIME types. |

### Data Persistence (`com.example.data`)
| File | Responsibility |
| :--- | :--- |
| `AppDatabase.kt` | Room SQLite database storing watch history, resume timestamps, format metadata, and IMDb artwork. |
| `MediaHistoryEntity.kt` / `MediaHistoryDao.kt` / `MediaHistoryRepository.kt` | Stores recently played streams & local videos so users can resume at their exact last position. |
| `SubtitlePrefsRepository.kt` | Jetpack DataStore (`Preferences`) storing subtitle appearance (size, text color, background opacity) and preferred `VideoDecoderMode` (`AUTO`, `HARDWARE`, `SOFTWARE`). |

### Domain Models (`com.example.model`)
| File | Responsibility |
| :--- | :--- |
| `MediaModels.kt` | Immutable data models: `StreamFormat`, `MediaContentType` (`MOVIE`, `SERIES`), `SeriesEpisode`, `SeriesSeason`, `JsonPlaylistItem`, `M3uChannel`, `ExternalSubtitle`, `TrackOption`, `SubtitleAppearance`, `AspectRatioMode`. |

### UI Layer (`com.example.ui`)
- **Home (`ui/home/`):** `HomeScreen.kt` (hero banner, stream launcher, Continue Watching, Movies & Series catalog shelves, M3U channels) and `JsonPlaylistUi.kt` (playlist loader dialogs & cards).
- **Player (`ui/player/`):**
  - `PlayerScreen.kt` — Fullscreen video surface (`key(surfaceResetToken)`), touch gesture detector (tap, double-tap seek, right-side vertical volume swipe), TV remote key handler, and modal sheet orchestration.
  - `PlayerControlsOverlay.kt` — Top bar, playback transport controls, isolated `PlayerTimestampBadge` & `PlayerIsolatedSeekBar`, speed selector, aspect ratio toggle, and `PlayerErrorCard` (with `Try Software/Hardware Decoder`, `Skip Bad Segment (+5s)`, `Retry`, `Try as HLS`, and `Back`).
  - `PlayerStatsOverlay.kt` — Compact YouTube-style Stats for Nerds card with custom Canvas sparklines (`Network Activity` & `Buffer Health`).
  - `TracksSheet.kt` — Video Quality tracks, Audio tracks, **Video Decoder Engine** (`Auto / HW / FFmpeg SW`), and **Stats for Nerds** toggle.
  - `SubtitleSheet.kt` — Subtitle track switcher, local file picker, remote URL loader, and live style customizer.
  - `SeriesDetailSheet.kt` — Season tabs + episode list modal for browsing and playing TV series on both phone and Android TV.
  - `JsonPlaylistSheet.kt` & `M3uPlaylistSheet.kt` — In-player catalog & channel switchers.
  - `CinemaSeekBar.kt` & `VolumeBoostUi.kt` — Custom buffered seekbar and 0%–200% boost HUD/slider.
- **Shared Components & Theme (`ui/components/`, `ui/theme/`):** `PosterMediaCard.kt`, `FormatBadge.kt`, `LocalFilePicker.kt`, `TvUtils.kt` (`tvFocusHighlight`, `isTelevisionDevice()`), and Obsidian/CinemaAmber M3 theme (`Color.kt`, `Theme.kt`, `Type.kt`).

---

## 3. State-Frequency Isolation Pattern (Critical Performance Design)

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

## 4. Navigation & Resource Lifecycle

```text
HomeScreen ──(Select Movie / Episode / URL / Local File)──► CineStreamViewModel.playMedia()
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
                            • player.clearVideoSurface()   (frees 4K GPU/CPU buffers)
                            • player.stop()
                            • player.clearMediaItems()
                            • allocator.trim()             (returns 64 KB heap segments)
                            • volumeBooster.release()      (releases native AudioFX)
                       3. Pop backstack -> HomeScreen (clean heap, zero leaked buffers)
```

This guarantees that returning to `HomeScreen` never leaves 30+ MB of video buffers or 4K hardware surfaces alive during Compose transitions.
