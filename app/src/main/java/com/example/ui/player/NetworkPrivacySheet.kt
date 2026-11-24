package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.player.NordVpnLocation
import com.example.player.NordVpnLocationCatalog
import com.example.player.NordVpnServer
import com.example.player.PrivateDnsMode
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SignalRose
import com.example.ui.theme.SubtitleEmerald
import com.example.viewmodel.NetworkPrivacyUiState
import kotlinx.coroutines.delay

import com.example.ui.theme.StreamCyan

private val PrivacyCyan = StreamCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPrivacySheet(
    state: NetworkPrivacyUiState,
    onSelectDnsMode: (PrivateDnsMode) -> Unit,
    onLoginNordToken: (String) -> Unit,
    onLoginNordConfiguredSecret: () -> Unit,
    onToggleNordEnabled: (Boolean) -> Unit,
    onToggleSmartAutoVpnFailover: (Boolean) -> Unit = {},
    onSelectNordLocation: (NordVpnLocation) -> Unit = {},
    onSelectNordServer: (NordVpnServer) -> Unit = {},
    onLogoutNord: () -> Unit,
    onDisableAll: () -> Unit = {},
    onProbeRoute: () -> Unit,
    onLaunchCompanionApp: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstControlFocusRequester = remember { FocusRequester() }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var showTokenPlaintext by rememberSaveable { mutableStateOf(false) }
    var isReplacingKey by rememberSaveable { mutableStateOf(false) }
    var replaceTokenInput by rememberSaveable { mutableStateOf("") }
    var showReplaceTokenPlaintext by rememberSaveable { mutableStateOf(false) }
    var locationSearchQuery by rememberSaveable { mutableStateOf("") }

    val matchingLocations = remember(state.allLocations, locationSearchQuery) {
        NordVpnLocationCatalog.search(state.allLocations, locationSearchQuery, limit = 6)
    }

    LaunchedEffect(state.nordVpnLoggedIn, state.isLoggingInNord, state.loginError) {
        if (state.nordVpnLoggedIn && !state.isLoggingInNord && state.loginError == null) {
            tokenInput = ""
            showTokenPlaintext = false
            if (replaceTokenInput.isNotBlank()) {
                replaceTokenInput = ""
                showReplaceTokenPlaintext = false
                isReplacingKey = false
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(120L)
        runCatching { firstControlFocusRequester.requestFocus() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianSurface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("network_privacy_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (state.isAnyPrivacyActive) {
                        SubtitleEmerald.copy(alpha = 0.18f)
                    } else {
                        PrivacyCyan.copy(alpha = 0.16f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (state.isAnyPrivacyActive) {
                            SubtitleEmerald.copy(alpha = 0.55f)
                        } else {
                            PrivacyCyan.copy(alpha = 0.45f)
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (state.isAnyPrivacyActive) SubtitleEmerald else PrivacyCyan,
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.privacy_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Built-in Cloudflare 1.1.1.1 + all 118+ NordVPN countries inside CineStream",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Live Route Status, 1-Tap Disable & Connection Test Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("privacy_probe_result_card"),
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurfaceHigh,
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        state.probeResult?.success == true -> SubtitleEmerald.copy(alpha = 0.6f)
                        state.probeResult?.success == false -> SignalRose.copy(alpha = 0.6f)
                        state.isAnyPrivacyActive -> PrivacyCyan.copy(alpha = 0.5f)
                        else -> ObsidianOutline.copy(alpha = 0.65f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.isAnyPrivacyActive) {
                                    "Active Route: ${state.compactStatusBadge}"
                                } else {
                                    "Active Route: Direct ISP (System DNS)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (state.isAnyPrivacyActive) SubtitleEmerald else MaterialTheme.colorScheme.onSurface
                            )
                            val statusSubtitle = when {
                                state.isConnectingVpn -> "Connecting in-app NordLynx (${state.selectedLocation.displayLabel})…"
                                state.nordLynxTunnelUp && state.activeNordLynxHostname.isNotBlank() ->
                                    "NordLynx · ${state.selectedLocation.displayLabel} (${state.activeNordLynxHostname})"
                                state.nordVpnLoggedIn && state.nordVpnEnabled ->
                                    "Location: ${state.selectedLocation.displayLabel}"
                                state.systemVpnActive -> "Active VPN tunnel detected"
                                else -> "Search any country & connect with your NordVPN key below"
                            }
                            Text(
                                text = statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.isAnyPrivacyActive || state.isConnectingVpn) {
                                    PrivacyCyan
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onProbeRoute,
                            enabled = !state.isProbingRoute && !state.isConnectingVpn,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrivacyCyan,
                                contentColor = Color(0xFF061018)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .focusRequester(firstControlFocusRequester)
                                .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                                .testTag("privacy_test_connection_button")
                        ) {
                            if (state.isProbingRoute || state.isConnectingVpn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF061018)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = when {
                                    state.isConnectingVpn -> "Connecting…"
                                    state.isProbingRoute -> "Testing…"
                                    else -> stringResource(R.string.privacy_test_connection)
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    val hasInAppPrivacyEnabled = state.privateDnsMode != PrivateDnsMode.SYSTEM ||
                        (state.nordVpnLoggedIn && state.nordVpnEnabled) ||
                        state.nordLynxTunnelUp
                    if (hasInAppPrivacyEnabled) {
                        OutlinedButton(
                            onClick = onDisableAll,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SignalRose.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                                .testTag("privacy_disable_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                tint = SignalRose,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "Disable 1.1.1.1 & NordVPN (Direct ISP)",
                                color = SignalRose,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    state.probeResult?.let { probe ->
                        HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.5f))
                        if (probe.success) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = SubtitleEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    val locPart = listOfNotNull(
                                        probe.locationCode.takeIf { it.isNotBlank() },
                                        probe.edgeColo.takeIf { it.isNotBlank() }?.let { "Edge $it" }
                                    ).joinToString(" · ")
                                    Text(
                                        text = "Exit IP: ${probe.exitIp}" +
                                            if (locPart.isNotBlank()) " ($locPart)" else "",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SubtitleEmerald
                                    )
                                    Text(
                                        text = "${probe.routeSummary} · ${probe.latencyMs} ms",
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = SignalRose,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = probe.errorMessage ?: "Connection test failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SignalRose
                                )
                            }
                        }
                    }
                }
            }

            // Section 1: Built-in Encrypted DNS-over-HTTPS (Cloudflare 1.1.1.1 / Google 8.8.8.8)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurfaceHigh,
                border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = PrivacyCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.privacy_dns_section_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = state.privateDnsMode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PrivateDnsMode.entries.forEach { mode ->
                            val isSelected = state.privateDnsMode == mode
                            val accent = when (mode) {
                                PrivateDnsMode.CLOUDFLARE_1111 -> PrivacyCyan
                                PrivateDnsMode.GOOGLE_8888 -> CinemaAmber
                                PrivateDnsMode.SYSTEM -> SubtitleEmerald
                            }
                            Surface(
                                onClick = { onSelectDnsMode(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                                    .testTag("privacy_dns_${mode.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) accent.copy(alpha = 0.22f) else ObsidianSurface,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) accent else ObsidianOutline.copy(alpha = 0.65f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = when (mode) {
                                            PrivateDnsMode.SYSTEM -> "System"
                                            PrivateDnsMode.CLOUDFLARE_1111 -> "1.1.1.1"
                                            PrivateDnsMode.GOOGLE_8888 -> "8.8.8.8"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when (mode) {
                                            PrivateDnsMode.SYSTEM -> "Default"
                                            PrivateDnsMode.CLOUDFLARE_1111 -> "Cloudflare"
                                            PrivateDnsMode.GOOGLE_8888 -> "Google DoH"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Built-In All-Countries NordVPN (Searchable Location + Access Key + NordLynx)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurfaceHigh,
                border = BorderStroke(
                    1.dp,
                    if (state.nordVpnLoggedIn && state.nordVpnEnabled) {
                        SubtitleEmerald.copy(alpha = 0.6f)
                    } else {
                        ObsidianOutline.copy(alpha = 0.6f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VpnKey,
                            contentDescription = null,
                            tint = if (state.nordVpnLoggedIn && state.nordVpnEnabled) SubtitleEmerald else CinemaAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NordVPN Built-In (All Locations · NordLynx)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (state.nordVpnLoggedIn) {
                                    "In-app WireGuard tunnel · No external app needed"
                                } else {
                                    "Search any country & connect with your NordVPN Access Key"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }

                    // Currently Selected Location Pill
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nordvpn_selected_location_card"),
                        shape = RoundedCornerShape(10.dp),
                        color = ObsidianSurface,
                        border = BorderStroke(
                            1.dp,
                            if (state.nordVpnEnabled) SubtitleEmerald.copy(alpha = 0.55f) else ObsidianOutline.copy(alpha = 0.7f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = if (state.nordVpnEnabled) SubtitleEmerald else PrivacyCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.selectedLocation.displayLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (state.activeNordLynxHostname.isNotBlank() && state.nordVpnEnabled) {
                                        "NordLynx · ${state.activeNordLynxHostname}"
                                    } else {
                                        "Selected Location · Type below to change"
                                    },
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state.nordVpnEnabled) SubtitleEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PrivacyCyan.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = state.selectedLocation.compactBadge,
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PrivacyCyan,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Searchable All-Locations Input (NO static list dumped on screen!)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = locationSearchQuery,
                            onValueChange = { locationSearchQuery = it },
                            label = { Text("Search Location (All 118+ Countries)") },
                            placeholder = { Text("Type India, Singapore, Tokyo, UK, USA…") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "Search NordVPN locations",
                                    tint = PrivacyCyan
                                )
                            },
                            trailingIcon = {
                                if (locationSearchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { locationSearchQuery = "" },
                                        modifier = Modifier.testTag("nordvpn_location_search_clear")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear search"
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    matchingLocations.firstOrNull()?.let { top ->
                                        onSelectNordLocation(top)
                                        locationSearchQuery = ""
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrivacyCyan,
                                unfocusedBorderColor = ObsidianOutline,
                                focusedLabelColor = PrivacyCyan
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nordvpn_location_search_input")
                        )

                        if (locationSearchQuery.isBlank()) {
                            Text(
                                text = "Search any country or city across ${state.allLocations.size} global NordVPN endpoints — connects directly inside CineStream.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (matchingLocations.isEmpty()) {
                            Text(
                                text = "No NordVPN location matched \"$locationSearchQuery\". Try a country name or 2-letter code (e.g. IN, SG, JP, UK, US).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("nordvpn_location_no_results")
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = ObsidianSurface,
                                border = BorderStroke(1.dp, PrivacyCyan.copy(alpha = 0.45f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    matchingLocations.forEachIndexed { idx, loc ->
                                        val isCurrent = loc.id == state.selectedLocation.id
                                        Surface(
                                            onClick = {
                                                onSelectNordLocation(loc)
                                                locationSearchQuery = ""
                                            },
                                            color = if (isCurrent) SubtitleEmerald.copy(alpha = 0.14f) else Color.Transparent,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .minimumInteractiveComponentSize()
                                                .tvFocusHighlight(shape = RoundedCornerShape(8.dp), focusedScale = 1.01f)
                                                .testTag("nordvpn_location_result_${loc.id}")
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isCurrent) Icons.Rounded.Check else Icons.Rounded.Public,
                                                    contentDescription = null,
                                                    tint = if (isCurrent) SubtitleEmerald else PrivacyCyan,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                                Text(
                                                    text = loc.displayLabel,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (isCurrent) SubtitleEmerald else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = loc.compactBadge,
                                                    fontFamily = JetBrainsMonoFamily,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (idx < matchingLocations.lastIndex) {
                                            HorizontalDivider(
                                                color = ObsidianOutline.copy(alpha = 0.35f),
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!state.nordVpnLoggedIn) {
                        if (state.hasConfiguredBuildToken) {
                            Button(
                                onClick = onLoginNordConfiguredSecret,
                                enabled = !state.isLoggingInNord,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SubtitleEmerald,
                                    contentColor = Color(0xFF061610)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                                    .testTag("nordvpn_connect_secret_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = "Connect 1.1.1.1 + NordVPN (Saved Secret)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("NordVPN Access Key / Token") },
                            placeholder = { Text("Paste key from my.nordaccount.com") },
                            singleLine = true,
                            visualTransformation = if (showTokenPlaintext) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (tokenInput.isNotBlank() && !state.isLoggingInNord) {
                                        onLoginNordToken(tokenInput)
                                    }
                                }
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showTokenPlaintext = !showTokenPlaintext },
                                    modifier = Modifier.testTag("nordvpn_toggle_token_visibility")
                                ) {
                                    Icon(
                                        imageVector = if (showTokenPlaintext) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = if (showTokenPlaintext) "Hide key" else "Show key"
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CinemaAmber,
                                unfocusedBorderColor = ObsidianOutline,
                                focusedLabelColor = CinemaAmber
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nordvpn_token_input")
                        )

                        if (!state.loginError.isNullOrBlank()) {
                            Text(
                                text = state.loginError,
                                style = MaterialTheme.typography.bodySmall,
                                color = SignalRose,
                                modifier = Modifier.testTag("nordvpn_login_error")
                            )
                        }

                        Button(
                            onClick = { onLoginNordToken(tokenInput) },
                            enabled = tokenInput.isNotBlank() && !state.isLoggingInNord,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CinemaAmber,
                                contentColor = Color(0xFF140D02)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                                .testTag("nordvpn_login_button")
                        ) {
                            if (state.isLoggingInNord) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF140D02)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Authenticating via 1.1.1.1…")
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Connect 1.1.1.1 + NordVPN")
                            }
                        }

                        Text(
                            text = "Exchanged over 1.1.1.1 DoH directly with api.nordvpn.com for your NordLynx WireGuard key and encrypted on-device with Android Keystore AES-256-GCM. Replace your key anytime.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Logged-in controls: Enable Switch, Replace Key anytime, Sign Out
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "In-App NordVPN Tunnel",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = when {
                                        state.isConnectingVpn -> "Connecting NordLynx to ${state.selectedLocation.displayLabel}…"
                                        state.nordVpnEnabled && state.activeNordLynxHostname.isNotBlank() ->
                                            "Connected · ${state.activeNordLynxHostname}"
                                        state.nordVpnEnabled -> "Active · ${state.selectedLocation.displayLabel}"
                                        else -> "Disconnected — toggle ON to connect"
                                    },
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state.nordVpnEnabled) SubtitleEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = state.nordVpnEnabled,
                                onCheckedChange = onToggleNordEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF061610),
                                    checkedTrackColor = SubtitleEmerald
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .tvFocusHighlight(shape = RoundedCornerShape(16.dp))
                                    .testTag("nordvpn_enable_toggle")
                            )
                        }

                        HorizontalDivider(color = ObsidianOutline.copy(alpha = 0.4f))

                        // Smart Auto-VPN Failover (Direct ISP first; auto-connect NordVPN on stalls/failures)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.smart_vpn_failover_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.smart_vpn_failover_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.smartAutoVpnFailoverEnabled) PrivacyCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = state.smartAutoVpnFailoverEnabled,
                                onCheckedChange = onToggleSmartAutoVpnFailover,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF061018),
                                    checkedTrackColor = PrivacyCyan
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .tvFocusHighlight(shape = RoundedCornerShape(16.dp))
                                    .testTag("nordvpn_smart_failover_switch")
                            )
                        }

                        if (!state.loginError.isNullOrBlank() && !isReplacingKey) {
                            Text(
                                text = state.loginError,
                                style = MaterialTheme.typography.bodySmall,
                                color = SignalRose,
                                modifier = Modifier.testTag("nordvpn_login_error")
                            )
                        }

                        if (isReplacingKey) {
                            OutlinedTextField(
                                value = replaceTokenInput,
                                onValueChange = { replaceTokenInput = it },
                                label = { Text("New NordVPN Access Key") },
                                placeholder = { Text("Paste replacement key") },
                                singleLine = true,
                                visualTransformation = if (showReplaceTokenPlaintext) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (replaceTokenInput.isNotBlank() && !state.isLoggingInNord) {
                                            onLoginNordToken(replaceTokenInput)
                                        }
                                    }
                                ),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { showReplaceTokenPlaintext = !showReplaceTokenPlaintext }
                                    ) {
                                        Icon(
                                            imageVector = if (showReplaceTokenPlaintext) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = if (showReplaceTokenPlaintext) "Hide key" else "Show key"
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CinemaAmber,
                                    unfocusedBorderColor = ObsidianOutline,
                                    focusedLabelColor = CinemaAmber
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("nordvpn_replace_token_input")
                            )

                            if (!state.loginError.isNullOrBlank()) {
                                Text(
                                    text = state.loginError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SignalRose,
                                    modifier = Modifier.testTag("nordvpn_login_error")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        isReplacingKey = false
                                        replaceTokenInput = ""
                                    },
                                    enabled = !state.isLoggingInNord,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("nordvpn_replace_cancel_button")
                                ) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = { onLoginNordToken(replaceTokenInput) },
                                    enabled = replaceTokenInput.isNotBlank() && !state.isLoggingInNord,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CinemaAmber,
                                        contentColor = Color(0xFF140D02)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .minimumInteractiveComponentSize()
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("nordvpn_replace_submit_button")
                                ) {
                                    if (state.isLoggingInNord) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF140D02)
                                        )
                                    } else {
                                        Text("Save & Reconnect")
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { isReplacingKey = true },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.65f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("nordvpn_replace_key_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SwapHoriz,
                                        contentDescription = null,
                                        tint = CinemaAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text("Replace Key", color = CinemaAmber)
                                }

                                OutlinedButton(
                                    onClick = onLogoutNord,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, SignalRose.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                                        .testTag("nordvpn_logout_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                                        contentDescription = null,
                                        tint = SignalRose,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text("Sign Out", color = SignalRose)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
