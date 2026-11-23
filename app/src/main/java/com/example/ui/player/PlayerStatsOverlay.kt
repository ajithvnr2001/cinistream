package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.player.DecoderFallbackHelper
import com.example.player.PlayerNetworkStats
import com.example.player.PlayerStatsFormatter
import com.example.player.PrivateDnsMode
import com.example.ui.components.tvFocusHighlight
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaAmberBright
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SignalRose
import com.example.ui.theme.SubtitleEmerald
import kotlinx.coroutines.flow.StateFlow

/**
 * YouTube-style "Stats for Nerds" floating telemetry overlay displaying live Connection Speed,
 * Network Activity sparkline, Buffer Health sparkline, Buffer/JVM Heap caps, Codecs & Dropped Frames.
 *
 * Collects [statsFlow] locally so 1-second telemetry ticks never recompose [PlayerScreen].
 */
@Composable
fun PlayerStatsOverlay(
    statsFlow: StateFlow<PlayerNetworkStats>,
    isTv: Boolean,
    showCloseButton: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by statsFlow.collectAsStateWithLifecycle()
    PlayerStatsCard(
        stats = stats,
        isTv = isTv,
        showCloseButton = showCloseButton,
        onClose = onClose,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerStatsCard(
    stats: PlayerNetworkStats,
    isTv: Boolean,
    showCloseButton: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxCardWidth = if (isTv) 420.dp else 330.dp
    val labelFontSize = if (isTv) 11.sp else 10.sp
    val valueFontSize = if (isTv) 11.5.sp else 10.5.sp

    val bufferSec = stats.bufferHealthMs / 1000f
    val bufferAccent = when {
        stats.playbackStateLabel == "BUFFERING" || bufferSec < 2f -> SignalRose
        bufferSec < 5f -> CinemaAmberBright
        else -> SubtitleEmerald
    }

    val stateAccent = when (stats.playbackStateLabel) {
        "PLAYING" -> SubtitleEmerald
        "BUFFERING" -> CinemaAmberBright
        "PAUSED" -> Color(0xFF90CAF9)
        else -> Color.White.copy(alpha = 0.72f)
    }

    Surface(
        modifier = modifier
            .widthIn(min = 250.dp, max = maxCardWidth)
            .testTag("player_stats_overlay"),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xCC0A0C12),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = if (isTv) 8.dp else 9.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 3.5.dp else 4.5.dp)
        ) {
            // Header row: YouTube-style Stats for Nerds title + live state pill + optional Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NetworkCheck,
                        contentDescription = null,
                        tint = CinemaAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "STATS FOR NERDS",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isTv) 11.sp else 10.sp,
                        letterSpacing = 0.8.sp,
                        color = Color.White
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = stateAccent.copy(alpha = 0.2f),
                        border = BorderStroke(0.75.dp, stateAccent.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = stats.playbackStateLabel,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp,
                            color = stateAccent,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    if (!stats.isLocalSource && stats.networkTransportLabel.isNotBlank()) {
                        val isQuic = stats.networkTransportLabel.contains("HTTP/3")
                        val transportAccent = if (isQuic) SubtitleEmerald else Color(0xFF7DD3FC)
                        val shortBadge = if (isQuic) "HTTP/3" else "HTTP/2"
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = transportAccent.copy(alpha = 0.18f),
                            border = BorderStroke(0.75.dp, transportAccent.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = shortBadge,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp,
                                color = transportAccent,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (!stats.isLocalSource && stats.privateDnsMode != PrivateDnsMode.SYSTEM) {
                        val dnsAccent = Color(0xFF38BDF8)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = dnsAccent.copy(alpha = 0.18f),
                            border = BorderStroke(0.75.dp, dnsAccent.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = stats.privateDnsMode.shortBadge,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp,
                                color = dnsAccent,
                                modifier = Modifier
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                    .testTag("stats_dns_badge")
                            )
                        }
                    }
                    if (!stats.isLocalSource && (stats.nordVpnActive || stats.systemVpnActive)) {
                        val vpnLabel = if (stats.nordVpnActive) "NORD" else "VPN"
                        val vpnAccent = if (stats.nordVpnActive) SubtitleEmerald else Color(0xFF38BDF8)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = vpnAccent.copy(alpha = 0.2f),
                            border = BorderStroke(0.75.dp, vpnAccent.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = vpnLabel,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp,
                                color = vpnAccent,
                                modifier = Modifier
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                    .testTag("stats_vpn_badge")
                            )
                        }
                    }
                }

                if (showCloseButton) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(28.dp)
                            .tvFocusHighlight(shape = CircleShape)
                            .testTag("stats_overlay_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close stats overlay",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

            // Source / Format
            StatLine(
                label = "Stream",
                value = "${stats.streamFormatLabel} · ${stats.streamHost}",
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )

            // Connection Speed (live bandwidth estimate)
            StatLine(
                label = "Conn Speed",
                value = if (stats.isLocalSource) {
                    "Local Storage"
                } else {
                    PlayerStatsFormatter.formatBitrate(stats.connectionSpeedBps)
                },
                valueColor = Color(0xFF7DD3FC),
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )

            // Network Activity + YouTube-style live bar sparkline
            StatSparklineRow(
                label = "Net Activity",
                value = "${PlayerStatsFormatter.formatThroughput(stats.networkActivityBytesPerSec)} · ${PlayerStatsFormatter.formatBytes(stats.sessionDownloadedBytes)}",
                samples = stats.networkActivityHistoryKBps,
                barColor = Color(0xFF38BDF8),
                valueColor = Color(0xFF38BDF8),
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )

            // Buffer Health + YouTube-style live bar sparkline
            val stallSummary = if (stats.rebufferCount > 0) {
                " · ${stats.rebufferCount} stall${if (stats.rebufferCount == 1) "" else "s"}"
            } else {
                ""
            }
            StatSparklineRow(
                label = "Buffer Health",
                value = "${PlayerStatsFormatter.formatBufferSeconds(stats.bufferHealthMs)} (${stats.bufferedPercent}%)$stallSummary",
                samples = stats.bufferHealthHistorySec,
                barColor = bufferAccent,
                valueColor = bufferAccent,
                labelSize = labelFontSize,
                valueSize = valueFontSize,
                fixedMax = 20f
            )

            // Buffer RAM & JVM Heap (directly shows heap-capped allocator health on TV)
            StatLine(
                label = "Buffer / Heap",
                value = "${PlayerStatsFormatter.formatBytes(stats.allocatedBufferBytes)}/${PlayerStatsFormatter.formatBytes(stats.targetBufferBytes.toLong())} · JVM ${PlayerStatsFormatter.formatBytes(stats.jvmHeapUsedBytes)}/${PlayerStatsFormatter.formatBytes(stats.jvmHeapMaxBytes)}",
                valueColor = CinemaAmberBright,
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )

            // Active CDN Node & Local Edge Disk Cache / TV RAM-Direct mode
            val cdnNodePart = if (stats.isLocalSource) {
                "Direct File"
            } else {
                val failPart = if (stats.cdnFailoverCount > 0) " (${stats.cdnFailoverCount}x failover)" else ""
                "Node ${stats.activeCdnIndex}/${stats.totalCdnNodes}$failPart"
            }
            val cachePart = when {
                stats.isLocalSource -> ""
                stats.isRamDirectCacheMode -> " · RAM Direct"
                stats.diskCacheMaxBytes > 0L ->
                    " · Edge ${PlayerStatsFormatter.formatBytes(stats.diskCacheUsedBytes)}/${PlayerStatsFormatter.formatBytes(stats.diskCacheMaxBytes)}"
                else -> ""
            }
            val prefetchPart = when {
                stats.isLocalSource || stats.prefetchDepth <= 0 -> ""
                stats.prefetchHits > 0L -> " · ${stats.prefetchDepth}× Prefetch (${stats.prefetchHitRatePercent}%)"
                else -> " · ${stats.prefetchDepth}× Prefetch"
            }
            StatLine(
                label = "CDN / Cache",
                value = "$cdnNodePart$cachePart$prefetchPart",
                valueColor = if (stats.cdnFailoverCount > 0) CinemaAmberBright else SubtitleEmerald,
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Video Resolution, FPS, Codec & Bitrate
            val videoBitrateText = if (stats.videoBitrateBps > 0) {
                " · ${PlayerStatsFormatter.formatBitrate(stats.videoBitrateBps.toLong())}"
            } else ""
            StatLine(
                label = "Video",
                value = "${PlayerStatsFormatter.formatResolution(stats.videoWidth, stats.videoHeight, stats.videoFps)}$videoBitrateText",
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )
            StatLine(
                label = "Codecs",
                value = "${stats.videoCodec} / ${PlayerStatsFormatter.formatAudioDetail(stats.audioCodec, stats.audioChannels, stats.audioSampleRateHz, stats.audioBitrateBps)}",
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )
            val decoderLabel = DecoderFallbackHelper.formatDecoderTag(stats.activeVideoDecoderName)
            val isSwDecoder = stats.activeVideoDecoderName.isNotBlank() &&
                DecoderFallbackHelper.isSoftwareCodecName(stats.activeVideoDecoderName)
            StatLine(
                label = "Decoder",
                value = if (decoderLabel != "—") decoderLabel else stats.effectiveDecoderMode.label,
                valueColor = if (isSwDecoder) CinemaAmberBright else SubtitleEmerald,
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )
            StatLine(
                label = "Dropped",
                value = "${stats.droppedFrames} frames",
                valueColor = if (stats.droppedFrames > 5L) SignalRose else Color.White.copy(alpha = 0.9f),
                labelSize = labelFontSize,
                valueSize = valueFontSize
            )
        }
    }
}

@Composable
private fun StatLine(
    label: String,
    value: String,
    valueColor: Color = Color.White.copy(alpha = 0.92f),
    labelSize: androidx.compose.ui.unit.TextUnit,
    valueSize: androidx.compose.ui.unit.TextUnit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = labelSize,
            color = Color.White.copy(alpha = 0.62f),
            modifier = Modifier.width(86.dp),
            maxLines = 1
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = valueSize,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatSparklineRow(
    label: String,
    value: String,
    samples: List<Float>,
    barColor: Color,
    valueColor: Color,
    labelSize: androidx.compose.ui.unit.TextUnit,
    valueSize: androidx.compose.ui.unit.TextUnit,
    fixedMax: Float? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatLine(
            label = label,
            value = value,
            valueColor = valueColor,
            labelSize = labelSize,
            valueSize = valueSize
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.width(86.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    val slotCount = 24
                    val gapPx = 1.5.dp.toPx()
                    val barW = ((size.width - gapPx * (slotCount - 1)) / slotCount).coerceAtLeast(1.5f)
                    val maxVal = fixedMax ?: (samples.maxOrNull()?.coerceAtLeast(128f) ?: 128f)
                    val offset = (slotCount - samples.size).coerceAtLeast(0)

                    for (i in samples.indices) {
                        val frac = (samples[i] / maxVal).coerceIn(0f, 1f)
                        val barH = (size.height * frac).coerceAtLeast(if (samples[i] > 0f) 1.5f else 0.75f)
                        val left = (offset + i) * (barW + gapPx)
                        drawRoundRect(
                            color = if (samples[i] > 0f) barColor else Color.White.copy(alpha = 0.14f),
                            topLeft = Offset(left, size.height - barH),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}
