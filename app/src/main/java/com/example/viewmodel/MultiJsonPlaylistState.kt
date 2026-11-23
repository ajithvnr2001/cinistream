package com.example.viewmodel

import com.example.model.JsonPlaylistItem

/**
 * Represents a single loaded JSON playlist (either remote HTTP/HTTPS URL or local document URI).
 * Multiple playlists can be loaded simultaneously in CineStream v1.19+.
 */
data class LoadedJsonPlaylist(
    val id: String,
    val label: String,
    val sourceUrl: String,
    val isLocal: Boolean = false,
    val items: List<JsonPlaylistItem> = emptyList()
) {
    val isRemoteHttp: Boolean
        get() = !isLocal && (
            sourceUrl.startsWith("http://", ignoreCase = true) ||
                sourceUrl.startsWith("https://", ignoreCase = true)
            )
}

data class JsonPlaylistUiState(
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
    val items: List<JsonPlaylistItem> = emptyList(),
    val playlists: List<LoadedJsonPlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val activeItemId: String? = null,
    val activeEpisodeId: String? = null,
    val selectedSeriesForSheet: JsonPlaylistItem? = null,
    val isLoading: Boolean = false,
    val isEnrichingImdb: Boolean = false,
    val errorMessage: String? = null
) {
    /**
     * All items across all loaded playlists (or legacy [items] when constructed directly in tests).
     */
    val allItems: List<JsonPlaylistItem>
        get() = if (playlists.isNotEmpty()) playlists.flatMap { it.items } else items

    /**
     * True if the currently selected playlist (or any loaded playlist when viewing "All") can be
     * refreshed over HTTP/HTTPS.
     */
    val canRefreshRemote: Boolean
        get() = when {
            playlists.isNotEmpty() -> {
                val active = selectedPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
                active?.isRemoteHttp ?: playlists.any { it.isRemoteHttp }
            }
            else -> sourceUrl?.startsWith("http://", ignoreCase = true) == true ||
                sourceUrl?.startsWith("https://", ignoreCase = true) == true
        }

    /**
     * Ordered list of remote HTTP/HTTPS playlist URLs currently loaded for DataStore persistence.
     */
    val remotePlaylistUrls: List<String>
        get() = playlists.filter { it.isRemoteHttp }.map { it.sourceUrl }
}

/**
 * Pure, deterministic state reducer for multi-JSON playlist operations.
 * Guarantees collision-free Compose keys across multiple playlists and clean selection fallback.
 */
object MultiJsonPlaylistReducer {

    fun stablePlaylistId(sourceUrl: String, isLocal: Boolean): String {
        val normalized = sourceUrl.trim().lowercase()
        val hex = normalized.hashCode().toUInt().toString(16).padStart(8, '0')
        return if (isLocal) "local_$hex" else "url_$hex"
    }

    /**
     * Namespaces every item's [JsonPlaylistItem.id] by [playlistId] and deduplicates collisions
     * within the same playlist so combined "All" LazyRow / LazyColumn views never crash on
     * duplicate keys.
     */
    fun namespaceItems(
        playlistId: String,
        rawItems: List<JsonPlaylistItem>
    ): List<JsonPlaylistItem> {
        val prefix = "${playlistId}__"
        val seenIds = HashSet<String>(rawItems.size * 2)
        return rawItems.mapIndexed { index, item ->
            val baseRaw = item.id.trim().ifBlank { "item_$index" }
            val prefixed = if (baseRaw.startsWith(prefix)) baseRaw else "$prefix$baseRaw"
            var candidate = prefixed
            var suffix = 1
            while (!seenIds.add(candidate)) {
                candidate = "${prefixed}_$suffix"
                suffix++
            }
            if (candidate == item.id) item else item.copy(id = candidate)
        }
    }

    /**
     * Inserts a new playlist or updates an existing playlist with the same [LoadedJsonPlaylist.id].
     */
    fun upsertPlaylist(
        state: JsonPlaylistUiState,
        playlist: LoadedJsonPlaylist,
        selectPlaylist: Boolean
    ): JsonPlaylistUiState {
        val namespaced = playlist.copy(items = namespaceItems(playlist.id, playlist.items))
        val existingIdx = state.playlists.indexOfFirst { it.id == namespaced.id }
        val updatedList = if (existingIdx >= 0) {
            state.playlists.toMutableList().apply { this[existingIdx] = namespaced }
        } else {
            state.playlists + namespaced
        }

        val nextSelectedId = when {
            updatedList.size == 1 -> updatedList.first().id
            selectPlaylist -> namespaced.id
            state.selectedPlaylistId != null &&
                updatedList.any { it.id == state.selectedPlaylistId } -> state.selectedPlaylistId
            else -> null
        }

        return projectSelection(
            base = state.copy(isLoading = false, errorMessage = null),
            playlists = updatedList,
            selectedPlaylistId = nextSelectedId
        )
    }

    /**
     * Switches the active view to either a single playlist ([playlistId]) or combined "All" (`null`).
     */
    fun selectPlaylist(
        state: JsonPlaylistUiState,
        playlistId: String?
    ): JsonPlaylistUiState {
        if (state.playlists.isEmpty()) return state
        val resolvedId = when {
            playlistId == null && state.playlists.size > 1 -> null
            playlistId == null -> state.playlists.first().id
            state.playlists.any { it.id == playlistId } -> playlistId
            else -> if (state.playlists.size > 1) null else state.playlists.first().id
        }
        return projectSelection(state, state.playlists, resolvedId)
    }

    /**
     * Removes a single playlist by [playlistId] and falls back cleanly to "All" or remaining playlist.
     */
    fun removePlaylist(
        state: JsonPlaylistUiState,
        playlistId: String
    ): JsonPlaylistUiState {
        val remaining = state.playlists.filterNot { it.id == playlistId }
        if (remaining.isEmpty()) {
            return JsonPlaylistUiState()
        }
        val nextSelectedId = when {
            remaining.size == 1 -> remaining.first().id
            state.selectedPlaylistId == playlistId -> null
            state.selectedPlaylistId != null &&
                remaining.any { it.id == state.selectedPlaylistId } -> state.selectedPlaylistId
            else -> null
        }
        val remainingIds = remaining.flatMap { pl -> pl.items.map { it.id } }.toSet()
        val updatedSeriesSheet = state.selectedSeriesForSheet?.takeIf { it.id in remainingIds }
        val updatedActiveId = state.activeItemId?.takeIf { it in remainingIds }
        return projectSelection(
            base = state.copy(
                activeItemId = updatedActiveId,
                selectedSeriesForSheet = updatedSeriesSheet
            ),
            playlists = remaining,
            selectedPlaylistId = nextSelectedId
        )
    }

    /**
     * Applies IMDb metadata enrichment updates across all loaded playlists and refreshes the
     * projected [JsonPlaylistUiState.items] and [JsonPlaylistUiState.selectedSeriesForSheet].
     */
    fun applyImdbUpdates(
        state: JsonPlaylistUiState,
        updatedMap: Map<String, JsonPlaylistItem>
    ): JsonPlaylistUiState {
        if (updatedMap.isEmpty()) return state
        if (state.playlists.isEmpty()) {
            return state.copy(
                items = state.items.map { updatedMap[it.id] ?: it },
                selectedSeriesForSheet = state.selectedSeriesForSheet?.let { updatedMap[it.id] ?: it }
            )
        }
        val updatedPlaylists = state.playlists.map { pl ->
            pl.copy(items = pl.items.map { existing -> updatedMap[existing.id] ?: existing })
        }
        val updatedSeriesSheet = state.selectedSeriesForSheet?.let { updatedMap[it.id] ?: it }
        return projectSelection(
            base = state.copy(selectedSeriesForSheet = updatedSeriesSheet),
            playlists = updatedPlaylists,
            selectedPlaylistId = state.selectedPlaylistId
        )
    }

    private fun projectSelection(
        base: JsonPlaylistUiState,
        playlists: List<LoadedJsonPlaylist>,
        selectedPlaylistId: String?
    ): JsonPlaylistUiState {
        val selected = selectedPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
        val visibleItems = selected?.items ?: playlists.flatMap { it.items }
        val label = when {
            selected != null -> selected.label
            playlists.size > 1 -> "${playlists.size} JSON Playlists"
            else -> playlists.firstOrNull()?.label
        }
        val url = selected?.sourceUrl
            ?: playlists.firstOrNull { it.isRemoteHttp }?.sourceUrl
            ?: playlists.firstOrNull()?.sourceUrl
        return base.copy(
            sourceLabel = label,
            sourceUrl = url,
            items = visibleItems,
            playlists = playlists,
            selectedPlaylistId = selected?.id
        )
    }
}
