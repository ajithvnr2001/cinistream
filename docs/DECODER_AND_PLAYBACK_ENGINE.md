# Decoder, Transport, External Audio, VPN & Playback Engine Deep Dive (v1.39)

CineStream v1.39 combines a self-healing **HTTP/3 (QUIC) Cronet + HTTP/2 OkHttp + Live-Streaming Parallel Prefetch + Embedded WireGuard NordLynx** networking stack with a hybrid **Hardware MediaCodec + NextLib Native FFmpeg** playback pipeline built on **AndroidX Media3 (`1.11.1`)**, **Play Services Cronet (`18.1.1`)**, **NextLib (`1.11.1-0.16.0`)**, and **`com.wireguard.android:tunnel` (`GoBackend`)**. It supports merging remote and offline-bundled external audio streams (`.m3u8` HLS, DASH, and direct `.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`, etc.) with unbounded `±60 min` bidirectional sync, automatic JSON playlist audio resolution, and an 8× parallel HLS / 4× multi-chunk resumable offline download engine with external audio sidecar persistence. Authenticated WebDAV streams automatically route through `OkHttpDataSource` (stripping `Authorization` on cross-origin CDN redirects) and bypass unencrypted disk cache.

---

## 1. Adaptive Network Transport & Self-Healing QUIC (`StreamingNetworkEngine.kt`)

### A. Asynchronous Play Services Cronet (HTTP/3 QUIC + TLS 1.3 + BBR)
On Android 9 TVs, stock `HttpURLConnection` drops to OpenSSL TLS 1.2 + TCP CUBIC (suffering TCP Head-of-Line stalls over Wi-Fi). `CineStreamApplication.onCreate()` asynchronously initializes Chromium's Cronet engine via Google Play Services (`play-services-cronet:18.1.1`):
- **Strict `PROVIDER_NAME_FALLBACK` Exclusion:** Explicitly skips Cronet's Java `HttpURLConnection` wrapper so non-GMS devices stay on our faster **HTTP/2 `OkHttpDataSource`**.
- **Zero-Delay Dynamic Switching (`AdaptiveHttpDataSourceFactory`):** Launches immediately on HTTP/2 OkHttp and seamlessly routes new requests to Cronet HTTP/3 once ready, synchronizing all custom headers (`Referer`, `Authorization`, `Origin`, `User-Agent`) across both factories.
- **Bounded Resources:** 4-thread daemon pool (`CineStream-Net`) and `HTTP_CACHE_DISABLED` to avoid duplicate RAM/disk caching.

### B. RAM-Hit-Immune `QuicThroughputWatchdog`
Some ISPs throttle UDP port 443 (QUIC) while leaving TCP 443 (HTTP/2) fast. `QuicThroughputWatchdog`:
- Monitors pure `BandwidthMeter` socket samples (ignoring `1 ms` RAM prefetch hits) and mid-playback rebuffer stalls.
- Demotes Cronet HTTP/3 → IPv4-first **HTTP/2 TCP** after 3 slow windows (`< 2.5 Mbps`) or `>= 2` stalls at `< 2.5 Mbps` (or QUIC I/O errors), immediately reconnecting at the exact millisecond.
- Users can also override transport persistently in **Tracks → Network Transport (`Auto` / `HTTP/2` / `HTTP/3`)**.

### C. Fast IPv4-First DNS Cache + Encrypted DoH (`FastDnsResolver` + `DohDnsClient`)
- 5-minute TTL bounded DNS cache (128 hosts, cleared on `onTrimMemory`), sorting `Inet4Address` before `Inet6Address` while preserving IPv6-only networks.
- 1-tap encrypted DNS-over-HTTPS via direct bootstrap IPs (**Cloudflare `1.1.1.1` / `1.0.0.1`** or **Google `8.8.8.8` / `8.8.4.4`**) to bypass ISP DNS blocking without root.

### D. Embedded WireGuard NordLynx (`NordLynxEngine.kt`) & Smart Auto-VPN Failover
- Exchanges your NordVPN Access Key via `NordVpnApiClient` (`api.nordvpn.com`) for a Curve25519 `nordlynx_private_key`, stored encrypted in **Android Keystore AES-256-GCM** (`CredentialVault`).
- Syncs **118+ live NordVPN countries & cities**, selects the lowest-load `wireguard_udp` server + public key, and opens a per-app WireGuard tunnel (`GoBackend`, `includeApplication`) with Cloudflare `1.1.1.1` DNS inside CineStream—zero external VPN apps required.
- **`SmartVpnFailoverCoordinator`:** When enabled, plays over direct ISP first and auto-connects NordLynx only if a stream hits repeated low-bandwidth stalls or fatal network blocks.

### E. Origin Pre-Warming & Fast Transient Recovery
- **Cold-Start Bitrate Seeding:** `6 Mbps` (TV Wi-Fi), `10 Mbps` (Phone Wi-Fi), `12 Mbps` (Ethernet/5G), `5 Mbps` (4G).
- **Debounced `HEAD` Preconnects:** Warms `scheme://host[:port]/` (60s cooldown) when loading catalogs, opening Series drawers, or queueing the next episode.
- **`FastStreamingLoadErrorHandlingPolicy`:** Retries transient Wi-Fi/5xx errors in `500 ms → 1200 ms → 2500 ms → 4000 ms`; fails fast (`C.TIME_UNSET`) on permanent 4xx / parser errors so Multi-CDN mirror failover triggers immediately.

---

## 2. Live-Streaming Parallel Prefetch Engine (`ParallelPrefetchEngine.kt`)

- **HLS Playlist Teeing (`HlsSegmentIndex`):** Intercepts `.m3u8` responses inline with 0 ms delay to map upcoming media segments.
- **Focused Bounded Concurrency:**
  - **Android TV:** `2×` parallel lookahead, `<= 14 MB` total RAM cap, `12 MB` single-segment ceiling.
  - **Mobile / Tablet:** `3×` parallel lookahead, `<= 28 MB` total RAM cap, `16 MB` single-segment ceiling.
- **Zero-Wait Live Streaming Handoff (`StreamingPrefetchEntry`):** If ExoPlayer requests a segment already mid-download, it attaches immediately and consumes live 32 KB chunks as they stream in—no waiting for download completion and no mid-transfer aborts.
- **Dedicated Audio Lane & Anti-Starvation:** Demuxed audio segments prefetch on an isolated lane; during seeks or low buffer (`< 8s`) lookahead drops to `1×` foreground-first; during software FFmpeg decoding, prefetch concurrency throttles automatically to preserve CPU headroom.
- **Progressive Byte-Range Lookahead:** Accelerates direct `.mp4` / `.mkv` / `.webm` streams via `2–3×` parallel `206 Content-Range` slices (1.5–2 MB each) with automatic single-stream fallback for servers without range support.

---

## 3. Hybrid Hardware + Native FFmpeg Decoding (`NextRenderersFactory` & `DecoderEngine.kt`)

```kotlin
private val renderersFactory = NextRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    .setEnableDecoderFallback(true)
    .setMediaCodecSelector(smartMediaCodecSelector)
    .setAllowedVideoJoiningTimeMs(5_000L)
```

### Renderer Chain
1. **`MediaCodecVideoRenderer` (GPU Hardware):** Primary path for battery-efficient 1080p/4K60 playback.
2. **`FfmpegVideoRenderer` (NextLib Native FFmpeg):** Native ARM64 / ARMv7a NEON software decoder supporting **10-bit HEVC (`Main 10`, HDR10)**, 8-bit HEVC, H.264, VP8/VP9, and **AV1 (`dav1d`)**.
3. **`MediaCodecAudioRenderer` + `FfmpegAudioRenderer`:** Decodes platform AAC/Opus/MP3 plus native FFmpeg **DTS (`dca`), AC-3, E-AC-3, TrueHD, FLAC, ALAC, Vorbis, and Opus**—with bidirectional audio sync applied upstream at the `SampleStream` boundary (`AudioSyncMediaSource`) so both PCM and HDMI passthrough stay locked.

### Zero-Recreation FFmpeg Handoff (`SmartMediaCodecSelector`)
- **Explicit Software Mode (`VideoDecoderMode.SOFTWARE`):** Returns an empty platform list when `FfmpegLibrary.isAvailable()` is true so `MediaCodecVideoRenderer` yields directly to `FfmpegVideoRenderer` at the exact timestamp without recreating `ExoPlayer`.
- **`.low_latency` WebRTC Codec Exclusion:** Strips `c2.qti.hevc.decoder.low_latency` and similar low-latency call codecs that lack B-frame support and crash on VOD HDR10 streams.
- **Dolby Vision Fallback:** Appends `video/hevc` decoders for `video/dolby-vision` tracks so Profile 7/8 rips play on non-DV displays via their HEVC base layer.
- **Clean Surface Handoff (`surfaceResetToken`):** Clears the video surface (`player.clearVideoSurface()`), trims allocator, and increments `surfaceResetToken` (`key(surfaceResetToken)` in `PlayerScreen`) whenever switching between `MediaCodec` (`NATIVE_WINDOW_API_MEDIA`) and `FfmpegVideoRenderer` (`NATIVE_WINDOW_API_CPU`), preventing `Surface already connected` driver crashes.

---

## 4. Frame-Drop Diagnostics & Selective Async Queueing (`FrameDropAnalyzer.kt`)

- **Root-Cause Classification (`FrameDropAnalyzer`):** Tracks rolling 3-second `drops/sec` alongside seek/start grace windows and active decoder type:
  - **`HEALTHY`:** Steady playback with zero or negligible drops.
  - **`SEEK_BURST`:** Harmless burst during initial stream join or seeks.
  - **`STREAM_PTS_OR_RESYNC`:** Drops under hardware decoding indicating malformed origin timestamps (PTS gaps), VFR jitter, or segment discontinuities on the URL/origin side.
  - **`SW_CPU_LOAD`:** Sustained drops under software FFmpeg decoding indicating CPU saturation (resolved by switching to HW or a lower resolution/H.264 track).
- **Selective MediaCodec Queueing (`MediaCodecQueueingPolicy`):** Keeps synchronous queueing on Qualcomm Snapdragon Codec 2.0 devices prone to async race crashes, while allowing Android 12+ asynchronous queueing where safe (`Async`/`Sync` shown in Stats for Nerds).
- **Display Refresh-Rate Matching:** Uses `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS` to reduce 24fps→60Hz pulldown judder without black-screen HDMI mode switches.

---

## 5. Multi-Tier Automatic Decoder Recovery Chain

1. **Step 0:** Set `bypassEdgeCacheForSession = true` and add the failed codec name to `sessionBlockedCodecs`.
2. **Tier 1A (HW failed in `AUTO`):** Switch to `VideoDecoderMode.SOFTWARE` (native FFmpeg 10-bit), reset `surfaceResetToken`, and resume at the exact millisecond.
3. **Tier 1B (SW failed first in `AUTO`):** Switch to `VideoDecoderMode.HARDWARE` with cache bypassed.
4. **Tier 2 (Both HW & SW failed on current adaptive track):** `DecoderFallbackHelper.pickCompatibleFallbackTrack()` prefers an untried H.264 (`video/avc`) track or the next lower resolution below the failing height.
5. **Tier 3 (`PlayerErrorCard`):** Displays the exact failing codec and offers 1-tap **Switch to Software/Hardware**, **Skip Segment (+5s)**, **Retry**, **Try as HLS**, and **Back**.

---

## 6. Audio DSP, Lip-Sync Delay, Subtitles & Playback Comfort

### A. Volume Boost (`0%–200%`) & Night Mode (`AudioVolumeBooster.kt`)
- **0%–100%:** Native `player.volume` scaling (`0.0f..1.0f`).
- **101%–200%:** `LoudnessEnhancer` (`+0` to `+2000 mB` / `+20 dB`) paired with an API 28+ `DynamicsProcessing` soft-limiter (`-1.0 dB` ceiling, `6:1` ratio) to prevent clipping.
- **Night Mode (Dialogue Boost):** API 28+ 4-band `DynamicsProcessing` Pre-EQ + multi-band compressor lifting `1–4 kHz` speech frequencies while compressing loud bass/explosions.

### B. Container- & Codec-Agnostic Unbounded Audio Sync (`AudioSyncMediaSource.kt`, `DelayAudioProcessor.kt`)
- **`AudioSyncMediaSource` / `AudioSyncMediaPeriod` / `AudioOffsetSampleStream`:** Wraps every primary `MediaSource` (muxed `.ts` HLS, demuxed `#EXT-X-MEDIA:TYPE=AUDIO` `.ts`/`.aac`/`.m4s` HLS segments, DASH, MKV, MP4) and shifts `C.TRACK_TYPE_AUDIO` sample timestamps (`buffer.timeUs`) directly before decoding, leaving video, subtitles, and the seekbar locked together.
- **Unbounded Custom Range (`±60 min` / `±3,600,000 ms`):** `AudioDelayMath` supports arbitrary positive/negative sync adjustments with coarse (`-5s`, `-1s`, `+1s`, `+5s`) and fine (`-50ms`, `-25ms`, `+25ms`, `+50ms`) steppers plus direct numeric input supporting **`ms`**, **`s`**, and **`m`/`min`** inline suffixes (e.g., `5m`, `-2.25min`, `1500ms`, `-3.5s`).
- Works identically across PCM, AAC, Opus, AC-3, E-AC-3, DTS, TrueHD, HDMI/Bluetooth passthrough, hardware `MediaCodec`, and `FfmpegAudioRenderer` with zero PCM ring-buffer RAM overhead and zero silence injection.
- Applies exact in-buffer realignment (`SeekParameters.EXACT` + anchor-locking + 180 ms debounce for rapid step taps, immediate on numeric Apply), protects delayed audio buffers in `discardBuffer()`, adjusts `getBufferedPositionUs()` for negative delays so `LoadControl` fetches ahead on demuxed HLS segments, drops sub-zero leading samples cleanly, and persists per-video offsets in Room v4.

### C. External Audio URL Engine & Auto-Loaded Playlist Audio (`ExternalAudioMediaSource.kt`, `CinePlayerManager.kt`)
- **`ExternalAudioUrlInspector` (v1.37–v1.39):** Accepts remote `http://` and `https://` audio streams for online URL attachment (strictly rejecting untrusted `file://` user input / control characters) while allowing app-verified local `file://` sidecars for offline downloads. Detects HLS (`.m3u8`, `/hls/`, `format=m3u8`), DASH (`.mpd`), and direct audio formats (**`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`**, `.aac`, `.dts`, `.opus`, `.ogg`, `.mka`, `.weba`, `.ts`). For extensionless URLs, performs a bounded 2 KB `Range: bytes=0-2047` HTTP peek on `Dispatchers.IO` to detect `#EXTM3U` HLS playlists, `<MPD` DASH manifests, and `Content-Type` headers (`audio/mpeg`, `audio/mp3`, `audio/ac3`, `audio/vnd.dolby`, `audio/eac3`, `audio/ec-3`, `audio/flac`, `audio/x-flac`, `audio/wav`, `audio/x-wav`, `audio/wave`, `audio/x-m4a`, `audio/mp4`, `audio/mp4a`).
- **Dedicated Direct HTTP & Local File Lane:** External audio sources use isolated `HlsMediaSource.Factory` / `DefaultMediaSourceFactory` instances backed by `DefaultDataSource.Factory(context, AdaptiveHttpDataSourceFactory)`—bypassing the primary video's `ParallelPrefetchManager` online, and natively reading local `file://` `.m3u8`/`.mp3`/`.ac3`/`.flac`/`.wav`/`.m4a` sidecars offline through NextLib FFmpeg + MediaCodec audio decoders.
- **`AudioOnlyFilteringMediaSource` & `AudioOnlyFilteringMediaPeriod`:** Wraps external sources (including muxed HLS master `.m3u8` URLs) so **only** `C.TRACK_TYPE_AUDIO` track groups are exposed to `MergingMediaSource`. Foreign video/subtitle tracks are stripped cleanly, each audio format is tagged with `ext_audio_<id>:<group>:<track>` (clearing `Format.labels` via `.setLabels(emptyList())` before `.setLabel(...)` to satisfy Media3 `1.11.1` `Format.Builder` invariants) with `[HLS]` / `[EXT]` badges, and `ExoTrackSelection` calls are transparently remapped back to the delegate's original `TrackGroup`.
- **`ExternalAudioSyncMediaSource` & `ExternalAudioSyncMediaPeriod`:** Translates `prepare`, `seekToUs`, `selectTracks`, `continueLoading`, `discardBuffer`, `reevaluateBuffer`, and `getBufferedPositionUs` independently by `audioDelayUs` (`delegatePosUs = (timelinePosUs - audioDelayUs).coerceAtLeast(0L)`) while wrapping streams in `AudioOffsetSampleStream` and returning `C.TIME_UNSET` from `readDiscontinuity()` to satisfy `MergingMediaPeriod` contracts. When an external audio track is active, `AudioDelayMath.computeRealignSeekPositionMs(..., isExternalAudioActive = true)` pins the video on its exact anchor frame while only the external audio seeks to the shifted offset (even across multi-minute offsets like `+5m` or `-5m`).
- **Mismatched Duration Safety & Automatic Recovery:** Merged via `MergingMediaSource(/* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true, syncedPrimarySource, *externalAudioSources, *subSources)` so an external audio track longer or shorter than the video (e.g., 1 hr video + 1 hr 5 min audio) merges and plays cleanly clipped to the timeline without `IllegalMergeException`. If an external audio URL fails during initial prepare, `CinePlayerManager.onPlayerError` automatically detaches the failing track, surfaces a clear error banner in `TracksSheet`, preserves any other valid tracks, and resumes primary video playback seamlessly at the exact timestamp.
- **`PlaylistAudioResolver` (v1.38):** Automatically resolves `PlaylistAudioSpec` entries from JSON playlist items (`eg-playlist-v3.json`), deduplicates URLs, selects the entry marked `"default": true` (or the first track unless all explicitly set `"default": false`), and applies any item-level initial `delayMs` when no user-tuned delay is saved in Room.

### D. HLS Extraction-Time Subtitle Transcoding & Dual Subtitles (`SubtitleHelper.kt`, `SecondarySubtitleEngine.kt`)
- **Media3 `1.11.1` Sidecar Transcoding (`SubtitleMediaSourceBuilder`):** Media3 `1.11.1` disables legacy subtitle decoding in `TextRenderer` (`expected application/x-media3-cues`). Rather than feeding raw `SingleSampleMediaSource` bytes into `MergingMediaSource` for HLS streams, `SubtitleMediaSourceBuilder.buildMediaSource()` wraps `.srt`, `.vtt`, `.ass`/`.ssa`, and `.ttml` sidecar files in `ProgressiveMediaSource` + `SubtitleExtractor(DefaultSubtitleParserFactory().create(format), format)` so captions are transcoded to `application/x-media3-cues` during extraction.
- **Embedded HLS Subtitles:** `DefaultHlsExtractorFactory(tolerantTsFlags, true)` enables `.experimentalParseSubtitlesDuringExtraction(true)` and formats cue codecs cleanly via `SubtitleMediaSourceBuilder.formatSubtitleCodecLabel()`.
- **Dual Subtitles:** `SecondarySubtitleEngine` parses `.srt`/`.vtt` files into binary-searchable cue windows and renders a secondary caption line at the top of the screen while Media3 renders primary captions at the bottom.

### E. High-Speed Parallel & Resumable Offline Download Engine with Loaded External Audio Bundling (`OfflineDownloadManager.kt`, `DownloadQualitySheet.kt` — v1.33–v1.39)
- **8× Parallel HLS Segment Engine:** Downloads up to 4,000 segments concurrently with per-segment retries, atomic `.part` → final file promotion, AES-128 key fetching, `#EXT-X-MAP` init segments, `#EXT-X-BYTERANGE`, and separate `#EXT-X-MEDIA:TYPE=AUDIO` audio playlists rewritten into self-contained local `.m3u8` manifests inside app-private `filesDir/offline_downloads/`.
- **4× Multi-Chunk Progressive Engine:** Splits MP4/MKV/WebDAV files into 4 concurrent `206 Partial Content` byte ranges with cross-origin `Authorization` redirect stripping, falling back cleanly to single-stream download when servers omit range support.
- **Multi-Quality × Multi-Audio + Loaded External Audio Picker (`inspectDownloadOptions` & `DownloadQualitySheet` — v1.39):**
  - Always opens `DownloadQualitySheet` whenever any loaded external audio tracks exist (from v3 JSON playlists, Tracks → Audio, or Web Remote)—across **multi-variant HLS**, **single-variant HLS**, and **progressive MP4/MKV/WebM** streams—as well as multi-variant/multi-audio HLS masters.
  - Merges loaded `DownloadExternalAudioCandidate` tracks alongside embedded tracks (`mergeExternalAudioOptions`), probing `Content-Length` or estimating format-aware audio bitrates (**FLAC ~900 kbps**, **WAV ~1,411 kbps**, **AC3 ~384 kbps**, **EAC3 ~640 kbps**, **MP3 ~192 kbps**, **M4A/AAC ~128 kbps**) so every combination shows an accurate combined Video + Audio estimated size.
  - Displays the Audio Track filter chip bar and a cyan **`Loaded`** pill badge on external audio rows inside a wrapping **`FlowRow`** header so badges never clip off narrow mobile screens.
- **Resumable External Audio Sidecar Bundling (`ext_audio/` + `offline_audio.json` — v1.39):**
  - Supports direct audio formats **`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`** (plus `.aac`, `.opus`, `.ogg`, `.dts`, `.mka`, `.weba`) downloaded into `<download_folder>/ext_audio/track.<ext>`, and external **HLS `.m3u8`** streams (both audio-only media playlists and master playlists—automatically selecting `#EXT-X-MEDIA:TYPE=AUDIO` or lowest-bandwidth rendition) downloaded into `<download_folder>/ext_audio/audio.m3u8` + segments.
  - Allocates clean continuous progress (`0–85%` video + `85–99%` external audio), persists external audio metadata and video-completion state inside `download_state.json` across pause/resume and Wi-Fi ↔ Cellular switches, and writes an atomic **`offline_audio.json`** sidecar (`relativePath`, `label`, `language`, `formatBadge`, `isHls`, `delayMs`) with zero Room schema migration.
  - On offline playback (`playOfflineDownload` & `playHistoryItem` for `file://` downloads), `OfflineDownloadManager.readOfflineSidecarAudio()` validates canonical path boundaries inside the download folder and automatically attaches the local `file://` external audio track + saved `delayMs`, while preserving the original embedded audio track for 1-tap switching offline. Deleting a download removes the entire folder (`video` + `ext_audio/` + `offline_audio.json`) cleanly.
- **Checkpoint Resume & Network Recovery:** Persists completed segments/chunks in `download_state.json` and monitors `ConnectivityManager.registerDefaultNetworkCallback` to evict stale sockets across Wi-Fi ↔ Cellular switches, auto-pause cleanly on network loss (*"Paused · Waiting for network"*), and auto-resume from the exact checkpoint once connectivity returns.

### F. Playback Comfort & Background Audio
- **PiP & Background Audio (`MediaPlaybackService.kt`):** Android 8+ PiP with custom `-10s` / `Play-Pause` / `+10s` remote actions + opt-in mobile `mediaPlayback` foreground service with lock-screen controls (automatically hidden on Android TV).
- **Sleep Timer, A-B Loop & Chapters:** `15m`/`30m`/`45m`/`60m`/`End of Episode` timer with 5-second gradual audio fade-out; visual chapter ticks and cyan A-B loop range directly on `CinemaSeekBar`.
