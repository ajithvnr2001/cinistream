package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.example.player.NordVpnServer
import com.example.player.PrivateDnsMode
import com.example.player.SystemVpnMonitor
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

private val PrivacyCyan = Color(0xFF38BDF8)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NetworkPrivacySheet(
    state: NetworkPrivacyUiState,
    onSelectDnsMode: (PrivateDnsMode) -> Unit,
    onLoginNordToken: (String) -> Unit,
    onLoginNordConfiguredSecret: () -> Unit,
    onToggleNordEnabled: (Boolean) -> Unit,
    onSelectNordServer: (NordVpnServer) -> Unit,
    onLogoutNord: () -> Unit,
    onDisableAll: () -> Unit = {},
    onProbeRoute: () -> Unit,
    onLaunchCompanionApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstControlFocusRequester = remember { FocusRequester() }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var showTokenPlaintext by rememberSaveable { mutableStateOf(false) }
    var isReplacingKey by rememberSaveable { mutableStateOf(false) }
    var replaceTokenInput by rememberSaveable { mutableStateOf("") }
    var showReplaceTokenPlaintext by rememberSaveable { mutableStateOf(false) }

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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
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
                    Column {
                        Text(
                            text = stringResource(R.string.privacy_sheet_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.privacy_sheet_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Live Route Status & 1-Tap Connection Test Card
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
                            val sysLabel = if (state.systemVpnActive) {
                                "Android System VPN detected (active)"
                            } else if (state.nordVpnLoggedIn && state.nordVpnEnabled) {
                                "Location: ${state.selectedNordServerLabel}"
                            } else {
                                "Connect with your NordVPN key + 1.1.1.1 below"
                            }
                            Text(
                                text = sysLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.systemVpnActive || (state.nordVpnLoggedIn && state.nordVpnEnabled)) {
                                    PrivacyCyan
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        Button(
                            onClick = onProbeRoute,
                            enabled = !state.isProbingRoute,
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
                            if (state.isProbingRoute) {
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
                                text = if (state.isProbingRoute) "Testing…" else stringResource(R.string.privacy_test_connection),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    val hasInAppPrivacyEnabled = state.privateDnsMode != PrivateDnsMode.SYSTEM ||
                        (state.nordVpnLoggedIn && state.nordVpnEnabled)
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

            // Section 2: NordVPN Access Key + Replace Anytime + Location Selector
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
                                text = stringResource(R.string.privacy_nordvpn_section_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (state.nordVpnLoggedIn) {
                                    "Key Active · ${state.selectedNordServerLabel}"
                                } else {
                                    "Pick a location & connect with your NordVPN Access Key (auto-pairs 1.1.1.1)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }

                    // Always-available Location Selector (works both before and after login)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Location (${state.availableNordServers.size} NordVPN SOCKS5 Regions)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            state.availableNordServers.forEach { server ->
                                val selected = server.hostname.equals(state.selectedNordServerHost, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectNordServer(server) },
                                    label = { Text(server.label, maxLines = 1) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Public,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SubtitleEmerald.copy(alpha = 0.22f),
                                        selectedLabelColor = SubtitleEmerald,
                                        selectedLeadingIconColor = SubtitleEmerald
                                    ),
                                    modifier = Modifier
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                                        .testTag("nordvpn_server_${server.id}")
                                )
                            }
                        }
                        Text(
                            text = "These are all 12 official NordVPN SOCKS5 proxy regions. For all 118+ countries (India, UK, Singapore, Japan, etc.), use the official NordVPN App below.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                        contentDescription = if (showTokenPlaintext) {
                                            "Hide key"
                                        } else {
                                            "Show key"
                                        }
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
                            text = "Exchanged via HTTPS over 1.1.1.1 DoH directly with api.nordvpn.com and encrypted on-device with Android Keystore AES-256-GCM. You can replace your key anytime.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Logged-in controls: Enable Switch, Replace Key anytime, Logout
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Route Streams Through NordVPN",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (state.nordVpnEnabled) {
                                        "Active exit: ${state.selectedNordServerHost}:1080"
                                    } else {
                                        "Paused — toggle ON to tunnel streams"
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

                        // Inline Replace Key flow (replace NordVPN key anytime without signing out!)
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
                                    border = BorderStroke(1.dp, CinemaAmber.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                                        .testTag("nordvpn_replace_key_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SwapHoriz,
                                        contentDescription = null,
                                        tint = CinemaAmber,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(
                                        text = "Replace Key",
                                        color = CinemaAmber,
                                        maxLines = 1
                                    )
                                }

                                OutlinedButton(
                                    onClick = onLogoutNord,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, SignalRose.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                                        .testTag("nordvpn_logout_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                                        contentDescription = null,
                                        tint = SignalRose,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(
                                        text = "Sign Out",
                                        color = SignalRose,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: 1-Tap System VPN Companion Launchers (Full Device Tunnel)
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
                    Text(
                        text = stringResource(R.string.privacy_system_apps_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Need full-device WireGuard/NordLynx encryption? Launch the official Android / Android TV app directly:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onLaunchCompanionApp(SystemVpnMonitor.PACKAGE_CLOUDFLARE_WARP) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PrivacyCyan.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                                .testTag("launch_warp_app_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = PrivacyCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = if (state.warpAppInstalled) "1.1.1.1 WARP" else "Get 1.1.1.1",
                                color = PrivacyCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = { onLaunchCompanionApp(SystemVpnMonitor.PACKAGE_NORDVPN) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SubtitleEmerald.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .tvFocusHighlight(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                                .testTag("launch_nord_app_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = SubtitleEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = if (state.nordAppInstalled) "NordVPN App" else "Get NordVPN",
                                color = SubtitleEmerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
