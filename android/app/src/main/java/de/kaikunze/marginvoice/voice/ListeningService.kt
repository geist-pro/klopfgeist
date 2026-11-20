package de.kaikunze.marginvoice.voice

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.kaikunze.marginvoice.MainActivity
import de.kaikunze.marginvoice.R

/**
 * Keeps the microphone while the screen is off or another app is in front, so
 * voice commands and comments work with the phone in a pocket or a car mount.
 * Runs while the microphone does; the microphone itself lives in [AndroidAudioCapture].
 */
class ListeningService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice commands", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Klopfgeist listens for voice commands"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Listening for voice commands")
            .setContentText("Say “stop” to comment, “bookmark” to mark a passage")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "listening"
        private const val ID = 7

        /**
         * Returns false when Android doesn't allow it now: a microphone service can only
         * start while the app is on screen (from the background `startForeground` throws).
         */
        fun start(context: Context): Boolean {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            if (state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java))
            }.isSuccess
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningService::class.java))
        }
    }
}
