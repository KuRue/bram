package io.github.kurue.bram.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
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

        private const val EXTRA_MODEL = "model"
        private const val EXTRA_BACKEND = "backend"
        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_DETAIL = "detail"

        /** Key of the inline reply on the completion alert, read by [CompletionReplyReceiver]. */
        const val KEY_REPLY = "reply"

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
         * finished, with an inline reply that starts the next turn without opening the app. No-op
         * without the notification permission, which Android also requires before it would show.
         */
        fun postCompletion(context: Context, model: String) {
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
            val reply = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, CompletionReplyReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, COMPLETION_CHANNEL_ID)
                .setContentTitle("Turn finished")
                .setContentText(model)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                        "Reply",
                        reply,
                    ).addRemoteInput(
                        RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build(),
                    ).build(),
                )
                .build()
            manager.notify(COMPLETION_NOTIFICATION_ID, notification)
        }
    }
}
