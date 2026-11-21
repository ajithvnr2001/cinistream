# Decoder, Transport & Playback Engine Deep Dive

CineStream v1.16 pairs an adaptive **HTTP/3 (QUIC) Cronet + HTTP/2 OkHttp** network stack with a hybrid **Hardware MediaCodec + Native FFmpeg** playback pipeline built on **AndroidX Media3 (`1.11.1`)**, **Google Play Services Cronet (`18.1.1`)**, and **NextLib (`io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0`)**.

This document explains how network transport, error recovery, video/audio decoders, and surface handoffs work end-to-end across both Android phones and low-RAM Android 9 TVs.

---

## 1. Adaptive Network Transport Engine (`StreamingNetworkEngine.kt`)

### A. Asynchronous Google Play Services Cronet (HTTP/3 QUIC + TLS 1.3 + BBR)
On Android 9 TVs, the platform networking stack only provides **OpenSSL TLS 1.2** (2-RTT handshake) and **TCP CUBIC** (which stalls an entire HTTP/2 connection whenever a single Wi-Fi packet drops — *TCP Head-of-Line blocking*).

At startup (`CineStreamApplication.onCreate()`), CineStream asynchronously initializes Chromium's **Cronet** engine via Google Play Services (`com.google.android.gms:play-services-cronet:18.1.1`, ~150 KB APK overhead):

```kotlin
nativeProvider.createBuilder()
    .setUserAgent(userAgent)
    .enableHttp2(true)
    .enableQuic(true)   // HTTP/3 over UDP QUIC + TLS 1.3 + 0-RTT + BBR congestion control
    .enableBrotli(true)
    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0L)
    .build()
```

**Critical Safeguards Built Into `StreamingNetworkEngine`:**
1. **Strict Exclusion of `PROVIDER_NAME_FALLBACK`:** Cronet's Java fallback wraps `HttpURLConnection` (serial HTTP/1.1). CineStream explicitly filters out `CronetProvider.PROVIDER_NAME_FALLBACK` so non-GMS devices **never** degrade to HTTP/1.1 — they stay on our faster **HTTP/2 `OkHttpDataSource`**.
2. **Zero-Crash Dynamic Switching (`AdaptiveHttpDataSourceFactory`):** Starts immediately with HTTP/2 OkHttp (0 ms launch delay). The moment Play Services Cronet completes initialization off the main thread, new stream requests automatically route through `CronetDataSource` (with `.setFallbackFactory(okHttpFactory)` as a secondary safety net) while keeping all custom CDN headers (`Referer`, `Authorization`, `Origin`) synchronized across both engines.
3. **Bounded Threading & Zero Duplicate Caching:** Cronet callbacks run on a bounded 4-thread daemon pool (`CineStream-Net`) and `HTTP_CACHE_DISABLED` ensures Cronet never allocates redundant RAM/disk caches on 1 GB TVs.

### B. 5-Minute IPv4-First In-Memory DNS Cache (`FastDnsResolver`)
Attached to `CineStreamApplication.sharedOkHttpClient`:
- Caches DNS resolutions in memory for **5 minutes** (bounded to 128 host entries; flushed on `onTrimMemory`/`onLowMemory`).
- Sorts resolved `InetAddress` lists with **IPv4 (`Inet4Address`) first, IPv6 (`Inet6Address`) second** — eliminating dual-stack IPv6 route stalls on ISPs like JioFiber while keeping full IPv6 fallback on IPv6-only networks.
- Never caches empty responses or failed lookups.

### C. Smart Cold-Start Bitrate Estimates & Origin Pre-Warming
- **Initial Bitrate Estimates (`DefaultBandwidthMeter`):** Instead of ExoPlayer's ~1 Mbps cold-start assumption (which starts HLS frame 1 in blurry 480p), CineStream seeds realistic starting estimates:
  - **TV / Constrained Wi-Fi:** `6 Mbps` (starts crisp in 720p/1080p immediately)
  - **Mobile / Tablet Wi-Fi:** `10 Mbps`
  - **Ethernet / 5G:** `12 Mbps`, **4G:** `5 Mbps`
- **Debounced Origin Pre-Warming (`StreamingNetworkEngine.prewarmOrigin`):**
  - Extracts only the safe `scheme://host[:port]/` root (never touches single-use tokenized media URLs or downloads video bytes).
  - Sends a background `HEAD` (3s timeout, debounced 60s per origin) when a JSON playlist loads, when opening a Series drawer, or ahead of the next episode — shaving 300–500 ms of DNS + TLS/QUIC handshake time before the user hits Play.

### D. Fast Transient Wi-Fi Retry Policy (`FastStreamingLoadErrorHandlingPolicy`)
Applied to **both** `HlsMediaSource.Factory` and `DefaultMediaSourceFactory` (covering HLS, DASH, MKV, MP4, WebM, and TS):
- **Transient Network / Timeout / HTTP 5xx / 408 / 429 Errors:** Retries up to 4 times with **`500 ms` → `1200 ms` → `2500 ms` → `4000 ms`** backoff (cutting first-retry wait in half compared to ExoPlayer's `1000 ms` default).
- **Permanent Client / Parse Errors (HTTP 400–499 except 408/429, `ParserException`, `FileNotFoundException`, `POSITION_OUT_OF_RANGE`):** Returns `C.TIME_UNSET` immediately so **Multi-CDN Mirror Failover** activates without wasting seconds retrying a dead origin.

---

## 2. Renderer Architecture (`NextRenderersFactory`)

Inside `CinePlayerManager`, ExoPlayer is built with `NextRenderersFactory` rather than stock `DefaultRenderersFactory`:

```kotlin
private val renderersFactory = NextRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    .setEnableDecoderFallback(true)
    .forceDisableMediaCodecAsynchronousQueueing()
    .setMediaCodecSelector(smartMediaCodecSelector)
    .setAllowedVideoJoiningTimeMs(5_000L)
```

### What This Registers
With `EXTENSION_RENDERER_MODE_ON`, Media3 registers renderers in priority order:

1. **Primary Video Renderer:** `MediaCodecVideoRenderer` (using `SmartMediaCodecSelector`)
   - Handles GPU/DSP hardware decoding (`c2.qti.hevc.decoder`, `c2.mtk.*`, `OMX.*`) for minimum battery, heat, and smooth 4K60 playback.
2. **Fallback / Software Video Renderer:** **`FfmpegVideoRenderer`** (from NextLib)
   - Native ARM64 / ARMv7a NEON FFmpeg software decoder supporting **10-bit HEVC (`Main 10`)**, 8-bit HEVC, H.264, VP8, VP9, and **AV1 (`dav1d`)**.
3. **Primary Audio Renderer:** `MediaCodecAudioRenderer` (hardware/platform Stereo DSP AAC, MP3, Opus, etc.)
4. **Fallback Audio Renderer:** **`FfmpegAudioRenderer`** (from NextLib)
   - Automatically decodes **DTS (`dca`), AC-3, E-AC-3, TrueHD, FLAC, ALAC, Vorbis, Opus, and AAC** whenever the Android device lacks a platform decoder (very common on Android TVs and phones playing MKV rips).

### Why `forceDisableMediaCodecAsynchronousQueueing()` Is Mandatory
On Android 12–15 Qualcomm Snapdragon devices (particularly Xiaomi MIUI / HyperOS), Media3's asynchronous `MediaCodec` input buffer queueing can trigger native Codec 2.0 race conditions inside `c2.qti.hevc.decoder`, causing sudden mid-stream `ERROR_CODE_DECODING_FAILED` crashes on high-bitrate HEVC bitstreams. Forcing synchronous queueing eliminates this class of driver crashes while acting as a safe no-op on Android 9 TVs.

---

## 3. `SmartMediaCodecSelector` & Zero-Recreation FFmpeg Handoff

Standard ExoPlayer creates renderers **once** at `ExoPlayer.Builder.build()`. Recreating `ExoPlayer` every time the user switches between HW and SW would allocate duplicate buffers, risk leaking `MediaCodec` hardware slots, and stall playback.

CineStream avoids recreating `ExoPlayer` by controlling renderer selection dynamically through `SmartMediaCodecSelector`:

### Step-by-Step Decision Flow for Video MIME Types (`video/*`)

1. **Explicit Software / FFmpeg Mode (`effectiveDecoderMode == VideoDecoderMode.SOFTWARE`):**
   - If native FFmpeg is loaded (`FfmpegLibrary.isAvailable() == true`) and secure DRM isn't required:
   - `SmartMediaCodecSelector.getDecoderInfos()` returns an **empty list (`mutableListOf()`)**.
   - Media3's `MediaCodecVideoRenderer` sees 0 matching platform codecs → immediately yields the track to the next video renderer in the chain: **`FfmpegVideoRenderer`**!
   - Result: Instant handoff to native **10-bit FFmpeg** at the exact playback millisecond with **zero** `ExoPlayer` recreation.
   - If FFmpeg is unavailable (e.g., JVM unit tests), `SmartMediaCodecSelector` falls back to ordering Android's built-in software codecs (`c2.android.*`, `OMX.google.*`) first.

2. **Hardware / Auto Mode (`AUTO` or `HARDWARE`):**
   - Queries `MediaCodecSelector.DEFAULT.getDecoderInfos(...)`.
   - **Dolby Vision Compatibility:** If `mimeType == "video/dolby-vision"` and non-DRM, appends `video/hevc` decoders so Dolby Vision Profile 7/8 MKV/MP4 rips play cleanly on non-DV screens via their base HEVC layer.
   - **`.low_latency` WebRTC Codec Exclusion:** Strips any codec matching `.low_latency`, `_low_latency`, or `.lowlatency` (such as **`c2.qti.hevc.decoder.low_latency`**).
     - *Why this matters:* On Snapdragon phones, when `c2.qti.hevc.decoder` fails on a 4K 10-bit HDR10 (`hvc1.2.4.H150.B0`) movie, Android's built-in `c2.android.hevc.decoder` rejects 10-bit (8-bit only), causing stock Media3 to fall into `c2.qti.hevc.decoder.low_latency` — a WebRTC video-call codec with no B-frame support that immediately throws `Decoder init failed`. Filtering `.low_latency` ensures Media3 skips straight to `FfmpegVideoRenderer`!
   - **Session Failed-Codec Blocklist:** Strips any codec that previously threw a decode error in this session (`sessionBlockedCodecs`). When all platform codecs are filtered out and FFmpeg is available (`allowEmptyAfterFiltering = true`), returns empty list so `FfmpegVideoRenderer` takes over automatically.

---

## 4. Clean HW ↔ FFmpeg Surface Handoff (`surfaceResetToken`)

Native `FfmpegVideoRenderer` renders decoded frames to the Android `Surface` using `ANativeWindow_lock` (`NATIVE_WINDOW_API_CPU`), whereas `MediaCodecVideoRenderer` attaches the hardware BufferQueue producer (`NATIVE_WINDOW_API_MEDIA`). On many Android drivers, handing the exact same `Surface` back and forth between CPU lock and `MediaCodec.configure()` can fail with `Surface already connected`.

CineStream prevents this deterministically:
1. Before reloading with a new decoder (`forceResetRenderer = true`), `CinePlayerManager` calls:
   ```kotlin
   player.clearVideoSurface()
   player.stop()
   player.clearMediaItems()
   allocator.trim()
   ```
2. It increments `PlayerPlaybackState.surfaceResetToken`.
3. `PlayerScreen` wraps `AndroidView(PlayerView)` inside `key(playbackState.surfaceResetToken)`.
4. Compose releases the old `PlayerView` (`playerView.player = null`) and mounts a fresh `PlayerView` with a brand-new untouched `SurfaceView`.

---

## 5. Multi-Tier Automatic Decoder Recovery Chain

When `onPlayerError(error: PlaybackException)` detects a decoder error (`ERROR_CODE_DECODER_INIT_FAILED`, `ERROR_CODE_DECODING_FAILED`, `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`, `MediaCodecDecoderException`, `FfmpegDecoderException`, etc.), CineStream executes this ordered recovery chain:

```text
Decoder Error Caught (e.g., c2.qti.hevc.decoder failed at 14:22)
   │
   ├──► Step 0 (Always):
   │      • bypassEdgeCacheForSession = true (stop reading potentially corrupt disk cache)
   │      • Add failed codec name to sessionBlockedCodecs
   │
   ├──► Tier 1A (HW failed in AUTO mode):
   │      • Switch effectiveDecoderMode = VideoDecoderMode.SOFTWARE
   │      • Reset surface (surfaceResetToken++), trim allocator
   │      • Resume at exact timestamp (14:22) using native FFmpeg 10-bit SW
   │      • Show toast: "Decoder failed (c2.qti.hevc.decoder) -> Switched to Software (SW)"
   │
   ├──► Tier 1B (Android SW failed first in AUTO mode):
   │      • Switch effectiveDecoderMode = VideoDecoderMode.HARDWARE
   │      • Resume at exact timestamp with sync queueing + cache bypassed
   │
   ├──► Tier 2 (Both HW & SW failed on current adaptive track):
   │      • DecoderFallbackHelper.pickCompatibleFallbackTrack():
   │          1. Prefer untried H.264 (video/avc) or non-HEVC track (highest res first)
   │          2. Otherwise pick next lower-resolution track below failing height
   │      • Apply TrackSelectionOverride, clear codec blocklist, resume at exact timestamp
   │
   └──► Tier 3 (Unrecoverable single-track error -> PlayerErrorCard):
          • Displays readable diagnostic hint + active codec name
          • Offers 1-tap actions:
              [Try Software / Hardware Decoder]
              [Skip Bad Segment (+5s)]   (jumps past corrupt GOP/segment with cache bypassed)
              [Retry]  [Back]  [Try as HLS / M3U8 Stream]
```

---

## 6. Tolerant HLS HEVC Extraction

Many self-hosted or FFmpeg-transcoded HLS HEVC streams begin `.ts` segments with **CRA / BLA** non-IDR random-access pictures rather than strict IDR frames. Stock Media3 TS extractors drop or mis-frame these access units, crashing both HW and SW decoders.

CineStream configures both `DefaultExtractorsFactory` and an explicit `HlsMediaSource.Factory` with:
```kotlin
private val tolerantTsFlags: Int =
    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

private val hlsMediaSourceFactory = HlsMediaSource.Factory(smartEdgeDataSourceFactory)
    .setExtractorFactory(DefaultHlsExtractorFactory(tolerantTsFlags, true))
    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    .setAllowChunklessPreparation(true)

private val mediaSourceFactory = DefaultMediaSourceFactory(smartEdgeDataSourceFactory, extractorsFactory)
    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
```
When external subtitles are attached to an HLS stream, they are cleanly merged via `MergingMediaSource(hlsSource, SingleSampleMediaSource...)` using the direct (uncached) data source so subtitle tracks stay in sync without polluting the video edge cache.

---

## 7. 0%–200% Audio Volume Boost Engine (`AudioVolumeBooster.kt`)

| Volume Slider | ExoPlayer `player.volume` | `LoudnessEnhancer` Gain | `DynamicsProcessing` Soft Limiter (API 28+) |
| :--- | :--- | :--- | :--- |
| `0%` | `0.0f` (Muted) | Disabled (`0 mB`) | Disabled |
| `1%–100%` | `0.01f–1.0f` | Disabled (`0 mB`) | Disabled |
| `150%` | `1.0f` | `+1000 mB` (`+10.0 dB`) | Enabled (`-1.0 dB` ceiling, `6:1` ratio) |
| `200%` | `1.0f` | `+2000 mB` (`+20.0 dB`) | Enabled (`-1.0 dB` ceiling, `6:1` ratio) |

- Attaches directly to ExoPlayer's active `audioSessionId` (works across phone speakers, Bluetooth headphones, and Android TV HDMI/optical output with **zero extra permissions**).
- Effects are lazily instantiated only when volume exceeds `100%` and deterministically released on `stopAndTrimBuffers()` and `release()`.

---

## 8. Precision Scrubbing (`SeekBarScrubEngine` & `CLOSEST_SYNC`)

- `player.setSeekParameters(SeekParameters.CLOSEST_SYNC)` snaps seeks to the nearest sync keyframe for instant multi-minute scrubbing on remote MKV/MP4/HLS streams without decoding hundreds of intermediate frames.
- `SeekBarScrubEngine` calculates smooth touch drag deltas and progressive D-pad hold acceleration for Android TV remotes.
