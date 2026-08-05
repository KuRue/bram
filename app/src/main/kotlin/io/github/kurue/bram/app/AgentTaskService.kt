package io.github.kurue.bram.app

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

/**
 * Keeps Bram alive while an agent task is running.
 *
 * The model itself already runs in the isolated `:inference` process, but the work that drives it —
 * collecting the stream, running tools, writing the reply to the conversation — lives in the app
 * process. Without a foreground service Android is free to kill that process as soon as the user
 * leaves, which would abandon a task mid-run and lose the reply. Agent work is expected to be long
 * and to continue while the user does something else, so the run holds a foreground service for as
 * long as it lasts and releases it immediately afterwards.
 */
class AgentTaskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val description = intent?.getStringExtra(EXTRA_DESCRIPTION) ?: DEFAULT_DESCRIPTION
        startInForegroundCompat(buildNotification(description))
        // Do not resurrect the service on its own: a task cannot be resumed from nothing, and a
        // restarted service with no run behind it would show a notification for work that is over.
        return START_NOT_STICKY
    }

    private fun startInForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(description: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Running tasks",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    this.description = "Shown while Bram is working on something for you."
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(BramDefaults.IDENTITY.displayName)
            .setContentText(description)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bram-agent-tasks"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_DESCRIPTION = "description"
        private const val DEFAULT_DESCRIPTION = "Working…"

        fun start(context: Context, description: String) {
            val intent = Intent(context, AgentTaskService::class.java)
                .putExtra(EXTRA_DESCRIPTION, description)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentTaskService::class.java)) }
        }
    }
}
