package io.github.kurue.bram.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Keeps Bram alive while a model is loaded, and reports what it is doing.
 *
 * The model itself runs in the isolated `:inference` process, but the work that drives it —
 * collecting the stream, running tools, writing the reply to the conversation — lives in the app
 * process. Without a foreground service Android is free to kill that process as soon as the user
 * leaves, which would abandon a turn mid-run, lose the reply, and drop the loaded model's KV
 * cache. A loaded model is held like a server holds one: the service starts when a model loads,
 * stops when the last one unloads, and the notification shows the loaded model, its backend, and
 * its current phase from the same events the transcript consumes.
 *
 * The opt-in completion alert is the same notification surfaced at the end of a turn: a separate,
 * dismissible notification that a reply has finished, with a reply action that starts the next
 * turn without opening the app.
 */
class AgentTaskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = intent?.getStringExtra(EXTRA_MODEL) ?: BramDefaults.IDENTITY.displayName
        val backend = intent?.getStringExtra(EXTRA_BACKEND).orEmpty()
        val phase = intent?.getStringExtra(EXTRA_PHASE)
            ?.let { wire -> ModelPhase.entries.firstOrNull { it.wire == wire } }
            ?: ModelPhase.IDLE
        val detail = intent?.getStringExtra(EXTRA_DETAIL)
        startInForegroundCompat(buildNotification(model, backend, phase, detail))
        // Do not resurrect the service on its own: a loaded model cannot be resumed from nothing,
        // and a restarted service with no model behind it would show a notification for work that
        // is over.
        return START_NOT_STICKY
    }

    private fun startInForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        model: String,
        backend: String,
        phase: ModelPhase,
        detail: String?,
    ): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Running model",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    this.description = "Shown while a model is loaded and Bram can keep working in the background."
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
            .setContentTitle(model)
            .setContentText(buildString {
                if (backend.isNotBlank()) {
                    append(backend)
                    append(" · ")
                }
                append(phase.label)
                if (!detail.isNullOrBlank()) {
                    append(" — ")
                    append(detail)
                }
            })
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bram-agent-tasks"
        private const val NOTIFICATION_ID = 1001

        private const val COMPLETION_CHANNEL_ID = "bram-completions"
        private const val COMPLETION_NOTIFICATION_ID = 1002
        private const val TASK_CHANNEL_ID = "bram-task-results"
        private const val TASK_NOTIFICATION_ID = 1003

        private const val EXTRA_MODEL = "model"
        private const val EXTRA_BACKEND = "backend"
        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_DETAIL = "detail"

        /**
         * Starts the status service or moves it to the given state. Called on every phase change,
         * so a turn can be followed from the shade; the service itself decides what to show.
         */
        fun update(context: Context, model: String, backend: String, phase: ModelPhase, detail: String? = null) {
            val intent = Intent(context, AgentTaskService::class.java)
                .putExtra(EXTRA_MODEL, model)
                .putExtra(EXTRA_BACKEND, backend)
                .putExtra(EXTRA_PHASE, phase.wire)
                .putExtra(EXTRA_DETAIL, detail)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentTaskService::class.java)) }
        }

        /**
         * Posts the opt-in completion alert: a dismissible notification that a backgrounded turn
         * finished, showing a summary of the reply so the shade reads it before the app is opened.
         * No-op without the notification permission, which Android also requires before it would
         * show.
         */
        fun postCompletion(context: Context, model: String, summary: String) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(COMPLETION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        COMPLETION_CHANNEL_ID,
                        "Turn finished",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        this.description = "Posted when a turn finishes while Bram is in the background."
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = summary.take(MAX_SUMMARY_LENGTH) +
                if (summary.length > MAX_SUMMARY_LENGTH) "…" else ""
            val notification = Notification.Builder(context, COMPLETION_CHANNEL_ID)
                .setContentTitle("Turn finished")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(COMPLETION_NOTIFICATION_ID, notification)
        }

        /**
         * Posts the result of a finished scheduled task. Only when the permission is held, and
         * channel is [NotificationManager.IMPORTANCE_DEFAULT] so a task completing is actually
         * noticed — unlike the running-model status, this is the alert the user scheduled work for.
         */
        fun postTaskFinished(context: Context, task: AgentTask) {
            val manager = notificationManager(context) ?: return
            val title = when (task.state) {
                TaskState.SUCCEEDED -> "Task finished: ${task.displayName}"
                TaskState.FAILED -> "Task failed: ${task.displayName}"
                TaskState.CANCELLED -> "Task cancelled: ${task.displayName}"
                else -> return
            }
            val text = task.resultSummary ?: task.error ?: "Done."
            val notification = Notification.Builder(context, TASK_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text.take(MAX_SUMMARY_LENGTH))
                .setStyle(Notification.BigTextStyle().bigText(text.take(MAX_SUMMARY_LENGTH)))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openPendingIntent(context))
                .setAutoCancel(true)
                .build()
            manager.notify(TASK_NOTIFICATION_ID + task.id.hashCode() % 1000, notification)
        }

        /** Says a scheduled task came due but cannot run until a model is loaded. */
        fun postTaskDeferred(context: Context, task: AgentTask) {
            val manager = notificationManager(context) ?: return
            val text = "The task \"${task.displayName}\" is due, but no model is loaded. " +
                "It will run as soon as one is."
            val notification = Notification.Builder(context, TASK_CHANNEL_ID)
                .setContentTitle("Task waiting for a model")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openPendingIntent(context))
                .setAutoCancel(true)
                .build()
            manager.notify(TASK_NOTIFICATION_ID + task.id.hashCode() % 1000, notification)
        }

        private fun notificationManager(context: Context): NotificationManager? {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return null
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(TASK_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        TASK_CHANNEL_ID,
                        "Task results",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        this.description = "Posted when a scheduled task finishes or is waiting for a model."
                    },
                )
            }
            return manager
        }

        private fun openPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private const val MAX_SUMMARY_LENGTH = 500
    }
}
