# Decoder, Transport, VPN & Playback Engine Deep Dive (v1.32)

CineStream v1.32 combines a self-healing **HTTP/3 (QUIC) Cronet + HTTP/2 OkHttp + Live-Streaming Parallel Prefetch + Embedded WireGuard NordLynx** networking stack with a hybrid **Hardware MediaCodec + NextLib Native FFmpeg** playback pipeline built on **AndroidX Media3 (`1.11.1`)**, **Play Services Cronet (`18.1.1`)**, **NextLib (`1.11.1-0.16.0`)**, and **`com.wireguard.android:tunnel` (`GoBackend`)**. Authenticated WebDAV streams automatically route through `OkHttpDataSource` (stripping `Authorization` on cross-origin CDN redirects) and bypass unencrypted disk cache.

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

### B. Container- & Codec-Agnostic Bidirectional Audio Sync (`AudioSyncMediaSource.kt`, `DelayAudioProcessor.kt`)
- **`AudioSyncMediaSource` / `AudioSyncMediaPeriod` / `AudioOffsetSampleStream`:** Wraps every `MediaSource` (muxed `.ts` HLS, demuxed `#EXT-X-MEDIA:TYPE=AUDIO` `.ts`/`.aac`/`.m4s` HLS segments, DASH, MKV, MP4, and `MergingMediaSource`) and shifts `C.TRACK_TYPE_AUDIO` sample timestamps (`buffer.timeUs`) directly before decoding (`-10 s` to `+10 s`), leaving video, subtitles, and seekbar locked together.
- Works identically across PCM, AAC, Opus, AC-3, E-AC-3, DTS, TrueHD, HDMI/Bluetooth passthrough, hardware `MediaCodec`, and `FfmpegAudioRenderer` with zero PCM ring-buffer RAM overhead and zero silence injection.
- Applies exact in-buffer realignment (`SeekParameters.EXACT` + anchor-locking + 180 ms debounce for rapid step taps, immediate on direct numeric `ms`/`s` Apply), protects delayed audio buffers in `discardBuffer()`, adjusts `getBufferedPositionUs()` for negative delays so `LoadControl` fetches ahead on demuxed HLS segments, and drops sub-zero leading samples cleanly. Persisted per video in Room.

### C. HLS Extraction-Time Subtitle Transcoding & Dual Subtitles (`SubtitleHelper.kt`, `SecondarySubtitleEngine.kt`)
- **Media3 `1.11.1` Sidecar Transcoding (`SubtitleMediaSourceBuilder`):** Media3 `1.11.1` disables legacy subtitle decoding in `TextRenderer` (`expected application/x-media3-cues`). Rather than feeding raw `SingleSampleMediaSource` bytes into `MergingMediaSource` for HLS streams, `SubtitleMediaSourceBuilder.buildMediaSource()` wraps `.srt`, `.vtt`, `.ass`/`.ssa`, and `.ttml` sidecar files in `ProgressiveMediaSource` + `SubtitleExtractor(DefaultSubtitleParserFactory().create(format), format)` so captions are transcoded to `application/x-media3-cues` during extraction.
- **Embedded HLS Subtitles:** `DefaultHlsExtractorFactory(tolerantTsFlags, true)` enables `.experimentalParseSubtitlesDuringExtraction(true)` and formats cue codecs cleanly via `SubtitleMediaSourceBuilder.formatSubtitleCodecLabel()`.
- **Dual Subtitles:** `SecondarySubtitleEngine` parses `.srt`/`.vtt` files into binary-searchable cue windows and renders a secondary caption line at the top of the screen while Media3 renders primary captions at the bottom.

### D. Playback Comfort & Background Audio
- **PiP & Background Audio (`MediaPlaybackService.kt`):** Android 8+ PiP with custom `-10s` / `Play-Pause` / `+10s` remote actions + opt-in mobile `mediaPlayback` foreground service with lock-screen controls (automatically hidden on Android TV).
- **Sleep Timer, A-B Loop & Chapters:** `15m`/`30m`/`45m`/`60m`/`End of Episode` timer with 5-second gradual audio fade-out; visual chapter ticks and cyan A-B loop range directly on `CinemaSeekBar`.
