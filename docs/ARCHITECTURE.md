# CineStream Architecture & Codebase Guide (v1.32)

This document provides an end-to-end architectural reference for **CineStream v1.32** (`com.aistudio.cinestream.vqxkpm`), detailing how the app coordinates Jetpack Compose Material 3 UI, adaptive HTTP/3 Cronet + HTTP/2 OkHttp + WireGuard NordLynx networking, parallel HLS/range prefetching, Media3 + NextLib FFmpeg playback, `AudioSyncMediaSource` HLS-segment bidirectional audio sync, extraction-time HLS subtitle transcoding, multi-account cloud/LAN WebDAV, Room v4 + Android Keystore persistence, and zero-OOM memory engineering across both phones and 32-bit Android TVs.

---

## 1. High-Level System Architecture

CineStream uses **MVVM with Unidirectional Data Flow (UDF)**, **constructor dependency wiring**, and **strict state-frequency isolation**:

```text
┌───────────────────────────────────────────────────────────────────────────────────────┐
│                                CineStreamApplication                                  │
│  • Async Play Services Cronet HTTP/3 (QUIC + TLS 1.3 + BBR) init off main thread      │
│  • Shared HTTP/2 OkHttpClient (Dispatcher 32/12, Pool(12, 5m), FastDnsResolver/DoH)   │
│  • DynamicSocksProxyManager + NordLynxEngine (embedded WireGuard GoBackend)           │
│  • Bounded RGB_565 Coil ImageLoader (10% JVM heap cap, 24 MB disk cache)              │
│  • onTrimMemory / onLowMemory -> evicts Coil bitmaps, prefetch RAM, sockets & DNS     │
└──────────────────────────────────────────┬────────────────────────────────────────────┘
                                           │
┌──────────────────────────────────────────▼────────────────────────────────────────────┐
│                                   MainActivity                                        │
│  • enableEdgeToEdge() + orientation, PiP (Android 8+), TV configChanges handling      │
│  • Navigation Compose: ROUTE_HOME <──> ROUTE_PLAYER                                   │
│  • Handles ACTION_VIEW media links, WireGuard VpnService consent, SAF backup/restore  │
│  • Hosts modal sheets (Privacy/VPN, Web Remote, Local Network, Offline Downloads)     │
└──────────────────────────────────────────┬────────────────────────────────────────────┘
                                           │
┌──────────────────────────────────────────▼────────────────────────────────────────────┐
│                               CineStreamViewModel                                     │
│  • Coordinates CinePlayerManager, Room v4 repos, DataStore prefs, Parsers & Engines   │
│  • Drives MultiJsonPlaylistReducer, GlobalSearchEngine, SmartVpnFailoverCoordinator   │
│  • Manages SubtitleMediaSourceBuilder (HLS cue transcoding) & SecondarySubtitleEngine │
│  • Hosts LocalWebRemoteServer (LAN HTTP + QR + Sync), OfflineDownloadManager, WebDAV  │
│  • Exposes frequency-isolated StateFlows:                                             │
│      1. uiState / jsonPlaylistState / v24 states (low frequency)                      │
│      2. playbackState               (medium frequency: play/pause, tracks, decoder)   │
│      3. progressState               (high frequency: 500 ms timeline ticks ONLY)      │
│      4. networkStats                (on-demand 1 s ticks ONLY when Stats HUD open)    │
└───────────┬──────────────────────────────┬─────────────────────────────┬──────────────┘
            │                              │                             │
┌───────────▼──────────────┐  ┌────────────▼──────────────┐  ┌───────────▼──────────────┐
│  Playback & Net Engines  │  │  Ecosystem & Parsers      │  │   Persistence & Crypto   │
│ • CinePlayerManager      │  │ • JsonPlaylistParser      │  │ • Room v4 (AppDatabase)  │
│ • StreamingNetworkEngine │  │ • M3uPlaylistParser       │  │   - media_history        │
│ • ParallelPrefetchEngine │  │ • XmltvEpgParser          │  │   - favorites            │
│ • NordLynxEngine         │  │ • ImdbMetadataClient      │  │   - offline_downloads    │
│ • NordVpnApiClient       │  │ • SubtitleMediaSourceBld  │  │   - network_servers      │
│ • PrivateDnsAndVpnEngine │  │ • SecondarySubtitleEngine │  │ • DataStore Prefs        │
│ • SmartVpnFailoverCoord. │  │ • LocalNetworkClient      │  │   (SubtitlePrefsRepo)    │
│ • FrameDropAnalyzer      │  │ • OfflineDownloadManager  │  │ • CredentialVault        │
│ • AudioSyncMediaSource   │  │ • LocalWebRemoteServer    │  │   (Keystore AES-256-GCM) │
│ • AudioVolumeBooster     │  │ • QrCodeMatrixGenerator   │  │ • DeviceSyncCodec        │
│ • CdnMirrorResolver      │  │ • MediaFormatDetector     │  └──────────────────────────┘
│ • MediaEdgeCache         │  │ • SubtitleHelper          │
│ • MediaPlaybackService   │  └───────────────────────────┘
└──────────────────────────┘
```

---

## 2. End-to-End Request, Prefetch & Playback Pipeline

```text
Catalog Loaded / Series Opened / Next Episode Queued
  └─► StreamingNetworkEngine.prewarmOrigin(url)
        • Debounced 60 s per origin root (HEAD https://<host>/)
        • Warms FastDnsResolver (IPv4-first / DoH) + TLS/QUIC socket pool before Play

User Taps Play -> CinePlayerManager.loadMedia()
  │
  ├─► AdaptiveHttpDataSourceFactory
  │     ├── Primary: CronetDataSource (Play Services HTTP/3 QUIC + TLS 1.3 + BBR)
  │     │     (Auto-bypassed when Private DoH / SOCKS5 is active or QuicThroughputWatchdog demotes)
  │     └── Fallback: OkHttpDataSource (HTTP/2 multiplexed + FastDnsResolver + DoH)
  │
  ├─► ParallelPrefetchDataSource (ParallelPrefetchEngine)
  │     ├── Tees .m3u8 playlists (0 ms overhead) -> 2× TV (<=14 MB) / 3× Mobile (<=28 MB)
  │     ├── StreamingPrefetchEntry: 0 ms mid-download live handoff in 32 KB chunks
  │     ├── Dedicated non-blocking audio lane + foreground-first anti-starvation
  │     └── 2–3× parallel 206 Content-Range slices for progressive MP4/MKV/WebM
  │
  ├─► MediaEdgeCache.createSmartDataSourceFactory()
  │     ├── Android TV / Low-RAM (<=400 MB heap): RAM-Direct (0 eMMC writes, 0 SQLite fsyncs)
  │     └── Phones / Tablets: 128 MB SimpleCache w/ 128 KB CacheDataSink (skips manifests/subs)
  │
  ├─► HlsMediaSource.Factory / DefaultMediaSourceFactory
  │     ├── Tolerant TS flags (FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS)
  │     └── FastStreamingLoadErrorHandlingPolicy (500 ms transient retry; instant 4xx fail-fast)
  │
  ├─► Media3 ExoPlayer (1.11.1)
  │     ├── DefaultBandwidthMeter (Smart cold-start: 6 Mbps TV / 10 Mbps Phone / 12 Mbps Eth/5G)
  │     ├── DefaultTrackSelector (TV Stereo 2ch DSP, preferred languages, 1080p/9.5 Mbps TV cap)
  │     ├── DefaultLoadControl (20–45 s window strictly capped at 12–40 MB Java heap)
  │     └── FrameDropAnalyzer + seamless display refresh-rate matching
  │
  ├─► AudioSyncMediaSource (AudioSyncMediaPeriod + AudioOffsetSampleStream)
  │     └── Shifts C.TRACK_TYPE_AUDIO PTS (-10s..+10s) on HLS muxed/demuxed segments, DASH, MKV, MP4
  │
  └─► NextRenderersFactory (EXTENSION_RENDERER_MODE_ON, Selective Async/Sync Queueing)
        ├── Video: MediaCodec (HW, .low_latency blocked) ──fallback──► NextLib FFmpeg 10-bit / AV1
        └── Audio: MediaCodec / HDMI Passthrough / NextLib FFmpeg -> AudioVolumeBooster
```

---

## 3. Complete Package & Module Inventory

### Application & Entry (`com.example`)
| File | Responsibility |
| :--- | :--- |
| `CineStreamApplication.kt` | Initializes Play Services Cronet HTTP/3 off the main thread, builds shared HTTP/2 `OkHttpClient` (`Dispatcher(32, 12)`, `ConnectionPool(12, 5m)`, `FastDnsResolver`, `DynamicSocksProxyManager`), configures Coil RGB_565 `ImageLoader`, and hooks `onTrimMemory` / `onLowMemory` to evict bitmaps, prefetch RAM, idle sockets, and DNS entries. |
| `MainActivity.kt` | Edge-to-edge Compose host managing Navigation Compose (`ROUTE_HOME`, `ROUTE_PLAYER`), Android 8+ Picture-in-Picture (`onUserLeaveHint`, `PictureInPictureParams` with `-10s`/`Play-Pause`/`+10s` `RemoteAction`s), WireGuard `VpnService.prepare()` consent, SAF JSON sync export/import, external `ACTION_VIEW` video/M3U8 routing, and modal sheets. |

### ViewModel Layer (`com.example.viewmodel`)
| File | Responsibility |
| :--- | :--- |
| `CineStreamViewModel.kt` | Central coordinator wiring `CinePlayerManager`, Room v4 repositories, DataStore preferences, multi-JSON & M3U playlists, XMLTV EPG, external/dual subtitles, offline downloads, multi-account WebDAV/Jellyfin browser, LAN Web Remote server, local Wi-Fi/SAF sync, Sleep Timer, Up-Next countdown, A-B loop, and Smart Auto-VPN failover. |
| `MultiJsonPlaylistState.kt` | Pure `MultiJsonPlaylistReducer` handling multi-feed JSON aggregation, collision-free item ID namespacing, `"All"` vs single-playlist selection, refresh, and removal. |
| `NetworkPrivacyState.kt` | `NetworkPrivacyUiState` modeling Private DNS mode, NordVPN login/key status, 118+ searchable locations, NordLynx WireGuard tunnel state, Smart Auto-VPN Failover, and live Cloudflare route probe results. |
| `V24UiStates.kt` | Immutable UI states for `SecondarySubtitleUiState`, `EpgUiState`, `ChannelZapBannerState`, `WebRemoteUiState`, `LocalNetworkUiState`, `DownloadsUiState`, and `SyncBackupUiState`. |

### Playback, Network, VPN & Ecosystem Engines (`com.example.player` — 26 files)
| File | Responsibility |
| :--- | :--- |
| `CinePlayerManager.kt` | Media3 `ExoPlayer` (`1.11.1`) wrapper owning `NextRenderersFactory`, `AudioSyncMediaSource`, `AdaptiveHttpDataSourceFactory`, `ParallelPrefetchDataSource`, `MediaEdgeCache`, `AudioVolumeBooster`, `FrameDropAnalyzer`, `QuicThroughputWatchdog`, multi-tier HW↔FFmpeg recovery, Multi-CDN failover, and `AnalyticsListener` telemetry. |
| `AudioSyncMediaSource.kt` | Container- and codec-agnostic `WrappingMediaSource` (`AudioSyncMediaPeriod` + `AudioOffsetSampleStream`) shifting `C.TRACK_TYPE_AUDIO` timestamps (`-10 s` to `+10 s`) across HLS muxed `.ts` segments, HLS demuxed `#EXT-X-MEDIA:TYPE=AUDIO` segments, DASH, MKV, MP4, PCM, FFmpeg, and HDMI passthrough. |
| `StreamingNetworkEngine.kt` | Asynchronous Play Services Cronet HTTP/3 builder (rejecting `PROVIDER_NAME_FALLBACK`), `AdaptiveHttpDataSourceFactory`, `QuicThroughputWatchdog`, `FastDnsResolver` (IPv4-first 5-min cache + DoH), `FastStreamingLoadErrorHandlingPolicy`, and debounced `HEAD` origin pre-warming. |
| `ParallelPrefetchEngine.kt` | `HlsSegmentIndex`, `StreamingPrefetchEntry`, `ParallelHlsPrefetchManager`, and `ParallelPrefetchDataSource` for live-streaming parallel HLS segment prefetching, dedicated audio lane, and progressive `206 Content-Range` lookahead. |
| `DecoderEngine.kt` | `VideoDecoderMode` (`AUTO`, `HARDWARE`, `SOFTWARE`), `DecoderFallbackHelper`, `.low_latency` WebRTC exclusion, Dolby Vision → HEVC fallback, H.264/lower-res track fallback picker, and `SmartMediaCodecSelector`. |
| `FrameDropAnalyzer.kt` | Rolling 3s `drops/sec` analyzer classifying frame drops into `HEALTHY`, `SEEK_BURST`, `STREAM_PTS_OR_RESYNC`, or `SW_CPU_LOAD`, plus `MediaCodecQueueingPolicy` (selective Android 12+ async vs sync queueing). |
| `NordLynxEngine.kt` | Embedded WireGuard (`com.wireguard.android:tunnel`, `GoBackend`) NordLynx VPN engine with per-app scoping (`includeApplication`), Cloudflare `1.1.1.1`/`1.0.0.1` tunnel DNS, and live Curve25519 peer negotiation. |
| `PrivateDnsAndVpnEngine.kt` | `PrivateDnsMode` (`SYSTEM`, `CLOUDFLARE_1111`, `GOOGLE_8888`), `DohDnsClient` (RFC 8484 wire/JSON over bootstrap IPs), `DynamicSocksProxyManager`, `CredentialVault` (Android Keystore AES-256-GCM encryption), and Cloudflare `/cdn-cgi/trace` probe. |
| `NordVpnApiClient.kt` | Real `api.nordvpn.com` client exchanging Access Tokens for NordLynx private keys + SOCKS5 service credentials, syncing 118+ countries/cities, and selecting lowest-load WireGuard/SOCKS5 endpoints. |
| `SmartVpnFailoverCoordinator.kt` | Pure state coordinator triggering opt-in NordLynx auto-connect only when direct ISP streams hit repeated stalls or fatal network blocks. |
| `CdnMirrorResolver.kt` | Candidate URL builder (explicit mirrors + path-preserving host swaps), disk cache eligibility filter, and device-tier storage caps. |
| `MediaEdgeCache.kt` | Routes Android TV / low-RAM devices to **RAM-Direct** (zero eMMC writes) and phones/tablets to a 128 MB LRU `SimpleCache` with 128 KB buffered `CacheDataSink`. |
| `AudioVolumeBooster.kt` | `AudioBoostMath` (`0%–200%`, `+20 dB`), `LoudnessEnhancer`, and API 28+ `DynamicsProcessing` soft-limiter + Night Mode 4-band dialogue boost Pre-EQ/MBC. |
| `DelayAudioProcessor.kt` | `AudioDelayMath` (`-10 s` to `+10 s` clamping, `ms`/`s` numeric input parsing, formatting, and seek realignment math), `SyncTimeUnit`, and legacy `DelayAudioProcessor` PCM helper. |
| `MediaPlaybackService.kt` | Play-compliant `mediaPlayback` foreground service keeping audio alive with lock-screen media controls when Background Audio is toggled on mobile. |
| `SecondarySubtitleEngine.kt` | Parses `.srt` and `.vtt` caption files into timestamped cue lists and renders synchronized dual subtitles at the top of the player. |
| `SubtitleHelper.kt` | Resolves local/remote `.srt`, `.vtt`, `.ass`, `.ssa`, `.ttml` URIs, shifts timestamps, and exposes `SubtitleMediaSourceBuilder` (`ProgressiveMediaSource` + `SubtitleExtractor` → `application/x-media3-cues`) for HLS sidecar subtitles. |
| `JsonPlaylistParser.kt` | Streaming 4 MB-capped JSON catalog parser supporting Movies, nested/flat Series, categories, mirrors, inherited HTTP headers, subtitles, and IMDb IDs. |
| `M3uPlaylistParser.kt` | `#EXTM3U` / `#EXTINF` parser supporting `tvg-logo`, `tvg-id`, `group-title`, and `#EXTM3U url-tvg` EPG auto-discovery. |
| `XmltvEpgParser.kt` | Streaming `.xml` / `.xml.gz` XMLTV parser matching channel IDs/names to compute live Now/Next programs and progress fractions. |
| `ImdbMetadataClient.kt` | Extracts `tt\d+` IMDb IDs and hydrates poster artwork, ratings, and release years asynchronously. |
| `LocalNetworkClient.kt` | Multi-account cloud/LAN media browser implementing RFC 4918 WebDAV `PROPFIND` (with same-origin redirect preservation, cross-origin `Authorization` stripping, XXE-disabled multi-`propstat` XML parsing, byte-for-byte UTF-8 Basic Auth, sibling subtitle auto-discovery, and connection testing) plus Jellyfin REST API browsing. |
| `OfflineDownloadManager.kt` | Zero-permission app-private downloader (`filesDir/offline_downloads/`) for progressive MP4/MKV and HLS `.m3u8` (segment download + local playlist rewrite). |
| `MediaFormatDetector.kt` | Format detection, URL validation, title inference, and Media3 MIME mapping. |
| `PlayerNetworkStats.kt` | Immutable telemetry snapshot (`PlayerNetworkStats`) and human-readable formatters (`PlayerStatsFormatter`). |
| `SeekBarScrubEngine.kt` | Touch drag and progressive D-pad hold scrubbing math (`±1m → ±2m → ±5m → ±10m`). |

### Phone-to-TV Web Remote (`com.example.remote`)
| File | Responsibility |
| :--- | :--- |
| `LocalWebRemoteServer.kt` | Embedded LAN HTTP server (`ServerSocket`) with constant-time 6-digit session PIN authentication, mobile web UI, push endpoints (stream URL, JSON/M3U playlist, subtitle, EPG URL, NordVPN key), and local Wi-Fi Sync (`/api/sync/export`, `/api/sync/import`). |
| `QrCodeMatrixGenerator.kt` | Pure-Kotlin QR code generator rendering scannable `http://<lan-ip>:<port>` pairing matrices on Canvas with zero external barcode dependencies. |

### Data Persistence (`com.example.data` — 9 files)
| File | Responsibility |
| :--- | :--- |
| `AppDatabase.kt` | **Room v4** SQLite database with automatic migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`) containing `media_history`, `favorites`, `offline_downloads`, and multi-account `network_servers` (non-unique `(baseUrl, serverType)` index). |
| `MediaHistoryEntity.kt` / `MediaHistoryDao.kt` / `MediaHistoryRepository.kt` | Stores watch history, exact resume timestamps, series/season/episode keys, audio/subtitle delay offsets per video, and pinned streams. |
| `ContinueWatchingHelper.kt` | Deduplicates in-progress items per series, filters finished/unstarted entries, formats `S1 E3` codes, remaining minutes, and `Watched` status. |
| `FavoriteAndSearch.kt` | `FavoriteRepository` and `GlobalSearchEngine` unifying instant search across Watchlist, JSON Catalogs, M3U Live TV, Offline Downloads, and History. |
| `V24EntitiesAndDaos.kt` | Room entities & DAOs for `FavoriteItemEntity`, `OfflineDownloadEntity`, and `NetworkServerEntity` (NAS credentials encrypted via `CredentialVault`). |
| `DeviceSyncSnapshot.kt` | `DeviceSyncCodec` serializing/deserializing credential-free JSON snapshots for Phone ↔ TV Wi-Fi sync and SAF backup/restore (explicitly excluding VPN keys and NAS passwords). |
| `SubtitlePrefsRepository.kt` | Jetpack DataStore (`Preferences`) storing subtitle appearance, multi-JSON URLs, volume boost, aspect ratio, Stats toggle, decoder/transport modes, DNS/VPN settings (Keystore-encrypted), preferred languages, skip interval, and Night Mode. |

### UI & Editorial Design System (`com.example.ui`)
- **Theme (`ui/theme/`):**
  - `Color.kt`: Warm carbon surfaces (`#09090B`, `#121215`, `#1A1A1F`, `#24242A`), hairline outlines (`#2C2C34`, `#1F1F26`), warm ivory ink (`#F4F1EA`), stone secondary (`#A09D96`), projector tungsten gold (`#E8A435`), steel cyan (`#7CB7E8`), sage emerald (`#47B881`), and vermilion (`#E05252`).
  - `Type.kt`: Local bundled **Fraunces** (`R.font.fraunces`) for display/headlines, **DM Sans** (`R.font.dm_sans`) for titles/body/actions, and **JetBrains Mono** (`R.font.jetbrains_mono`) for telemetry/tags/timestamps.
  - `Theme.kt`: Material 3 `CineStreamDarkColorScheme` + crisp editorial `CineStreamShapes` (`4dp` / `8dp` / `12dp` / `16dp` / `22dp`).
- **Home (`ui/home/`):** `HomeScreen.kt` (editorial hero reading `BuildConfig.VERSION_NAME`, unified hairline-divided Stream Dock, adaptive 2-column TV vs single-column phone layout), `ContinueWatchingRow.kt` (16:9 cinema stills), `HomeV24Sections.kt` (Quick Tools, Global Search, poster-backed Watchlist), `JsonPlaylistUi.kt`.
- **Player (`ui/player/` — 15 files):** `PlayerScreen.kt`, `PlayerControlsOverlay.kt` (including Android TV Brightness pill right next to `Sub`), `PlayerOverlaysV24.kt` (Up-Next, Skip Intro, Hold-2×, Dual Subtitles, Channel Zap EPG banner), `PlayerStatsOverlay.kt`, `CinemaSeekBar.kt` (with chapter ticks & A-B loop range), `PlayerGestures.kt` (Mobile vertical `BrightnessHudOverlay` matching `VerticalVolumeBoostHud`, Android TV `TvBrightnessControlPanel` with 10%–150% dim/boost filter, and `ZoomHudBadge`), `VolumeBoostUi.kt`, `TracksSheet.kt`, `SubtitleSheet.kt` (File/URL loader, track switcher, sync delay, appearance customizer, and Dual Subtitles), `SeriesDetailSheet.kt`, `JsonPlaylistSheet.kt`, `M3uPlaylistSheet.kt`, `NetworkPrivacySheet.kt`, `DownloadsSheet.kt`, `LocalNetworkSheet.kt`.
- **Remote & Shared (`ui/remote/`, `ui/components/`, `ui/icons/`):** `WebRemoteSheet.kt`, `PosterMediaCard.kt`, `FormatBadge.kt`, `LocalFilePicker.kt`, `TvUtils.kt`, and compact local vector icons (`ExtendedRoundedIcons.kt` replacing the ~15 MB `material-icons-extended` DEX).

---

## 4. State-Frequency Isolation Pattern

To keep Compose frame times under 4 ms on low-RAM Android TVs:
1. **`CinePlayerManager.state: StateFlow<PlayerPlaybackState>`** — Emits only on discrete transitions (play/pause, buffering, tracks, decoder, error, `surfaceResetToken`).
2. **`CinePlayerManager.progressState: StateFlow<PlaybackProgressState>`** — Emits every 500 ms and is collected **only** inside leaf timeline composables (`PlayerTimestampBadge`, `PlayerIsolatedSeekBar`, `DualSubtitleOverlay`).
3. **`CinePlayerManager.networkStats: StateFlow<PlayerNetworkStats>`** — Polled **only** when Stats for Nerds is enabled (strictly 0% overhead when hidden).

---

## 5. Deterministic Resource Lifecycle

When exiting `PlayerScreen` (or switching decoders):
1. Persist exact playback position + per-video audio/subtitle delay to Room v4.
2. Execute `CinePlayerManager.stopAndTrimBuffers()`:
   - `player.playWhenReady = false`
   - `player.clearVideoSurface()` (releases 24–40 MB 4K GPU/ION surfaces)
   - `player.stop()` and `player.clearMediaItems()`
   - `allocator.trim()` (returns 64 KB Java heap segments)
   - `parallelPrefetchManager.clearAll()` (releases in-flight segment RAM)
   - `volumeBooster.release()` (releases native AudioFX)
3. Pop back to `HomeScreen` with a clean heap and zero leaked surfaces.
