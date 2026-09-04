package com.sotto

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the microphone and the process alive while Sotto listens with the screen off or
 * the app in the background. The audio itself runs in the app-wide engine; this service
 * only holds the foreground notification and a partial wake lock.
 */
class ListenService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as SottoApplication).engine.setListening(false)
            stopSelf()
            return START_NOT_STICKY
        }
        // A sticky restart after the process was killed: no engine is listening yet, and the
        // microphone permission may have been taken away in the meantime.
        val restarted = intent == null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, SottoApplication.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Sotto is listening")
            .setContentText("Messages arrive even with the screen off")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                // FOREGROUND_SERVICE_TYPE_MICROPHONE arrived in API 30, not 29.
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {   // background start refused, or a permission changed under us
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            // No timeout on purpose. This is the whole point of the service: the user has asked
            // to keep listening with the screen off, the notification says so, and it goes away
            // the moment they stop. A timeout here would silently deafen the phone overnight.
            // Not reference counted, so a second start command cannot stack a second acquire.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sotto:listen")
                .also { it.setReferenceCounted(false); it.acquire() }
        }
        // A sticky restart brings the process back with no activity, so nothing else will start
        // the radio or the carry network. setListening does all three.
        if (restarted) (application as SottoApplication).engine.setListening(true)
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.sotto.STOP_LISTENING"

        /**
         * False when the system refused. From Android 12 a foreground service cannot be started
         * from the background, and from Android 14 one that holds the microphone cannot be
         * started from the background at all -- and the exception is thrown here, at the caller,
         * where it would take the app down rather than surface as a failure.
         */
        fun start(context: Context): Boolean =
            runCatching { context.startForegroundService(Intent(context, ListenService::class.java)) }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ListenService::class.java)) }
        }
    }
}
