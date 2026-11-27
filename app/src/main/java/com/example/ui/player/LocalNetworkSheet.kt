package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.NetworkServerEntity
import com.example.player.CredentialVault
import com.example.player.LocalNetworkClient
import com.example.player.NetworkBrowserItem
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OnCinemaAmber
import com.example.ui.theme.SignalRose
import com.example.ui.theme.SlateInkSecondary
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.SubtitleEmerald
import com.example.viewmodel.LocalNetworkUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalNetworkSheet(
    servers: List<NetworkServerEntity>,
    browserState: LocalNetworkUiState,
    onAddServer: (name: String, type: String, baseUrl: String, username: String, secret: String) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onOpenServer: (NetworkServerEntity) -> Unit,
    onBrowseItem: (NetworkBrowserItem) -> Unit,
    onNavigateUp: () -> Unit,
    onDismiss: () -> Unit,
    onUpdateServer: ((serverId: Long, name: String, type: String, baseUrl: String, username: String, secret: String) -> Unit)? = null,
    onTestConnection: ((type: String, baseUrl: String, username: String, secret: String) -> Unit)? = null,
    onClearConnectionTest: (() -> Unit)? = null,
    onRefreshFolder: (() -> Unit)? = null,
    onCloseActiveServer: (() -> Unit)? = null,
    onDownloadItem: ((NetworkBrowserItem) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddForm by remember { mutableStateOf(servers.isEmpty()) }
    var editingServerId by remember { mutableStateOf<Long?>(null) }
    var serverType by remember { mutableStateOf(LocalNetworkClient.TYPE_WEBDAV) }
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var folderFilterQuery by remember(browserState.activeServer?.id, browserState.breadcrumbPath) {
        mutableStateOf("")
    }

    fun resetFormForNew() {
        editingServerId = null
        serverType = LocalNetworkClient.TYPE_WEBDAV
        serverName = ""
        serverUrl = ""
        username = ""
        secret = ""
        showPassword = false
        onClearConnectionTest?.invoke()
    }

    fun startEditing(srv: NetworkServerEntity) {
        editingServerId = srv.id
        serverType = srv.serverType.uppercase()
        serverName = srv.name
        serverUrl = srv.baseUrl
        username = CredentialVault.decrypt(srv.usernameEnc)
        secret = ""
        showPassword = false
        showAddForm = true
        onClearConnectionTest?.invoke()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianSurface,
        modifier = Modifier.testTag("local_network_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (browserState.activeServer != null) {
                        IconButton(
                            onClick = onNavigateUp,
                            modifier = Modifier.testTag("local_network_up_button")
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Go up folder")
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.FolderShared,
                            contentDescription = stringResource(R.string.cd_local_network),
                            tint = StreamCyan
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = browserState.activeServer?.name ?: stringResource(R.string.local_network_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (browserState.activeServer != null) {
                                browserState.breadcrumbPath.joinToString(" / ") { it.first }
                            } else {
                                stringResource(R.string.local_network_subtitle)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateInkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (browserState.activeServer != null && onRefreshFolder != null) {
                        IconButton(
                            onClick = onRefreshFolder,
                            modifier = Modifier.testTag("network_browser_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_webdav_folder),
                                tint = StreamCyan
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("local_network_close_button")
                    ) {
                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close WebDAV & Media Servers")
                    }
                }
            }

            if (browserState.errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SignalRose.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = browserState.errorMessage,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = SignalRose
                    )
                }
            }

            if (browserState.activeServer != null) {
                // Quick account switcher bar when 2+ WebDAV/Jellyfin accounts are saved
                if (servers.size > 1 || onCloseActiveServer != null) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("webdav_account_switcher_row")
                    ) {
                        if (onCloseActiveServer != null) {
                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        onCloseActiveServer()
                                        showAddForm = false
                                    },
                                    label = { Text("All Accounts") },
                                    modifier = Modifier.testTag("network_browser_accounts_button")
                                )
                            }
                        }
                        items(servers, key = { it.id }) { srv ->
                            FilterChip(
                                selected = srv.id == browserState.activeServer.id,
                                onClick = {
                                    if (srv.id != browserState.activeServer.id) {
                                        onOpenServer(srv)
                                    }
                                },
                                label = {
                                    Text(
                                        text = srv.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("switch_network_account_${srv.id}")
                            )
                        }
                    }
                }

                if (!browserState.isLoading && browserState.items.size >= 5) {
                    OutlinedTextField(
                        value = folderFilterQuery,
                        onValueChange = { folderFilterQuery = it },
                        placeholder = { Text("Filter folders & media…") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = SlateInkSecondary
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("network_browser_search_input")
                    )
                }

                val filteredItems = remember(browserState.items, folderFilterQuery) {
                    val q = folderFilterQuery.trim()
                    if (q.isEmpty()) {
                        browserState.items
                    } else {
                        browserState.items.filter { it.name.contains(q, ignoreCase = true) }
                    }
                }

                if (browserState.isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = CinemaAmber)
                    }
                } else if (filteredItems.isEmpty()) {
                    Text(
                        text = if (folderFilterQuery.isNotBlank()) {
                            "No matching files or folders for \"$folderFilterQuery\"."
                        } else {
                            "No playable videos or subfolders found in this folder."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateInkSecondary,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            val sizeLabel = remember(item.sizeBytes) {
                                LocalNetworkClient.formatFileSize(item.sizeBytes)
                            }
                            val detailText = remember(item.isFolder, item.format, sizeLabel, item.siblingSubtitles.size) {
                                if (item.isFolder) {
                                    "Folder"
                                } else {
                                    buildList {
                                        add(item.format.label)
                                        if (sizeLabel.isNotBlank()) add(sizeLabel)
                                        if (item.siblingSubtitles.isNotEmpty()) {
                                            add("+${item.siblingSubtitles.size} sub${if (item.siblingSubtitles.size == 1) "" else "s"}")
                                        }
                                    }.joinToString(" · ")
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ObsidianSurfaceHigh,
                                border = BorderStroke(1.dp, ObsidianOutline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onBrowseItem(item)
                                        if (!item.isFolder) onDismiss()
                                    }
                                    .testTag("network_browser_item_${item.id.hashCode()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (item.isFolder) Icons.Rounded.FolderOpen else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = if (item.isFolder) StreamCyan else CinemaAmber
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = detailText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SlateInkSecondary
                                            )
                                            if (!item.isFolder && item.siblingSubtitles.isNotEmpty()) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Subtitles,
                                                    contentDescription = null,
                                                    tint = SubtitleEmerald,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (!item.isFolder && onDownloadItem != null) {
                                        IconButton(
                                            onClick = { onDownloadItem(item) },
                                            modifier = Modifier.testTag("network_browser_download_${item.id.hashCode()}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Download,
                                                contentDescription = "Download ${item.name} for offline playback",
                                                tint = StreamCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !showAddForm,
                        onClick = {
                            showAddForm = false
                            editingServerId = null
                            onClearConnectionTest?.invoke()
                        },
                        label = { Text("Saved Accounts (${servers.size})") },
                        modifier = Modifier.testTag("local_network_saved_tab")
                    )
                    FilterChip(
                        selected = showAddForm,
                        onClick = {
                            if (!showAddForm || editingServerId != null) {
                                resetFormForNew()
                            }
                            showAddForm = true
                        },
                        label = { Text(if (editingServerId != null) "Edit Account" else "+ Add Account") },
                        modifier = Modifier.testTag("local_network_add_tab")
                    )
                }

                if (showAddForm) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = serverType == LocalNetworkClient.TYPE_WEBDAV,
                                onClick = {
                                    serverType = LocalNetworkClient.TYPE_WEBDAV
                                    onClearConnectionTest?.invoke()
                                },
                                label = { Text("WebDAV / Cloud") },
                                modifier = Modifier.testTag("network_type_webdav")
                            )
                            FilterChip(
                                selected = serverType == LocalNetworkClient.TYPE_JELLYFIN,
                                onClick = {
                                    serverType = LocalNetworkClient.TYPE_JELLYFIN
                                    onClearConnectionTest?.invoke()
                                },
                                label = { Text("Jellyfin") },
                                modifier = Modifier.testTag("network_type_jellyfin")
                            )
                        }

                        if (serverType == LocalNetworkClient.TYPE_WEBDAV) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = serverUrl.trim().trimEnd('/').equals(
                                        LocalNetworkClient.TORBOX_WEBDAV_URL.trimEnd('/'),
                                        ignoreCase = true
                                    ),
                                    onClick = {
                                        serverUrl = LocalNetworkClient.TORBOX_WEBDAV_URL
                                        if (serverName.isBlank()) {
                                            serverName = "TorBox"
                                        }
                                        onClearConnectionTest?.invoke()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Public,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("TorBox WebDAV") },
                                    modifier = Modifier.testTag("network_preset_torbox")
                                )
                            }
                        }

                        OutlinedTextField(
                            value = serverName,
                            onValueChange = { serverName = it },
                            label = { Text("Account Label (e.g. TorBox Personal)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("network_server_name_input")
                        )
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = {
                                Text(
                                    if (serverType == LocalNetworkClient.TYPE_JELLYFIN) {
                                        "Server URL (e.g. http://192.168.1.10:8096)"
                                    } else {
                                        "WebDAV URL (e.g. https://webdav.torbox.app/)"
                                    }
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("network_server_url_input")
                        )
                        if (serverType == LocalNetworkClient.TYPE_WEBDAV) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Username / Email") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("network_server_username_input")
                            )
                        }
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it },
                            label = {
                                Text(
                                    when {
                                        serverType == LocalNetworkClient.TYPE_JELLYFIN -> "Jellyfin API Key"
                                        editingServerId != null -> "Password (leave blank to keep saved)"
                                        else -> "Password (Keystore AES-256 encrypted)"
                                    }
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showPassword = !showPassword },
                                    modifier = Modifier.testTag("network_server_password_visibility_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showPassword) "Hide password" else "Show password"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("network_server_secret_input")
                        )

                        if (browserState.connectionTestMessage != null) {
                            val ok = browserState.connectionTestSuccess == true
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = (if (ok) SubtitleEmerald else SignalRose).copy(alpha = 0.16f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("network_connection_test_banner")
                            ) {
                                Text(
                                    text = browserState.connectionTestMessage,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ok) SubtitleEmerald else SignalRose
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onTestConnection != null) {
                                OutlinedButton(
                                    onClick = {
                                        if (serverUrl.isNotBlank() && !browserState.isTestingConnection) {
                                            val effectiveSecret = if (secret.isEmpty() && editingServerId != null) {
                                                servers.firstOrNull { it.id == editingServerId }
                                                    ?.let { CredentialVault.decrypt(it.secretEnc) }
                                                    .orEmpty()
                                            } else {
                                                secret
                                            }
                                            onTestConnection(serverType, serverUrl, username, effectiveSecret)
                                        }
                                    },
                                    enabled = serverUrl.isNotBlank() && !browserState.isTestingConnection,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("network_test_connection_button")
                                ) {
                                    if (browserState.isTestingConnection) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = StreamCyan
                                        )
                                    } else {
                                        Text("Test")
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    if (serverUrl.isNotBlank()) {
                                        val editId = editingServerId
                                        if (editId != null && onUpdateServer != null) {
                                            onUpdateServer(editId, serverName, serverType, serverUrl, username, secret)
                                        } else {
                                            onAddServer(serverName, serverType, serverUrl, username, secret)
                                        }
                                        editingServerId = null
                                        showAddForm = false
                                    }
                                },
                                enabled = serverUrl.isNotBlank(),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .testTag("network_save_connect_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CinemaAmber,
                                    contentColor = OnCinemaAmber
                                )
                            ) {
                                Text(if (editingServerId != null) "Update & Open" else "Save & Connect")
                            }
                        }
                    }
                } else {
                    if (servers.isEmpty()) {
                        Text(
                            text = "No WebDAV or Jellyfin accounts saved yet. Tap + Add Account to connect TorBox, Nextcloud, NAS, or Jellyfin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateInkSecondary
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(servers, key = { it.id }) { srv ->
                                val accountUser = remember(srv.id, srv.usernameEnc) {
                                    CredentialVault.decrypt(srv.usernameEnc)
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = ObsidianSurfaceHigh,
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenServer(srv) }
                                        .testTag("saved_network_server_${srv.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (srv.serverType.equals(LocalNetworkClient.TYPE_WEBDAV, true)) {
                                                Icons.Rounded.Public
                                            } else {
                                                Icons.Rounded.FolderShared
                                            },
                                            contentDescription = null,
                                            tint = StreamCyan
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = srv.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = buildString {
                                                    append(srv.serverType)
                                                    if (accountUser.isNotBlank()) {
                                                        append(" · ")
                                                        append(accountUser)
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CinemaAmber,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = srv.baseUrl,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SlateInkSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (onUpdateServer != null) {
                                            IconButton(
                                                onClick = { startEditing(srv) },
                                                modifier = Modifier.testTag("edit_network_server_${srv.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Edit,
                                                    contentDescription = "Edit account",
                                                    tint = SlateInkSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { onDeleteServer(srv.id) },
                                            modifier = Modifier.testTag("delete_network_server_${srv.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "Delete account",
                                                tint = SlateInkSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
