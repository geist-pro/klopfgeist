package de.kaikunze.marginvoice.conversion

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
import de.kaikunze.marginvoice.MainActivity
import de.kaikunze.marginvoice.R

/**
 * Keeps conversions running with the app in the background ("Creating audiobook"
 * with its progress; iOS: a continued processing task with a Live Activity).
 */
class ConversionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(ID, notification(this, intent?.getStringExtra(TITLE) ?: "", intent?.getIntExtra(PERCENT, 0) ?: 0), type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "conversion"
        private const val ID = 8
        private const val TITLE = "title"
        private const val PERCENT = "percent"

        private fun notification(context: Context, title: String, percent: Int) = run {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Creating audiobooks", NotificationManager.IMPORTANCE_LOW),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Creating audiobook")
                .setContentText(title)
                .setProgress(100, percent, false)
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        fun start(context: Context, title: String) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ConversionService::class.java).putExtra(TITLE, title))
        }.isSuccess

        fun update(context: Context, title: String, fraction: Double) {
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(ID, notification(context, title, (fraction * 100).toInt()))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConversionService::class.java))
        }
    }
}
