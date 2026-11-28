# Playlists, Collections, External Audio, Live TV EPG, WebDAV & Hybrid CDN Guide (v1.39)

CineStream v1.39 supports **Multi-JSON Movie, Movie Collection (Franchise) & Multi-Season Series catalogs**, **automatic JSON-defined External Audio URLs** (`eg-playlist-v3.json`, both online and bundled into mobile offline downloads across `.mp3`, `.ac3`, `.flac`, `.wav`, `.m4a`, and `.m3u8`), **M3U / M3U8 Live TV + XMLTV EPG guides**, **Multi-Account Cloud/LAN WebDAV (TorBox, Nextcloud, NAS) & Jellyfin streaming**, **Phone-to-TV Web Remote (including `load_audio`) & Local Wi-Fi Sync**, automatic **per-movie & catalog IMDb metadata enrichment**, and a **5-Layer Hybrid In-App CDN & Transport Acceleration Engine**.

---

## 1. Multi-JSON Playlist Catalogs (`JsonPlaylistParser` & `MultiJsonPlaylistReducer`)

### Multiple Simultaneous Feeds
- Load and persist **multiple** remote JSON playlist URLs (plus local `.json` files) simultaneously; all saved URLs reload concurrently on startup (with per-URL failure tolerance so one offline feed never blocks others).
- `MultiJsonPlaylistReducer` namespaces item IDs across feeds to eliminate collisions, powers **`All (N)` + per-playlist switcher chips** in both Home and Player, supports inline `+` adding, per-list or Refresh-All syncing, individual playlist removal, and cross-playlist Collection/Series/Next-Item continuity.
- **Sample Playlists Included in Repository:**
  - `eg-playlist.json` — Classic Movies & Multi-Season Series sample.
  - `eg-playlist-v2.json` — Adds **Movie Collections** (both explicit `"type": "collection"` with `movies[]` and flat `"collection"` tag auto-grouping).
  - `eg-playlist-v3.json` — Full v1.38/v1.39 schema showcasing **automatic external audio URLs** (`audios[]`, single `audio` object, and `audioUrl` string shorthands with `default` and `delayMs`) across Movies, Movie Collections, and Multi-Season Series.

### Supported Top-Level JSON Shapes (4 MB Safety Cap)
1. **Wrapped catalog object:** `{ "title": "...", "cdnMirrors": [...], "headers": {...}, "items": [...] }`
2. **Split arrays:** `{ "movies": [...], "collections": [...], "series": [...] }` (merged automatically)
3. **Top-level array:** `[ { "type": "movie", ... }, { "type": "collection", "movies": [...] }, { "type": "series", "seasons": [...] } ]`
4. **Categorized catalog:** `{ "categories": [ { "name": "Sci-Fi", "videos": [...] } ] }`
5. **Single item JSON:** A standalone movie, collection, or series object.

---

## 2. Movie Collections / Franchises (`CollectionDetailSheet` — v1.36+)

CineStream treats **Movie Collections** (trilogies, sagas, franchises, director box sets) as a dedicated first-class catalog type—distinct from TV Series—with **zero `S/E` episode badges**, per-film IMDb metadata, and one-tap franchise continuity.

### Two Supported Authoring Formats
1. **Explicit Collection Object (`"type": "collection"`):**
   Define a collection item with an ordered `movies` (or `films` / `parts`) array. Each film carries its own `title`, `url`, `year`, `imdbId`, `poster`, `duration`, `subtitles`, `audios`, `mirrors`, and optional `headers` (inheriting from playlist & collection).
2. **Flat `"collection"` Tag Auto-Grouping:**
   Tag standalone `"type": "movie"` items with `"collection": "Trilogy Name"` (plus optional `"part": 1`, `"order": 2`). `JsonPlaylistParser` automatically groups matching films into a single collection card sorted by `part`/`order` (falling back to `year` / insertion order) while keeping each film individually searchable via Global Search.

### Collection UI & Playback Features
- **Collections Filter Chip:** `JsonPlaylistSheet` provides dedicated **`All` · `Movies` · `Collections` · `Series`** filter chips alongside genre/group pills and film-count badges (`3 FILMS`).
- **`CollectionDetailSheet`:** Full-bleed backdrop hero, collection metadata, **Play / Resume Next Film** primary CTA, clean **Film 1 / Film 2** (or `Part 1`) cards with **zero `S1 E1` labels**, per-film progress bars, emerald **`Watched`** badges, **`CC`** & cyan **`AUDIO`** badges, and per-film **Offline Download** buttons (on mobile/tablet).
- **Per-Movie IMDb Enrichment:** Each individual film inside a collection with an `imdbId` (or `imdbUrl`) automatically fetches its own poster, IMDb rating, year, and plot asynchronously—without overwriting the collection's own hero artwork.
- **Auto-Next Franchise Continuity:** Finishing Film 1 offers the floating smoked-glass Up-Next countdown card to launch Film 2 seamlessly.

---

## 3. Automatic External Audio URLs in JSON Playlists (`eg-playlist-v3.json` — v1.38–v1.39)

Any **Movie**, **Collection Film**, or **Series Episode** in a JSON playlist can bundle one or more external audio stream URLs (`.m3u8` HLS audio-only or muxed master playlists, **`.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`**, `.aac`, `.dts`, `.opus`, `.ogg`, `.mka`, `.weba`, or HTTP audio-carrying `.mp4`/`.mkv`). When the user taps Play, CineStream attaches and selects the external audio stream automatically—no manual pasting required. On mobile/tablet, tapping **Download** (from the Player or `CollectionDetailSheet`) automatically surfaces all loaded external audio tracks in **`DownloadQualitySheet`** with a **`Loaded`** badge and bundles the chosen track offline alongside the video.

### Three Supported Authoring Shapes
1. **Array of Audio Track Objects (`audios`, `audioTracks`, `externalAudios`, `audioUrls`):** Supports multiple dubs/commentaries per title with individual labels, languages, default flags, and sync offsets.
2. **Single Audio Track Object (`audio`, `externalAudio`, `external_audio`):** Clean shorthand for a single dubbed or commentary track with metadata.
3. **Flat String Shorthand (`audioUrl`, `audio_url`, `dubUrl`, `audioStream`, `externalAudioUrl`):** Paired with optional sibling fields `audioLabel`, `audioLang`, `audioDefault`, and `audioDelayMs`.

### Complete v1.39 JSON Example (Movie + Collection + Series with External Audios)
```json
{
  "title": "CineStream v3 Showcase Catalog",
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
      "id": "scifi-feature-2025",
      "type": "movie",
      "title": "Orbital Horizon",
      "url": "https://primary-edge.example.com/movies/orbital/video.m3u8",
      "mirrors": [
        "https://fallback.example.org/movies/orbital-1080p.mp4"
      ],
      "format": "HLS",
      "group": "Sci-Fi · Thriller",
      "imdbId": "tt1772240",
      "year": "2025",
      "poster": "https://images.example.com/posters/orbital.jpg",
      "subtitle": "https://primary-edge.example.com/subs/orbital-en.vtt",
      "subtitleLabel": "English",
      "audios": [
        {
          "url": "https://audio-cdn.example.com/orbital/hindi-dub.m3u8",
          "label": "Hindi Dub (HLS)",
          "lang": "hi",
          "default": true,
          "delayMs": 0
        },
        {
          "url": "https://audio-cdn.example.com/orbital/director-commentary.m4a",
          "label": "Director Commentary",
          "lang": "en",
          "default": false,
          "delayMs": -1250
        }
      ]
    },
    {
      "id": "open-blender-collection",
      "type": "collection",
      "title": "Open Cinema Collection",
      "group": "Sci-Fi · Animation",
      "year": "2008–2012",
      "poster": "https://images.example.com/posters/open-collection.jpg",
      "movies": [
        {
          "id": "tears-of-steel",
          "part": 1,
          "title": "Tears of Steel",
          "year": "2012",
          "imdbId": "tt2285752",
          "duration": "12m",
          "url": "https://primary-edge.example.com/collection/tos/master.m3u8",
          "audio": {
            "url": "https://audio-cdn.example.com/tos/spanish-dub.m3u8",
            "label": "Spanish Dub",
            "lang": "es",
            "default": true,
            "delayMs": 450
          }
        },
        {
          "id": "sintel",
          "part": 2,
          "title": "Sintel",
          "year": "2010",
          "imdbId": "tt1727587",
          "duration": "15m",
          "url": "https://primary-edge.example.com/collection/sintel/master.m3u8",
          "audioUrl": "https://audio-cdn.example.com/sintel/commentary.mp3",
          "audioLabel": "Score Only",
          "audioLang": "und",
          "audioDefault": false,
          "audioDelayMs": 0
        }
      ]
    },
    {
      "id": "anthology-series",
      "type": "series",
      "title": "Open Cinema Anthology",
      "group": "Sci-Fi",
      "seasons": [
        {
          "season": 1,
          "title": "Season 1",
          "episodes": [
            {
              "id": "anthology-s1-e1",
              "episode": 1,
              "title": "Pilot",
              "url": "https://primary-edge.example.com/series/s1/e1/master.m3u8",
              "duration": "42m",
              "audioUrl": "https://audio-cdn.example.com/series/s1/e1/english-51.m3u8",
              "audioLabel": "English Dub",
              "audioLang": "en",
              "audioDelayMs": -180
            }
          ]
        }
      ]
    }
  ]
}
```
Flat `"episodes"` arrays (with `"season"` / `"seasonNumber"`) are also grouped into seasons automatically.

### Auto-Selection, Delay Precedence & Safety Rules (`PlaylistAudioResolver`)
- **Strict Scheme Validation & Deduplication:** Only valid `http://` and `https://` audio URLs are accepted (blank/malformed/non-HTTP entries are silently ignored); duplicate URLs on the same item are collapsed preserving the first declaration's metadata.
- **Auto-Select Precedence:**
  1. If any track sets `"default": true` (or `selected` / `autoSelect` / `audioDefault`), the **first** such track is automatically selected on playback start.
  2. Otherwise, if at least one track omits `"default"` (defaulting to `null`), the **first** valid external audio track is auto-selected.
  3. If **every** external track explicitly sets `"default": false`, all tracks are pre-loaded into **Tracks → Audio** so the user can switch to them with 1 tap without overriding the primary video's embedded audio on start.
- **Initial Sync Offset (`delayMs`) Precedence:**
  - Any valid `delayMs` (clamped to **`-3,600,000 ms` .. `+3,600,000 ms`**, i.e. `±60 minutes`) on the auto-selected track seeds the player's initial Audio Sync offset automatically.
  - If the user has already saved a custom per-video Audio Sync offset in Room history, the user's saved offset takes priority so manual fine-tuning is never overwritten on resume.
- **Full Entry-Point & Offline Download Preservation (v1.39):** External audio bundles travel automatically across **Catalog Play**, **CollectionDetailSheet** (both playback and per-film Download buttons), **SeriesDetailSheet**, **Up-Next Auto-Advance**, **Global Search** (including collection films), **Continue Watching**, **Active Session Resume**, and **Mobile Offline Downloads** (persisted in `ext_audio/` + `offline_audio.json` with saved `delayMs` for instant zero-network offline re-attachment).
- **Visual Badges:** Catalog cards, collection film rows, and series episode rows display a cyan **`AUDIO`** (or **`2 AUDIO`**) pill badge next to `CC` whenever external audio tracks are attached; mobile Download Options highlights them with a cyan **`Loaded`** pill.
- **Graceful Partial-Failure Recovery:** If one external audio URL fails (404, expired token, CORS, unsupported stream), `CinePlayerManager` drops only the failing external track, preserves any surviving `autoSelectAudioId`, and resumes video seamlessly without interrupting playback.

---

## 4. Field Alias Reference (`JsonPlaylistParser`)

| Concept | Accepted JSON Field Names |
| :--- | :--- |
| **Playlist Title** | `title`, `name`, `playlistName`, `playlist_title` |
| **Catalog Arrays** | `items`, `movies`, `collections`, `franchises`, `series`, `shows`, `tvShows`, `tv_shows`, `videos`, `playlist`, `channels`, `streams`, `media`, `entries`, `results`, `data`, `catalog`, `tracks`, `categories[].*` |
| **Item Type** | `type`, `contentType`, `content_type`, `mediaType`, `media_type`, `kind` (`"collection"`/`"franchise"`/`"saga"`/`"boxset"`, `"series"`/`"show"`/`"tv"`/`"tvshow"`, `"movie"`, or auto-detected via `movies`/`seasons`/`episodes`) |
| **Movie Collection Fields** | `collection`, `collectionName`, `collection_name`, `franchise`, `saga`; child array: `movies`, `films`, `parts`; ordering: `part`, `partNumber`, `order`, `index`, `number` |
| **Stream URL** | `url`, `streamUrl`, `stream_url`, `videoUrl`, `video_url`, `src`, `source`, `link`, `hls`, `stream`, `file`, `playUrl`, `play_url`, `sources[0]`, `streams[0]` |
| **Item Mirrors** | `mirrors`, `cdnUrls`, `cdn_urls`, `backupUrls`, `fallbackUrls`, plus extra entries in `sources` / `streams` |
| **Playlist CDN Mirrors** | `cdnMirrors`, `cdn_mirrors`, `mirrorHosts`, `fallbackHosts`, `cdnBases` |
| **Custom HTTP Headers** | `headers`, `httpHeaders`, `http_headers` (inherited Playlist → Collection/Series → Season → Film/Episode; child overrides parent) |
| **External Audio Arrays** | `audios`, `audioTracks`, `audio_tracks`, `externalAudios`, `external_audios`, `externalAudio`, `external_audio`, `audioUrls`, `audio_urls` |
| **External Audio Object / Shorthand** | Single object: `audio`, `externalAudio`, `external_audio` (fields: `url`/`src`/`streamUrl`, `label`/`name`/`title`, `lang`/`language`, `default`/`selected`/`autoSelect`, `delayMs`/`offsetMs`/`syncMs`). Flat shorthands: `audioUrl`, `audio_url`, `audio`, `audioStream`, `dubUrl`, `externalAudioUrl` + `audioLabel`, `audioLang`, `audioDefault`, `audioDelayMs` |
| **Poster / Artwork** | `poster`, `posterUrl`, `poster_url`, `image`, `imageUrl`, `image_url`, `thumbnail`, `thumb`, `cover`, `artwork`, `banner`, `logo`, `tvgLogo`, `tvg-logo`, `backdrop` |
| **IMDb Identifier** | `imdbId`, `imdb_id`, `imdb`, `imdbUrl`, `imdb_url`, `imdbLink`, `imdb_link`, `tconst`, `tt` |
| **Rating & Year** | `imdbRating`, `imdb_rating`, `rating`, `score`; `year`, `releaseYear`, `release_year`, `datePublished` |
| **Subtitles** | `subtitle`, `subtitleUrl`, `subtitle_url`, `captions`, `captionUrl`, `srt`, `vtt`, `subtitles[]`, `captions[]`, `tracks[]` |
| **Group / Genre** | `group`, `groupTitle`, `group_title`, `group-title`, `category`, `genre`, `genres[]` |

### Series & Collection Continuity Features
- **Per-Episode / Per-Film Resume & `Watched` Badges:** `SeriesDetailSheet` and `CollectionDetailSheet` display per-item progress bars and emerald `Watched` pills backed by Room v4.
- **Up-Next 10s Countdown & Skip Intro (`+85s`):** Floating smoked-glass Up-Next card with *Play Now* / *Cancel* near episode or collection-film end (auto-advancing across season boundaries and franchise parts) plus a *Skip Intro (+85s)* pill during intros.

---

## 5. 5-Layer Hybrid In-App CDN & Transport Engine

1. **Multi-CDN Mirror Failover (`CdnMirrorResolver`):** Builds a deduplicated candidate chain (Primary URL → per-item `mirrors` → path-preserving `cdnMirrors` origin swaps). Permanent HTTP 4xx or parse failures fail fast (`C.TIME_UNSET`) and hop to the next mirror at the exact playback millisecond.
2. **Live-Streaming Parallel Prefetch (`ParallelPrefetchEngine`):** Streams `2×` (TV) / `3×` (Mobile) parallel HLS segments + `2–3×` progressive `206 Content-Range` lookahead with 0 ms live mid-download handoff. External audio URLs intentionally use a dedicated direct HTTP lane so audio fetches never contend with primary video prefetch queues.
3. **Device-Aware Edge Cache (`MediaEdgeCache`):** **RAM-Direct** (zero eMMC writes) on Android TV/low-RAM devices; **128 MB** LRU `SimpleCache` with `128 KB` buffered `CacheDataSink` on phones/tablets (automatically bypassed on decode errors, authenticated WebDAV streams, manifests, subtitles, and local files).
4. **Unified Custom HTTP Headers:** Inherited `Authorization`, `Referer`, `Origin`, and `User-Agent` headers sync across both Cronet (HTTP/3) and OkHttp (HTTP/2).
5. **HTTP/3 QUIC + Self-Healing HTTP/2 + Origin Pre-Warming:** Automatic Cronet HTTP/3 upgrade, `QuicThroughputWatchdog` demotion if UDP-443 is throttled, IPv4-first DoH DNS caching, and debounced `HEAD` origin preconnects.

---

## 6. M3U / M3U8 Live TV & XMLTV EPG Guide (`M3uPlaylistParser` & `XmltvEpgParser`)

```m3u
#EXTM3U url-tvg="https://epg.example.com/guide.xml.gz"
#EXTINF:-1 tvg-id="news.hd" tvg-logo="https://example.com/logo.png" group-title="News",Example News HD
https://stream.example.com/news/master.m3u8
```
- Automatically detects `url-tvg` / `x-tvg-url` attributes from `#EXTM3U` headers (or load any `.xml` / `.xml.gz` XMLTV URL manually inside `M3uPlaylistSheet`).
- Displays live **Now** & **Next** program titles with progress bars inside `M3uPlaylistSheet` and the floating **Channel Zap EPG Banner**.
- Switch channels instantly via `CH-`/`CH+` buttons or Android TV remote `CHANNEL_UP`/`CHANNEL_DOWN` keys.

---

## 7. Multi-Account Cloud & LAN WebDAV / Jellyfin (`LocalNetworkClient`)

- Open **WebDAV · NAS** from the Home screen (`LocalNetworkSheet`) to add, test, edit, delete, and switch between **unlimited WebDAV & Jellyfin accounts**—including multiple accounts sharing the exact same server URL (such as multiple TorBox accounts on `https://webdav.torbox.app/`, available via the 1-tap **TorBox WebDAV** preset chip).
- All usernames, passwords, and Jellyfin API tokens are encrypted at rest via **Android Keystore AES-256-GCM** (`CredentialVault`) in **Room v4** (`network_servers`, non-unique `(baseUrl, serverType)` index) and are **never** hardcoded in the app or included in Wi-Fi/SAF sync snapshots.
- **Special-Character & Redirect Safety:** Passwords are encoded byte-for-byte in UTF-8 HTTP Basic Auth (no trimming). `PROPFIND` follows same-origin `301`/`302`/`307`/`308` redirects while preserving `Depth: 1` and XML body, and automatically strips `Authorization` on cross-origin redirects—both during folder browsing and during ExoPlayer playback redirects to signed storage CDNs (via `OkHttpDataSource`). Authenticated WebDAV streams also bypass unencrypted `MediaEdgeCache` disk storage.
- **Direct Playback & Sibling Subtitles:** Lists folders and all Media3 + NextLib FFmpeg playable formats (`.mp4`, `.mkv`, `.webm`, `.m3u8`, `.mpd`, `.ts`, `.mov`, `.avi`, `.flac`, `.mp3`, `.opus`, plus `video/*` & `audio/*` MIME types), shows file sizes, supports in-folder search + refresh + 1-tap account switching, and automatically attaches sibling `.srt`/`.vtt`/`.ass`/`.ssa`/`.ttml` subtitle files from the same WebDAV folder when playback starts.

---

## 8. Phone-to-TV Web Remote & Local Wi-Fi Sync (`LocalWebRemoteServer` & `DeviceSyncCodec`)

- Open **Web Remote** on your Android TV (or phone) to start the embedded LAN HTTP server displaying a scannable **QR code** (`http://<lan-ip>:<port>`) and a **6-digit session PIN** (validated in constant time).
- From any phone browser on the same Wi-Fi network, push:
  - Direct video/stream URLs (`play_url`)
  - **External Audio URLs** (`load_audio` — `.m3u8`, `.mp3`, `.ac3`, `.eac3`, `.flac`, `.wav`, `.m4a`, `.aac`, `.opus`, or muxed streams merged live into the active playback session without typing URLs on a D-pad, and selectable for offline download on mobile!)
  - JSON catalog (`load_json`) or M3U playlist URLs (`load_m3u`)
  - External subtitle URLs (`load_subtitle`)
  - XMLTV EPG URLs (`load_epg`)
  - NordVPN Access Keys (`set_nord_key`)
- **Local Sync (Phone ↔ TV):** Transfer Watchlist, Continue Watching, history, saved playlists, and playback preferences directly over LAN (`/api/sync/export` & `/api/sync/import`) or export/import a `.json` backup via Android's Storage Access Framework (SAF). Sensitive secrets (NordVPN keys and NAS passwords) are **never** included in sync snapshots.

---

## 9. Automatic IMDb Enrichment (`ImdbMetadataClient`)

Any JSON Movie, Series, or **individual Movie inside a Collection** with an `imdbId` or `imdbUrl` (`tt1772240`) lacking an explicit poster, rating, year, or description is enriched asynchronously over the shared `OkHttpClient` and displayed with crisp IMDb pills on Home, Catalog, and `CollectionDetailSheet` cards.

