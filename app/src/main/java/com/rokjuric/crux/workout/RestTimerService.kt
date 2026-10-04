package com.rokjuric.crux.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rokjuric.crux.R
import com.rokjuric.crux.ui.MainActivity

/**
 * Runs for the length of a workout. Shows what is next in an ongoing
 * notification and, during rest, counts down and alerts when the next set is
 * due - with the screen off too. The alert is a normal notification, so Huawei
 * Health can mirror it to the watch.
 */
class RestTimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var title = ""
    private var next = ""
    private var endsAt = 0L

    private val fire = Runnable { restDone() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        title = intent.getStringExtra(EXTRA_TITLE) ?: title
        next = intent.getStringExtra(EXTRA_NEXT) ?: ""
        endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)

        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing(), type)

        handler.removeCallbacks(fire)
        releaseWake()
        val wait = endsAt - System.currentTimeMillis()
        if (endsAt > 0 && wait > 0) {
            NotificationManagerCompat.from(this).cancel(ALERT_ID)
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crux:rest").apply {
                acquire(wait + 15_000)
            }
            handler.postDelayed(fire, wait)
        }
        return START_NOT_STICKY
    }

    private fun resting() = endsAt > System.currentTimeMillis()

    private fun ongoing(): Notification {
        val b = NotificationCompat.Builder(this, CH_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat_crux)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (resting()) {
            b.setContentTitle("Rest")
                .setContentText(if (next.isNotEmpty()) "Next: $next" else title)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(endsAt)
                .setShowWhen(true)
        } else {
            b.setContentTitle(title)
                .setContentText(next)
                .setShowWhen(false)
        }
        return b.build()
    }

    private fun restDone() {
        endsAt = 0L
        vibrate()
        val alert = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_crux)
            .setContentTitle("Rest over")
            .setContentText(next.ifEmpty { "Next set" })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .setContentIntent(openApp())
            .build()
        notifySafely(ALERT_ID, alert)
        notifySafely(ONGOING_ID, ongoing())
        releaseWake()
    }

    private fun notifySafely(id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-workout; the vibration still happened.
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 700), -1))
    }

    private fun openApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_WORKOUT, true)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopEverything() {
        handler.removeCallbacks(fire)
        releaseWake()
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(fire)
        releaseWake()
        super.onDestroy()
    }

    companion object {
        private const val CH_WORKOUT = "workout"
        private const val CH_ALERT = "rest_over"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_NEXT = "next"
        private const val EXTRA_ENDS_AT = "ends_at"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_WORKOUT, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "What's next and the rest countdown"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Rest over", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts when the next set is due (the app vibrates itself)"
                    enableVibration(false)
                }
            )
        }

        /** [endsAt] in epoch millis starts a rest countdown; 0 just shows [next]. */
        fun update(context: Context, title: String, next: String, endsAt: Long = 0L) {
            val i = Intent(context, RestTimerService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NEXT, next)
                .putExtra(EXTRA_ENDS_AT, endsAt)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        fun stop(context: Context) {
            val i = Intent(context, RestTimerService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}
