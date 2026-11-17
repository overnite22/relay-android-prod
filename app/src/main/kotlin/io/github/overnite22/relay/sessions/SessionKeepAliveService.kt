package io.github.overnite22.relay.sessions

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
import androidx.core.app.ServiceCompat
import io.github.overnite22.relay.MainActivity
import io.github.overnite22.relay.R

/** Service au premier plan tant que des sessions sont ouvertes : Android ne coupe pas les tunnels en arrière-plan. */
class SessionKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(this, count),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "count"

        fun update(context: Context, count: Int) {
            val intent = Intent(context, SessionKeepAliveService::class.java)
            try {
                if (count > 0) context.startForegroundService(intent.putExtra(EXTRA_COUNT, count)) else context.stopService(intent)
            } catch (_: Exception) {
                // Démarrage refusé (app en arrière-plan) : les sessions restent ouvertes tant qu'Android le permet.
            }
        }

        private fun notification(context: Context, count: Int): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sessions ouvertes", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (count == 1) "1 session ouverte" else "$count sessions ouvertes")
                .setContentText("Relay garde vos sessions connectées.")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
}
