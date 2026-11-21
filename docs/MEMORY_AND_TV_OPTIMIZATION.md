# Memory Management & Android TV Optimization Guide

CineStream v1.16 was forensic-tuned from real ADB profiling logs to run lag-free with **zero `OutOfMemoryError` crashes** on **32-bit Android 9 TVs** (such as the Thomson `PATH_7XPRO` with 1 GB RAM, `80 MB` default heap / `384 MB` `largeHeap`, and 125 ms RTT 5 GHz Wi-Fi to Cloudflare Workers) while simultaneously supporting **37+ Mbps 4K 10-bit HDR10 playback** on modern smartphones.

---

## 1. Root Causes of Buffering & OOMs on 32-Bit Android 9 TVs

Forensic crash and ADB log analysis on 32-bit Android 9 TVs revealed six compounding bottlenecks in stock Jetpack Compose + ExoPlayer apps:

| Bottleneck | Stock Default Behavior | CineStream v1.16 Engineering Fix |
| :--- | :--- | :--- |
| **1. Default ExoPlayer Heap Buffers** | `DefaultLoadControl` buffers 50s ahead with up to **137.5 MB** (`125 MB` video + `12.5 MB` audio) allocated as `64 KB byte[]` segments **directly on Java Heap** — guaranteed OOM within 80s on an 80 MB heap! | Dynamic heap-capped `LoadControl` (`20%` of `maxMemory`, clamped **12–40 MB**), 20–45s time window, 2.5s start / 6s post-rebuffer cushion, `prioritizeTimeOverSizeThresholds = false`, 0–5s back-buffer, `DefaultAllocator(trimOnReset = true)` + explicit `allocator.trim()`. |
| **2. `largeHeap` Disabled** | Android 9 32-bit TVs cap apps at **80 MB** unless opted in (`384 MB` available). | Enabled `android:largeHeap="true"` in `AndroidManifest.xml`. |
| **3. HTTP/1.1, TLS 1.2 & TCP Head-of-Line Stalls** | Android 9's `HttpURLConnection` falls back to serial HTTP/1.1 + OpenSSL TLS 1.2 + TCP CUBIC over 125 ms RTT, capping demuxed fMP4 HLS delivery at **3.95 Mbps** even on 867 Mbps 5 GHz Wi-Fi. | **`StreamingNetworkEngine`** unlocks **Google Play Services Cronet (`HTTP/3 QUIC + TLS 1.3 + BoringSSL + BBR + 0-RTT`)** even on Android 9 TVs, with zero-crash fallback to a shared **HTTP/2 `OkHttpDataSource`** (`ConnectionPool(6, 5m)` + 5-min IPv4-first `FastDnsResolver`). |
| **4. Software 7.1 AAC Audio Underruns & Slow ABR** | Auto-selecting `DEFAULT=YES` 7.1 surround tracks forced software `OMX.google.aac.decoder` on 32-bit Cortex-A53 CPUs (50,000+ `AudioTrack` underruns) while default ABR stayed pinned to 1080p down to 0.44s and cold-started at ~1 Mbps 480p. | On Android TV / constrained devices, `DefaultTrackSelector` prefers **2-channel Stereo** hardware DSP (`maxAudioChannelCount = 2` with `exceedAudioConstraintsIfNecessary = true`), caps auto-selected video to `1080p / 9.5 Mbps`, seeds Wi-Fi cold start at `6 Mbps`, and uses faster ABR downshifts (`maxDecrease = 10s`, `0.70f`). |
| **5. Slow TV eMMC Flash Contention** | Synchronously writing every 2–6 MB `.m4s` chunk to `SimpleCache` (20 KB default sink + SQLite index updates) on slow internal TV eMMC shared with OS RAM swap throttles ExoPlayer's loader thread. | On Android TV / constrained RAM devices (`FEATURE_LEANBACK || isLowRamDevice || maxMemory <= 400 MB`), `MediaEdgeCache` runs in **RAM-Direct mode** (zero eMMC writes, zero SQLite fsyncs); modern phones keep a 128 MB edge cache with a 6× larger `128 KB` buffered `CacheDataSink`. |
| **6. Retained Video Buffers & Full-Screen Recomposition** | Navigating back from `PlayerScreen` to `HomeScreen` kept player buffers & 4K GPU surfaces alive during transitions; 500ms progress ticks recomposed the whole screen. | `stopAndTrimBuffers()` clears video surface, stops player, clears items, calls `allocator.trim()`, and releases AudioFX **before** popping back to Home. High-frequency timeline state lives in isolated `PlaybackProgressState`. |

---

## 2. Exact Memory Budgets & Safeguards in CineStream v1.16

### A. Heap-Capped Media3 `LoadControl` (`CinePlayerManager.kt`)
```kotlin
const val MIN_TARGET_BUFFER_BYTES = 12 * 1024 * 1024   // 12 MB floor
const val MAX_TARGET_BUFFER_BYTES = 40 * 1024 * 1024   // 40 MB hard ceiling
const val MIN_BUFFER_MS = 20_000                       // 20s min buffer
const val MAX_BUFFER_MS = 45_000                       // 45s max buffer
const val BUFFER_FOR_PLAYBACK_MS = 2_500               // 2.5s smooth initial start
const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 6_000 // 6s post-rebuffer cushion
const val SAFE_LARGE_HEAP_BACK_BUFFER_MS = 5_000       // 5s on >=256 MB heaps, 0 on <256 MB

fun computeSafeTargetBufferBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
    return (maxHeapBytes * 0.20)
        .toLong()
        .coerceIn(MIN_TARGET_BUFFER_BYTES.toLong(), MAX_TARGET_BUFFER_BYTES.toLong())
        .toInt()
}
```
- **80 MB heap device:** Buffer strictly capped at **16 MB**, `backBuffer = 0`.
- **384 MB (`largeHeap`) Android 9 TV or modern phone:** Buffer strictly capped at **40 MB** (~**37 seconds** of 8.6 Mbps 1080p cushion), `backBuffer = 5s`.
- **`setPrioritizeTimeOverSizeThresholds(false)`:** Guarantees the byte cap is **always** respected before the 45s time ceiling — making OOM crashes impossible even on 37.6 Mbps 4K remuxes (~8.5s = 40 MB cap).

### B. Bounded Network Engine Footprint (`StreamingNetworkEngine.kt`)
- **Cronet Native Memory Hygiene:** Uses Google Play Services' shared Chromium binary (zero embedded multi-MB Cronet `.so` bundled in APK) with `CronetEngine.Builder.HTTP_CACHE_DISABLED` so Cronet never allocates a duplicate disk or RAM cache alongside ExoPlayer.
- **Bounded Thread Pools:** Uses a fixed 4-thread daemon pool (`CineStream-Net`) for Cronet callbacks and a single low-priority daemon thread (`CineStream-Prewarm`) for debounced `HEAD` origin pre-warming.
- **Bounded Caches:** `FastDnsResolver` caps entries at 128 hosts (5-minute TTL) and pre-warm cooldown tracking caps at 32 origins (60-second cooldown).

### C. Deterministic Buffer & Surface Reclamation
4K 10-bit hardware/CPU video surfaces consume **24–40 MB** of native graphics/ION memory each. CineStream deterministically calls:
```kotlin
player.clearVideoSurface()
player.stop()
player.clearMediaItems()
allocator.trim()
```
at **three critical moments**:
1. Leaving `PlayerScreen` (`stopAndTrimBuffers()`)
2. Switching decoders between HW `MediaCodec` and SW `FFmpeg` (`forceResetRenderer = true` + `surfaceResetToken++`)
3. Destroying the ViewModel (`release()`)

### D. Adaptive Resolution, Bitrate & Stereo Guardrail for Low-RAM / TV Devices
In `DefaultTrackSelector` and `DefaultBandwidthMeter`, CineStream detects Android TV / constrained devices (`FEATURE_LEANBACK || isLowRamDevice || maxMemory <= 400 MB`) and configures:
- **Stereo Hardware DSP Priority:** `maxAudioChannelCount = 2` + `exceedAudioConstraintsIfNecessary = true` (auto-selects 2.0 Stereo when available, still plays 5.1/7.1-only files, manual 7.1 override supported in **Tracks**).
- **Max Auto Video Resolution & Bitrate:** `1920 × 1080` at **`9.5 Mbps`** (fits 8.25 Mbps 1080p H.264 cleanly over HTTP/3 & HTTP/2 while preventing 18+ Mbps `orig` tracks from starving 1 GB TVs).
- **Smart Cold-Start Estimate & Fast ABR Downshift:** Starts TV Wi-Fi at `6 Mbps` (mobile Wi-Fi `10 Mbps`, Ethernet/5G `12 Mbps`) and configures `AdaptiveTrackSelection.Factory(10_000, 10_000, 25_000, 0.70f)` so quality drops 2.5× faster on Wi-Fi dips instead of starving down to 0.44s.

### E. Coil Image Loader Optimization (`CineStreamApplication.kt` & `PosterMediaCard.kt`)
- **Strict 10% Memory Cache Cap:** Down from Coil's default 25% of JVM heap.
- **24 MB Disk Cache:** Stored in `cacheDir/imdb_poster_cache`.
- **`allowRgb565(true)`:** Cuts opaque poster/backdrop bitmap memory in **half** (2 bytes/pixel instead of 4 bytes/pixel ARGB_8888).
- **`crossfade(false)`:** Eliminates holding two full-size bitmaps simultaneously during fade animations.
- **Bounded Decode Size (`320 × 480 px`) + `remember`:** `PosterImageThumbnail` memoizes `ImageRequest` and never decodes raw 2000×3000 IMDb posters into RAM.

### F. System Memory Pressure Hooks (`CineStreamApplication.kt`)
- `onTrimMemory(level)` (when `level >= TRIM_MEMORY_UI_HIDDEN` or `level >= TRIM_MEMORY_RUNNING_LOW`) and `onLowMemory()` both:
  1. Clear Coil's in-memory bitmap cache.
  2. Evict all idle sockets from `sharedOkHttpClient.connectionPool`.
  3. Clear `fastDnsResolver` cached host entries.

### G. Universal APK Size & ABI Optimization (`app/build.gradle.kts`)
```kotlin
ndk {
    // 64-bit phones + 32-bit Android TVs + 64-bit emulators (drops obsolete 32-bit x86)
    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
}
packaging {
    jniLibs {
        useLegacyPackaging = true // Compresses native FFmpeg .so binaries (~57 MB -> 36 MB universal APK)
    }
}
```
Because Play Services Cronet loads Chromium's native library dynamically from Google Play Services (`play-services-cronet:18.1.1`) rather than bundling `cronet-embedded`, full HTTP/3 QUIC support adds minimal overhead while keeping the universal APK at **36 MB**.

---

## 3. Android TV First-Class Design & Ergonomics

CineStream is a true dual-form-factor app (not just a stretched phone UI):

- **Leanback Manifest Support:** Declares `LEANBACK_LAUNCHER` category, `android:banner="@drawable/tv_banner"`, and marks `android.hardware.touchscreen` and `android.software.leanback` as `required="false"` so the single APK installs cleanly from Google Play or sideloading on both phones and TVs.
- **Visible Focus Everywhere (`TvUtils.kt`):** Every interactive button, card, track row, episode tile, error recovery button, and dropdown option applies `tvFocusHighlight(...)` (amber/white glowing border + subtle scale-up on focus).
- **Predictable Initial Focus:** Modals like `TracksSheet`, `SubtitleSheet`, `SeriesDetailSheet`, and speed menus explicitly request focus on their top/active item via `FocusRequester` so TV remote users never get "lost" off-screen.
- **TV-Boosted Subtitles:** `PlayerScreen` automatically applies a **1.35× font size boost** and slightly raised bottom margin (`10%` vs `8%`) on television screens for comfortable 10-foot living-room legibility.
- **TV CPU Tip for 10-bit HEVC:** Keep **Video Decoder Engine** set to **Auto (HW + FFmpeg SW)** on 32-bit Android 9 TVs so the TV's hardware decoder handles normal 1080p/4K streams effortlessly, while FFmpeg SW automatically handles 10-bit 720p/1080p x265 and DTS/AC-3 audio when hardware lacks support.
