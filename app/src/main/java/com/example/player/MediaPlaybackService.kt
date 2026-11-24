package com.example.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Google Play compliant Foreground Service (`foregroundServiceType="mediaPlayback"`) keeping
 * CineStream audio playback alive while the screen is locked or while the user switches apps
 * when Background Audio Mode is explicitly enabled.
 */
class MediaPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "cinestream_media_playback"
        const val NOTIFICATION_ID = 4201

        const val ACTION_START_OR_UPDATE = "com.example.action.MEDIA_BG_START"
        const val ACTION_STOP = "com.example.action.MEDIA_BG_STOP"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.action.MEDIA_BG_TOGGLE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        @Volatile
        var onNotificationTogglePlayPause: (() -> Unit)? = null

        @Volatile
        var onNotificationStop: (() -> Unit)? = null

        fun startOrUpdate(
            context: Context,
            title: String,
            subtitle: String,
            isPlaying: Boolean
        ) {
            val intent = Intent(context.applicationContext, MediaPlaybackService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, MediaPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.applicationContext.stopService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                onNotificationStop?.invoke()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                onNotificationTogglePlayPause?.invoke()
                return START_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.app_name) }
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE).orEmpty().ifBlank { "Background audio playing" }
                val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, true) ?: true
                val notification = buildNotification(title, subtitle, isPlaying)
                startForegroundCompat(notification)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "CineStream Background Playback",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controls for background audio playback"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(title: String, subtitle: String, isPlaying: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val togglePending = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPending)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                togglePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending
            )
            .build()
    }
}
