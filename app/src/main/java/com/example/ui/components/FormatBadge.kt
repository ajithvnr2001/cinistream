package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.StreamFormat
import com.example.ui.theme.BadgeDash
import com.example.ui.theme.BadgeHls
import com.example.ui.theme.BadgeM3u
import com.example.ui.theme.BadgeMkv
import com.example.ui.theme.BadgeMp4
import com.example.ui.theme.SlateInkSecondary
import java.util.Locale

fun StreamFormat.accentColor(): Color = when (this) {
    StreamFormat.MKV -> BadgeMkv
    StreamFormat.MP4, StreamFormat.WEBM -> BadgeMp4
    StreamFormat.HLS, StreamFormat.M3U8 -> BadgeHls
    StreamFormat.M3U -> BadgeM3u
    StreamFormat.DASH -> BadgeDash
    StreamFormat.AUTO -> SlateInkSecondary
}

@Composable
fun FormatBadge(
    format: StreamFormat,
    modifier: Modifier = Modifier
) {
    val accent = format.accentColor()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color(0xBF09090B),
        border = BorderStroke(0.75.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = format.badge,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SubtitlePillBadge(
    text: String,
    accent: Color = MaterialTheme.colorScheme.tertiary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(0.75.dp, accent.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatOffsetSeconds(offsetMs: Long): String {
    val sec = offsetMs / 1000.0
    return if (offsetMs > 0L) {
        String.format(Locale.US, "+%.2fs", sec)
    } else if (offsetMs < 0L) {
        String.format(Locale.US, "%.2fs", sec)
    } else {
        "0.00s"
    }
}
