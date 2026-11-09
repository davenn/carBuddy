package com.davenn.carbuddy

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder

/**
 * Asks for screen-capture permission. Android only shows that prompt from an
 * activity, so this invisible one exists just to show it and pass the answer
 * on to [ProjectionService].
 */
class ProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // prompt already showing
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Whole screen only: skips the "one app or entire screen" choice.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    @Deprecated("Activity result API is not worth a dependency here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, ProjectionService::class.java)
                    .putExtra(ProjectionService.EXTRA_CODE, resultCode)
                    .putExtra(ProjectionService.EXTRA_DATA, data)
            )
            Notifications.cancelStartPrompt(this)
        }
        finish()
    }

    companion object {
        private const val REQUEST = 1

        fun intent(context: Context): Intent =
            Intent(context, ProjectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /**
         * Shows the prompt on the phone. Called from the car, where Android may
         * block the launch as a background start, so a notification that does
         * the same thing is posted as well.
         */
        fun request(context: Context) {
            try {
                context.startActivity(intent(context))
            } catch (_: Exception) {}
            Notifications.showStartPrompt(context)
        }
    }
}

/**
 * Holds the screen capture. Android requires capture to run inside a foreground
 * service of type mediaProjection, which also shows the ongoing notification.
 */
class ProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Mirror.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be in the foreground before getMediaProjection() on Android 14+.
        val notification = Notifications.mirroring(this)
        startForeground(Notifications.ID_MIRRORING, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Detach first: Mirror.start() ends any earlier capture, and that must
        // not stop this service, which now holds the new one.
        Mirror.removeListener(stopWhenMirrorEnds)
        Mirror.start(this, mp)
        Mirror.addListener(stopWhenMirrorEnds)
        return START_NOT_STICKY
    }

    private val stopWhenMirrorEnds: () -> Unit = {
        if (!Mirror.isRunning) stopSelf()
    }

    override fun onDestroy() {
        Mirror.removeListener(stopWhenMirrorEnds)
        Mirror.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.davenn.carbuddy.STOP"

        fun stop(context: Context) {
            if (Mirror.isRunning) {
                context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

object Notifications {
    private const val CHANNEL_MIRROR = "mirror"
    private const val CHANNEL_PROMPT = "prompt"
    const val ID_MIRRORING = 1
    private const val ID_START = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MIRROR, "Mirroring", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPT, "Start prompts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun mirroring(context: Context): Notification {
        val stop = PendingIntent.getService(
            context, 0,
            Intent(context, ProjectionService::class.java).setAction(ProjectionService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Mirroring to the car screen")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    fun showStartPrompt(context: Context) {
        val open = PendingIntent.getActivity(
            context, 0, ProjectionActivity.intent(context), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Start mirroring to the car")
            .setContentText("Tap, then choose Start now.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID_START, n)
        } catch (_: SecurityException) {} // notifications not allowed
    }

    fun cancelStartPrompt(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(ID_START)
}
