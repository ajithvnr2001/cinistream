# Memory Management & Android TV Optimization Guide

CineStream was specifically forensic-tuned to run smoothly with **zero `OutOfMemoryError` crashes** on **32-bit Android 9 TVs** (such as the Thomson `PATH_7XPRO` with an `80 MB` default heap / `384 MB` `largeHeap`) while simultaneously supporting **37+ Mbps 4K 10-bit HDR10 playback** on modern smartphones.

---

## 1. The Root Causes of Streaming OOMs on 32-Bit Android TVs

Forensic crash log analysis on 32-bit Android 9 TVs revealed four compounding memory traps in stock Jetpack Compose + ExoPlayer apps:

| Trap | Stock Default Behavior | CineStream Engineering Fix |
| :--- | :--- | :--- |
| **1. Default ExoPlayer Heap Buffers** | `DefaultLoadControl` buffers 50s ahead with up to **137.5 MB** (`125 MB` video + `12.5 MB` audio) allocated as `64 KB byte[]` segments **directly on Java Heap** — guaranteed OOM within 80s on an 80 MB heap! | Dynamic heap-capped `LoadControl` (`20%` of `maxMemory`, clamped **12–32 MB**), `prioritizeTimeOverSizeThresholds = false`, `backBuffer = 0`, `DefaultAllocator(trimOnReset = true)` + explicit `allocator.trim()`. |
| **2. `largeHeap` Disabled** | Android 9 32-bit TVs cap apps at **80 MB** unless opted in (`384 MB` available). | Enabled `android:largeHeap="true"` in `AndroidManifest.xml`. |
| **3. OkHttp HTTP/2 Streaming Churn** | Streaming video through `OkHttpDataSource` allocates thousands of short-lived `8 KB` `okio.Segment` buffers per second on `OkHttp TaskRunner`. | Video streaming uses Media3's `DefaultHttpDataSource` directly (zero `okio.Segment` churn). Shared `OkHttpClient` is reserved for JSON/M3U/IMDb/Coil with **zero body logging** and explicit pool eviction on memory pressure. |
| **4. Retained Video Buffers & Full-Screen Recomposition** | Navigating back from `PlayerScreen` to `HomeScreen` kept player buffers & 4K GPU surfaces alive during transitions; 500ms progress ticks recomposed the whole screen. | `stopAndTrimBuffers()` clears video surface, stops player, clears items, calls `allocator.trim()`, and releases AudioFX **before** popping back to Home. High-frequency timeline state lives in isolated `PlaybackProgressState`. |

---

## 2. Exact Memory Budgets & Safeguards in CineStream v1.14

### A. Heap-Capped Media3 `LoadControl` (`CinePlayerManager.kt`)
```kotlin
const val MIN_TARGET_BUFFER_BYTES = 12 * 1024 * 1024   // 12 MB floor
const val MAX_TARGET_BUFFER_BYTES = 32 * 1024 * 1024   // 32 MB hard ceiling
const val MIN_BUFFER_MS = 10_000                       // 10s min buffer
const val MAX_BUFFER_MS = 20_000                       // 20s max buffer
const val BUFFER_FOR_PLAYBACK_MS = 1_500               // 1.5s fast start
const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

fun computeSafeTargetBufferBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
    return (maxHeapBytes * 0.20)
        .toLong()
        .coerceIn(MIN_TARGET_BUFFER_BYTES.toLong(), MAX_TARGET_BUFFER_BYTES.toLong())
        .toInt()
}
```
- **80 MB heap device:** Buffer strictly capped at **16 MB**.
- **384 MB (`largeHeap`) Android 9 TV or modern phone:** Buffer strictly capped at **32 MB**.
- **`setPrioritizeTimeOverSizeThresholds(false)`:** Guarantees the byte cap is **always** respected before the 20s time ceiling (so even a 37.6 Mbps 4K remux buffers ~6–7s = 32 MB instead of trying to allocate 95 MB!).
- **`setBackBuffer(0, false)`:** Releases played frames immediately instead of hoarding old video frames in Java heap.

### B. Deterministic Buffer & Surface Reclamation
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

### C. Adaptive Resolution & Bitrate Guardrail for Low-RAM / TV Devices
In `DefaultTrackSelector`, CineStream detects constrained devices (`ActivityManager.isLowRamDevice == true || Runtime.getRuntime().maxMemory() <= 400 MB`) and caps automatic adaptive HLS/DASH selection to:
- **Max Resolution:** `1920 × 1080` (Full HD)
- **Max Bitrate:** `12 Mbps`
*(Users can still manually pick any resolution in the **Tracks** sheet if desired.)*

### D. Coil Image Loader Optimization (`CineStreamApplication.kt` & `PosterMediaCard.kt`)
- **Strict 10% Memory Cache Cap:** Down from Coil's default 25% of JVM heap.
- **24 MB Disk Cache:** Stored in `cacheDir/imdb_poster_cache`.
- **`allowRgb565(true)`:** Cuts opaque poster/backdrop bitmap memory in **half** (2 bytes/pixel instead of 4 bytes/pixel ARGB_8888).
- **`crossfade(false)`:** Eliminates holding two full-size bitmaps simultaneously during fade animations.
- **Bounded Decode Size (`320 × 480 px`) + `remember`:** `PosterImageThumbnail` memoizes `ImageRequest` and never decodes raw 2000×3000 IMDb posters into RAM.

### E. System Memory Pressure Hooks (`CineStreamApplication.kt`)
- `onTrimMemory(level)` (when `level >= TRIM_MEMORY_UI_HIDDEN` or `TRIM_MEMORY_RUNNING_LOW`) and `onLowMemory()` both:
  1. Clear Coil's in-memory bitmap cache.
  2. Evict all idle sockets from `sharedOkHttpClient.connectionPool`.

### F. Universal APK Size & ABI Optimization (`app/build.gradle.kts`)
```kotlin
ndk {
    // 64-bit phones + 32-bit Android TVs + 64-bit emulators (drops obsolete 32-bit x86)
    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
}
packaging {
    jniLibs {
        useLegacyPackaging = true // Compresses native FFmpeg .so binaries (~57 MB -> 34 MB APK)
    }
}
```

---

## 3. Android TV First-Class Design & Ergonomics

CineStream is a true dual-form-factor app (not just a stretched phone UI):

- **Leanback Manifest Support:** Declares `LEANBACK_LAUNCHER` category, `android:banner="@drawable/tv_banner"`, and marks `android.hardware.touchscreen` and `android.software.leanback` as `required="false"` so the single APK installs cleanly from Google Play or sideloading on both phones and TVs.
- **Visible Focus Everywhere (`TvUtils.kt`):** Every interactive button, card, track row, episode tile, error recovery button, and dropdown option applies `tvFocusHighlight(...)` (amber/white glowing border + subtle scale-up on focus).
- **Predictable Initial Focus:** Modals like `TracksSheet`, `SubtitleSheet`, `SeriesDetailSheet`, and speed menus explicitly request focus on their top/active item via `FocusRequester` so TV remote users never get "lost" off-screen.
- **TV-Boosted Subtitles:** `PlayerScreen` automatically applies a **1.35× font size boost** and slightly raised bottom margin (`10%` vs `8%`) on television screens for comfortable 10-foot living-room legibility.
- **TV CPU Tip for 10-bit HEVC:** Keep **Video Decoder Engine** set to **Auto (HW + FFmpeg SW)** on 32-bit Android 9 TVs so the TV's hardware decoder handles normal 1080p/4K streams effortlessly, while FFmpeg SW automatically handles 10-bit 720p/1080p x265 and DTS/AC-3 audio when hardware lacks support.
