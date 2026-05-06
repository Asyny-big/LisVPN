package com.lisvpn.android.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.vpn.core.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground-service notification builder. Avoids any business logic; given a [VpnState],
 * returns a notification suitable for [android.app.Service.startForeground].
 */
@Singleton
class VpnNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_VPN) == null) {
                val channel = NotificationChannel(
                    CHANNEL_VPN,
                    "VPN tunnel",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Active VPN connection state"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun build(state: VpnState, openAppPendingIntent: PendingIntent?): Notification {
        ensureChannels()
        val (title, text) = when (state) {
            is VpnState.Connecting -> "Подключение…" to (state.serverDisplayName ?: "Поиск маршрута")
            is VpnState.Connected -> "VPN включён" to "${state.server.displayName}${state.pingMs?.let { " · $it ms" } ?: ""}"
            is VpnState.Reconnecting -> "Переподключение" to (state.previousServerDisplayName ?: "")
            is VpnState.Error -> "Ошибка" to (state.detail ?: state.reason.name)
            VpnState.Preparing -> "Подключение…" to "Запрос разрешения VPN"
            VpnState.Disconnecting -> "Отключение…" to ""
            VpnState.Idle -> "VPN выключен" to ""
        }

        val stopIntent = Intent(context, LisVpnService::class.java).apply { action = VpnIntents.ACTION_STOP }
        val stopPi = PendingIntent.getService(
            context,
            REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        openAppPendingIntent?.let(builder::setContentIntent)

        if (state is VpnState.Connected || state is VpnState.Connecting || state is VpnState.Reconnecting) {
            builder.addAction(0, "Отключить", stopPi)
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_VPN = "vpn_tunnel"
        const val NOTIFICATION_ID = 1001
        private const val REQ_STOP = 11
    }
}
