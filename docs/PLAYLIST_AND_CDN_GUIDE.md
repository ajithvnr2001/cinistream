# JSON Playlists, Series & Hybrid In-App CDN Guide

CineStream v1.16 supports unified **Movie + Multi-Season Series JSON catalogs**, **M3U / M3U8 IPTV playlists**, automatic **IMDb metadata enrichment**, and a **4-Layer Hybrid In-App CDN & Transport Acceleration Engine** built directly into the player.

---

## 1. Unified JSON Playlist Schema Reference

CineStream's `JsonPlaylistParser` accepts remote HTTP/HTTPS URLs or local `.json` files (capped at **4 MB** per file to protect low-RAM Android TVs). It flexibly supports **all** of these top-level shapes:

1. **Wrapped catalog object:** `{ "title": "...", "cdnMirrors": [...], "headers": {...}, "items": [...] }`
2. **Split arrays in one file:** `{ "movies": [...], "series": [...] }` (both arrays are merged automatically!)
3. **Top-level array:** `[ { "type": "movie", ... }, { "type": "series", "seasons": [...] } ]`
4. **Categorized catalog:** `{ "categories": [ { "name": "Sci-Fi", "videos": [...] } ] }`
5. **Single item JSON:** A standalone movie or series JSON object.

### Full Unified Example (Movies + Multi-Season Series + Multi-CDN + Headers + Subtitles)

```json
{
  "title": "My Cinema & TV Catalog",
  "cdnMirrors": [
    "https://backup-edge-1.example.com",
    "https://backup-edge-2.example.com"
  ],
  "headers": {
    "Referer": "https://media.example.com/",
    "Authorization": "Bearer YOUR_OPTIONAL_TOKEN"
  },
  "items": [
    {
      "id": "the-hidden-face-2011",
      "type": "movie",
      "title": "The Hidden Face",
      "url": "https://primary-edge.example.com/movies/hidden-face/master.m3u8",
      "mirrors": [
        "https://direct-fallback.example.org/movies/hidden-face-1080p.mp4"
      ],
      "format": "HLS",
      "group": "Mystery · Thriller",
      "imdbId": "tt1772240",
      "year": "2011",
      "poster": "https://images.example.com/posters/hidden-face.jpg",
      "description": "A Spanish orchestra conductor investigates his girlfriend's disappearance.",
      "subtitle": "https://primary-edge.example.com/subs/hidden-face-en.vtt",
      "subtitleLabel": "English"
    },
    {
      "id": "open-cinema-anthology",
      "type": "series",
      "title": "Open Cinema Anthology",
      "group": "Sci-Fi · Animation",
      "imdbId": "tt2285752",
      "year": "2012",
      "description": "Multi-season anthology with automatic next-episode playback.",
      "seasons": [
        {
          "season": 1,
          "title": "Season 1 · Classics",
          "episodes": [
            {
              "id": "anthology-s1-e1",
              "episode": 1,
              "title": "Tears of Steel",
              "url": "https://primary-edge.example.com/series/s1/e1/master.m3u8",
              "mirrors": [
                "https://fallback.example.org/series/s1/e1.mp4"
              ],
              "format": "HLS",
              "duration": "12m",
              "description": "Warriors and scientists stage a crucial event from the past.",
              "subtitles": [
                {
                  "url": "https://primary-edge.example.com/series/s1/e1-en.srt",
                  "label": "English"
                }
              ]
            },
            {
              "id": "anthology-s1-e2",
              "episode": 2,
              "title": "Sintel",
              "url": "https://primary-edge.example.com/series/s1/e2/playlist.m3u8",
              "format": "M3U8",
              "duration": "15m"
            }
          ]
        }
      ]
    }
  ]
}
```

### Supported Flat-Episodes Series Shape
You can also omit the `"seasons"` wrapper and pass a flat `"episodes"` array; `JsonPlaylistParser` groups episodes automatically by `"season"` / `"seasonNumber"` (defaulting to Season 1):

```json
{
  "id": "mini-series-1",
  "type": "series",
  "title": "Six-Part Mini-Series",
  "episodes": [
    { "season": 1, "episode": 1, "title": "Pilot", "url": "https://cdn.example.com/ep1.m3u8" },
    { "season": 1, "episode": 2, "title": "Chapter 2", "url": "https://cdn.example.com/ep2.m3u8" }
  ]
}
```

### Field Alias Reference

| Concept | Accepted JSON Field Names |
| :--- | :--- |
| **Playlist Title** | `title`, `name`, `playlistName`, `playlist_title` |
| **Catalog Arrays** | `items`, `movies`, `series`, `shows`, `tvShows`, `tv_shows`, `videos`, `playlist`, `channels`, `streams`, `media`, `entries`, `results`, `data`, `catalog`, `tracks`, `categories[].*` |
| **Item Type** | `type`, `contentType`, `content_type`, `mediaType`, `media_type`, `kind` (`"series"`, `"show"`, `"tv"`, `"tvshow"`, or auto-detected if `seasons`/`episodes` exist) |
| **Stream URL** | `url`, `streamUrl`, `stream_url`, `videoUrl`, `video_url`, `src`, `source`, `link`, `hls`, `stream`, `file`, `playUrl`, `play_url`, `sources[0]`, `streams[0]` |
| **Item Mirrors** | `mirrors`, `cdnUrls`, `cdn_urls`, `backupUrls`, `fallbackUrls`, plus secondary entries in `sources` / `streams` |
| **Playlist CDN Mirrors** | `cdnMirrors`, `cdn_mirrors`, `mirrorHosts`, `fallbackHosts`, `cdnBases` |
| **Custom HTTP Headers** | `headers`, `httpHeaders`, `http_headers` (inherited Playlist → Series → Season → Episode; child keys override parent keys) |
| **Poster / Artwork** | `poster`, `posterUrl`, `poster_url`, `image`, `imageUrl`, `image_url`, `thumbnail`, `thumb`, `cover`, `artwork`, `banner`, `logo`, `tvgLogo`, `tvg-logo`, `backdrop` |
| **IMDb Identifier** | `imdbId`, `imdb_id`, `imdb`, `imdbUrl`, `imdb_url`, `imdbLink`, `imdb_link`, `tconst`, `tt` |
| **Rating & Year** | `imdbRating`, `imdb_rating`, `rating`, `score`; `year`, `releaseYear`, `release_year`, `datePublished` |
| **Subtitles** | `subtitle`, `subtitleUrl`, `subtitle_url`, `captions`, `captionUrl`, `srt`, `vtt`, `subtitles[]`, `captions[]`, `tracks[]` |
| **Group / Genre** | `group`, `groupTitle`, `group_title`, `group-title`, `category`, `genre`, `genres[]` |

### Series Playback Experience
- **Series Detail Modal (`SeriesDetailSheet`):** Tapping a Series card opens season tabs and a full episode list (D-pad navigable on Android TV) and immediately pre-warms the first episode's origin host in the background.
- **In-Player `Next Ep` Pill & Auto-Advance:** When watching a series episode, a **Next Ep** action appears in the player bar, the next episode's origin host is pre-warmed silently, and playback automatically advances to the next episode (including across season boundaries) when the current episode finishes.

---

## 2. 4-Layer Hybrid In-App CDN & Transport Engine

CineStream ships an in-app CDN and transport engine engineered for instant startup, maximum throughput over Cloudflare Workers / S3 / R2 / Backblaze origins, and automatic resilience against origin downtime — with **zero server lock-in**.

### Layer 1: Multi-CDN Mirror Resolver (`CdnMirrorResolver`) + Fast Failover
For every movie or episode, `CdnMirrorResolver.buildCandidateUrls()` builds a deduplicated, ordered list of playable origins:
1. **Primary URL** (`item.url`)
2. **Explicit per-item / per-episode mirrors** (`item.mirrors` + extra entries in `sources` / `streams`)
3. **Path-preserving origin swaps** generated from playlist-level `cdnMirrors`

*Path-preserving host swap example:*
- Primary URL: `https://worker-a.workers.dev/pub/movie-1/master.m3u8?token=abc`
- Playlist `cdnMirrors`: `["https://cdn-b.example.com"]`
- Auto-generated backup URL: `https://cdn-b.example.com/pub/movie-1/master.m3u8?token=abc`

**Automatic Mid-Stream Failover:**
- `FastStreamingLoadErrorHandlingPolicy` retries brief transient Wi-Fi drops rapidly (`500 ms`), but **fails fast immediately** (`C.TIME_UNSET`) on permanent HTTP 4xx (`403`, `404`, `410`) or manifest parsing errors.
- `CinePlayerManager` then automatically hops to the next mirror **at the exact current playback millisecond** and displays a subtle failover toast. Users can also hop manually between CDN nodes.

### Layer 2: Device-Aware Caching (`MediaEdgeCache`: TV RAM-Direct vs. Mobile Edge Disk Cache)
- **On Android TV / Constrained-RAM Devices (`FEATURE_LEANBACK || isLowRamDevice || maxMemory <= 400 MB`):**
  - Runs in **RAM-Direct Mode** (zero synchronous eMMC disk writes, zero SQLite index updates on ExoPlayer's loader thread). Slow TV eMMC flash contention can never throttle high-bitrate HLS/DASH downloads; ExoPlayer's 20–45s heap-capped RAM buffer + 5s back-buffer handles smooth playback and instant micro-rewinds directly from memory.
- **On Modern Phones & Tablets (Fast UFS Flash):**
  - Wraps Media3's `SimpleCache` in `cacheDir/media_edge_cache` with a **128 MB** LRU cap (`4 MB` cache fragment size) and an enlarged **`128 KB` `CacheDataSink` write buffer** (6× larger than Media3's 20 KB default).
  - **CACHED:** Progressive streams & HLS/DASH media segments (`.ts`, `.m4s`, `.mp4`, `.mkv`, `.webm`, `.aac`) → instant seek-back and replay.
  - **BYPASSED:** `.m3u8`, `.m3u`, `.mpd`, `.ism/manifest` (so live streams & expiring signed tokens never go stale), subtitles (`.srt`, `.vtt`, `.ass`, `.ssa`, `.ttml`), and local `content://` / `file://` files.
  - **Fault Tolerance:** Uses `FLAG_IGNORE_CACHE_ON_ERROR` and automatically sets `bypassEdgeCacheForSession = true` on any video decoder error so a truncated cached chunk can never crash decoders twice.

### Layer 3: Custom HTTP Origin Headers (Synced Across HTTP/3 & HTTP/2)
Many S3/Cloudflare Workers/private CDN endpoints require `Authorization`, `Referer`, `Origin`, or custom `User-Agent` headers.
- Headers defined at the **Playlist**, **Series**, **Season**, or **Episode** level merge automatically (`child` overrides `parent`) and are applied through `AdaptiveHttpDataSourceFactory.setDefaultRequestProperties(...)` so they apply consistently across **both** Play Services Cronet (HTTP/3 QUIC) and OkHttp (HTTP/2).

### Layer 4: Adaptive HTTP/3 QUIC Transport + Origin Pre-Warming (`StreamingNetworkEngine`)
- **HTTP/3 (QUIC) + TLS 1.3 + BBR:** Automatically upgrades Cloudflare Workers / modern CDN origins to HTTP/3 QUIC via Google Play Services Cronet even on Android 9 TVs, falling back seamlessly to HTTP/2 OkHttp + 5-minute IPv4-first `FastDnsResolver`.
- **Safe Origin Pre-Warming:** When a playlist loads, when opening a Series drawer, or when starting an episode with a next episode on another host, CineStream sends a debounced (60s cooldown) background `HEAD scheme://host[:port]/` request to warm DNS + TLS/QUIC sockets without ever consuming single-use tokenized media URLs or downloading media bytes.

---

## 3. M3U / M3U8 IPTV Playlists (`M3uPlaylistParser`)

CineStream also supports standard IPTV `#EXTM3U` playlists (from remote URL or local file):
```m3u
#EXTM3U
#EXTINF:-1 tvg-logo="https://example.com/logo.png" group-title="News",Example News HD
https://stream.example.com/news/master.m3u8
```
- Groups channels by `group-title`, displays channel logos via RGB_565 Coil, supports instant search/filtering, and detects live streams (`● LIVE HLS` badge).

---

## 4. Automatic IMDb Metadata Enrichment (`ImdbMetadataClient`)

Whenever a JSON playlist item provides an `imdbId` or `imdbUrl` (e.g., `tt1772240` or `https://www.imdb.com/title/tt1772240/`) without an explicit poster URL or rating, `ImdbMetadataClient` fetches poster artwork, rating, and year asynchronously over the shared `OkHttpClient` (with `FastDnsResolver` caching) and hydrates the catalog cards seamlessly.
