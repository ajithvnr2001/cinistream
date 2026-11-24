# Playlists, Live TV EPG, NAS & Hybrid In-App CDN Guide (v1.25)

CineStream v1.25 supports **Multi-JSON Movie & Multi-Season Series catalogs**, **M3U / M3U8 Live TV + XMLTV EPG guides**, **WebDAV & Jellyfin LAN streaming**, **Phone-to-TV Web Remote & Local Wi-Fi Sync**, automatic **IMDb metadata enrichment**, and a **5-Layer Hybrid In-App CDN & Transport Acceleration Engine**.

---

## 1. Multi-JSON Playlist Catalogs (`JsonPlaylistParser` & `MultiJsonPlaylistReducer`)

### Multiple Simultaneous Feeds
- Load and persist **multiple** remote JSON playlist URLs (plus local `.json` files) simultaneously; all saved URLs reload concurrently on startup (with per-URL failure tolerance so one offline feed never blocks others).
- `MultiJsonPlaylistReducer` namespaces item IDs across feeds to eliminate collisions, powers **`All (N)` + per-playlist switcher chips** in both Home and Player, supports inline `+` adding, per-list or Refresh-All syncing, individual playlist removal, and cross-playlist Series/Next-Episode continuity.

### Supported Top-Level JSON Shapes (4 MB Safety Cap)
1. **Wrapped catalog object:** `{ "title": "...", "cdnMirrors": [...], "headers": {...}, "items": [...] }`
2. **Split arrays:** `{ "movies": [...], "series": [...] }` (merged automatically)
3. **Top-level array:** `[ { "type": "movie", ... }, { "type": "series", "seasons": [...] } ]`
4. **Categorized catalog:** `{ "categories": [ { "name": "Sci-Fi", "videos": [...] } ] }`
5. **Single item JSON:** A standalone movie or series object.

### Unified Movie + Multi-Season Series Example
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
      "description": "A conductor investigates his girlfriend's disappearance.",
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
              "subtitles": [
                {
                  "url": "https://primary-edge.example.com/series/s1/e1-en.srt",
                  "label": "English"
                }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```
Flat `"episodes"` arrays (with `"season"` / `"seasonNumber"`) are also grouped into seasons automatically.

### Field Alias Reference
| Concept | Accepted JSON Field Names |
| :--- | :--- |
| **Playlist Title** | `title`, `name`, `playlistName`, `playlist_title` |
| **Catalog Arrays** | `items`, `movies`, `series`, `shows`, `tvShows`, `tv_shows`, `videos`, `playlist`, `channels`, `streams`, `media`, `entries`, `results`, `data`, `catalog`, `tracks`, `categories[].*` |
| **Item Type** | `type`, `contentType`, `content_type`, `mediaType`, `media_type`, `kind` (`"series"`, `"show"`, `"tv"`, `"tvshow"`, or auto-detected via `seasons`/`episodes`) |
| **Stream URL** | `url`, `streamUrl`, `stream_url`, `videoUrl`, `video_url`, `src`, `source`, `link`, `hls`, `stream`, `file`, `playUrl`, `play_url`, `sources[0]`, `streams[0]` |
| **Item Mirrors** | `mirrors`, `cdnUrls`, `cdn_urls`, `backupUrls`, `fallbackUrls`, plus extra entries in `sources` / `streams` |
| **Playlist CDN Mirrors** | `cdnMirrors`, `cdn_mirrors`, `mirrorHosts`, `fallbackHosts`, `cdnBases` |
| **Custom HTTP Headers** | `headers`, `httpHeaders`, `http_headers` (inherited Playlist → Series → Season → Episode; child overrides parent) |
| **Poster / Artwork** | `poster`, `posterUrl`, `poster_url`, `image`, `imageUrl`, `image_url`, `thumbnail`, `thumb`, `cover`, `artwork`, `banner`, `logo`, `tvgLogo`, `tvg-logo`, `backdrop` |
| **IMDb Identifier** | `imdbId`, `imdb_id`, `imdb`, `imdbUrl`, `imdb_url`, `imdbLink`, `imdb_link`, `tconst`, `tt` |
| **Rating & Year** | `imdbRating`, `imdb_rating`, `rating`, `score`; `year`, `releaseYear`, `release_year`, `datePublished` |
| **Subtitles** | `subtitle`, `subtitleUrl`, `subtitle_url`, `captions`, `captionUrl`, `srt`, `vtt`, `subtitles[]`, `captions[]`, `tracks[]` |
| **Group / Genre** | `group`, `groupTitle`, `group_title`, `group-title`, `category`, `genre`, `genres[]` |

### Series Continuity Features
- **Per-Episode Resume & `Watched` Badges:** `SeriesDetailSheet` displays per-episode progress bars and emerald `Watched` pills backed by Room v3.
- **Up-Next 10s Countdown & Skip Intro (`+85s`):** Floating smoked-glass Up-Next card with *Play Now* / *Cancel* near episode end (auto-advancing across season boundaries) plus a *Skip Intro (+85s)* pill during intros.

---

## 2. 5-Layer Hybrid In-App CDN & Transport Engine

1. **Multi-CDN Mirror Failover (`CdnMirrorResolver`):** Builds a deduplicated candidate chain (Primary URL → per-item `mirrors` → path-preserving `cdnMirrors` origin swaps). Permanent HTTP 4xx or parse failures fail fast (`C.TIME_UNSET`) and hop to the next mirror at the exact playback millisecond.
2. **Live-Streaming Parallel Prefetch (`ParallelPrefetchEngine`):** Streams `2×` (TV) / `3×` (Mobile) parallel HLS segments + `2–3×` progressive `206 Content-Range` lookahead with 0 ms live mid-download handoff.
3. **Device-Aware Edge Cache (`MediaEdgeCache`):** **RAM-Direct** (zero eMMC writes) on Android TV/low-RAM devices; **128 MB** LRU `SimpleCache` with `128 KB` buffered `CacheDataSink` on phones/tablets (automatically bypassed on decode errors and for manifests/subtitles/local files).
4. **Unified Custom HTTP Headers:** Inherited `Authorization`, `Referer`, `Origin`, and `User-Agent` headers sync across both Cronet (HTTP/3) and OkHttp (HTTP/2).
5. **HTTP/3 QUIC + Self-Healing HTTP/2 + Origin Pre-Warming:** Automatic Cronet HTTP/3 upgrade, `QuicThroughputWatchdog` demotion if UDP-443 is throttled, IPv4-first DoH DNS caching, and debounced `HEAD` origin preconnects.

---

## 3. M3U / M3U8 Live TV & XMLTV EPG Guide (`M3uPlaylistParser` & `XmltvEpgParser`)

```m3u
#EXTM3U url-tvg="https://epg.example.com/guide.xml.gz"
#EXTINF:-1 tvg-id="news.hd" tvg-logo="https://example.com/logo.png" group-title="News",Example News HD
https://stream.example.com/news/master.m3u8
```
- Automatically detects `url-tvg` / `x-tvg-url` attributes from `#EXTM3U` headers (or load any `.xml` / `.xml.gz` XMLTV URL manually inside `M3uPlaylistSheet`).
- Displays live **Now** & **Next** program titles with progress bars inside `M3uPlaylistSheet` and the floating **Channel Zap EPG Banner**.
- Switch channels instantly via `CH-`/`CH+` buttons or Android TV remote `CHANNEL_UP`/`CHANNEL_DOWN` keys.

---

## 4. Local Network Streaming (WebDAV & Jellyfin) (`LocalNetworkClient`)

- Add home NAS (**WebDAV** via `PROPFIND`) or **Jellyfin** servers from Home → **NAS · Jellyfin** (`LocalNetworkSheet`).
- Credentials (passwords / Jellyfin API tokens) are encrypted via **Android Keystore AES-256-GCM** (`CredentialVault`) before persistence in Room v3 `network_servers`.
- Supports directory drill-down, back navigation, and direct playback with inherited Basic Auth / Jellyfin token headers.

---

## 5. Phone-to-TV Web Remote & Local Wi-Fi Sync (`LocalWebRemoteServer` & `DeviceSyncCodec`)

- Open **Web Remote** on your Android TV (or phone) to start the embedded LAN HTTP server displaying a scannable **QR code** (`http://<lan-ip>:<port>`) and a **6-digit session PIN** (validated in constant time).
- From any phone browser on the same Wi-Fi network, push:
  - Direct video/stream URLs
  - JSON catalog or M3U playlist URLs
  - External subtitle URLs
  - XMLTV EPG URLs
  - NordVPN Access Keys
- **Local Sync (Phone ↔ TV):** Transfer Watchlist, Continue Watching, history, saved playlists, and playback preferences directly over LAN (`/api/sync/export` & `/api/sync/import`) or export/import a `.json` backup via Android's Storage Access Framework (SAF). Sensitive secrets (NordVPN keys, OpenSubtitles passwords, NAS passwords) are **never** included in sync snapshots.

---

## 6. Automatic IMDb Enrichment (`ImdbMetadataClient`)

Any JSON item with an `imdbId` or `imdbUrl` (`tt1772240`) lacking an explicit poster or rating is enriched asynchronously over the shared `OkHttpClient` and displayed with crisp IMDb pills on Home and Catalog cards.
