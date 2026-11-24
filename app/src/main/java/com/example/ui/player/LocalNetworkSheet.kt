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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.NetworkServerEntity
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
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddForm by remember { mutableStateOf(servers.isEmpty()) }
    var serverType by remember { mutableStateOf(LocalNetworkClient.TYPE_WEBDAV) }
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

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
                    Column {
                        Text(
                            text = browserState.activeServer?.name ?: stringResource(R.string.local_network_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
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
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("local_network_close_button")
                ) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close Local Network")
                }
            }

            if (browserState.errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SignalRose.copy(alpha = 0.16f)
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
                if (browserState.isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = CinemaAmber)
                    }
                } else if (browserState.items.isEmpty()) {
                    Text(
                        text = "No playable videos or subfolders found in this folder.",
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
                        items(browserState.items, key = { it.id }) { item ->
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
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (item.isFolder) "Folder" else item.format.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SlateInkSecondary
                                        )
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
                        onClick = { showAddForm = false },
                        label = { Text("Saved Servers (${servers.size})") },
                        modifier = Modifier.testTag("local_network_saved_tab")
                    )
                    FilterChip(
                        selected = showAddForm,
                        onClick = { showAddForm = true },
                        label = { Text("+ Add Server") },
                        modifier = Modifier.testTag("local_network_add_tab")
                    )
                }

                if (showAddForm) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = serverType == LocalNetworkClient.TYPE_WEBDAV,
                            onClick = { serverType = LocalNetworkClient.TYPE_WEBDAV },
                            label = { Text("WebDAV / NAS") },
                            modifier = Modifier.testTag("network_type_webdav")
                        )
                        FilterChip(
                            selected = serverType == LocalNetworkClient.TYPE_JELLYFIN,
                            onClick = { serverType = LocalNetworkClient.TYPE_JELLYFIN },
                            label = { Text("Jellyfin") },
                            modifier = Modifier.testTag("network_type_jellyfin")
                        )
                    }

                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = { Text("Display Name (optional)") },
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
                                    "WebDAV URL (e.g. http://192.168.1.10:8080/movies)"
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
                            label = { Text("Username (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = {
                            Text(
                                if (serverType == LocalNetworkClient.TYPE_JELLYFIN) {
                                    "Jellyfin API Key"
                                } else {
                                    "Password (optional, Keystore encrypted)"
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("network_server_secret_input")
                    )

                    Button(
                        onClick = {
                            if (serverUrl.isNotBlank()) {
                                onAddServer(serverName, serverType, serverUrl, username, secret)
                                showAddForm = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("network_save_connect_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CinemaAmber,
                            contentColor = OnCinemaAmber
                        )
                    ) {
                        Text("Save & Connect")
                    }
                } else {
                    if (servers.isEmpty()) {
                        Text(
                            text = "No LAN media servers saved yet. Tap + Add Server to connect WebDAV or Jellyfin.",
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
                                            imageVector = Icons.Rounded.FolderShared,
                                            contentDescription = null,
                                            tint = StreamCyan
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = srv.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${srv.serverType} · ${srv.baseUrl}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SlateInkSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteServer(srv.id) },
                                            modifier = Modifier.testTag("delete_network_server_${srv.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "Delete server",
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
