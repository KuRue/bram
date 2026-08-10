package io.github.kurue.bram.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools that act on the phone's own surfaces: app-private files, the clipboard, notifications,
 * alarms, and other apps through intents.
 *
 * Everything here either reads data a person might consider theirs (clipboard, contacts, calendar)
 * or does something another app or the system can see (notifications, alarms, launching apps), so
 * nothing takes the AUTO fast path: each tool names a permission token, and the approval card shows
 * what will happen before the gate lets it through. The tokens that map to Android runtime
 * permissions ([RuntimePermissions.TOKEN_NOTIFICATIONS], [TOKEN_CONTACTS], [TOKEN_CALENDAR]) are
 * requested through the system dialog by [PermissionAwareApprovalGate] after the user allows the
 * call.
 */

/** Reads and writes files in a dedicated app-private directory. Nothing outside Bram is reachable. */
class FilesTool(context: Context) : ToolHandler {
    private val root = File(context.applicationContext.filesDir, AGENT_FILES_DIR)

    override val definition = ToolDefinition(
        name = "write_file",
        description = "Write a file into Bram's private files area under a given relative path. " +
            "The area starts empty; use list_files to see what is there.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "path":{"type":"string","description":"Relative path, e.g. notes/todo.txt."},
               "body":{"type":"string","description":"What to write. Truncated beyond 100 KB."}},
             "required":["path","body"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_PRIVATE_STORAGE),
        approvalScopeKeys = listOf("path"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val path = safeRelativePath(arguments.optString("path"))
            ?: return@withContext toolError("invalid_path", "Path must be a relative path inside Bram's files")
        val body = arguments.optString("body").take(MAX_WRITE_CHARS)
        runCatching {
            root.mkdirs()
            val target = File(root, path)
            target.parentFile?.mkdirs()
            target.writeText(body)
            JSONObject()
                .put("wrote", path)
                .put("bytes", body.toByteArray().size)
                .toString()
        }.getOrElse { failure ->
            toolError("write_failed", failure.message ?: failure::class.java.simpleName)
        }
    }

    companion object {
        const val AGENT_FILES_DIR = "agent-files"

        /**
         * Reduces a model-supplied path to one contained under the root.
         *
         * The model chooses this string, and a model repeating something it read is how a path like
         * `../../databases/x` gets attempted. Each segment is stripped of separators and traversal,
         * so an unhelpful path still writes somewhere sensible inside the area.
         */
        fun safeRelativePath(raw: String): String? {
            val segments = raw.split('/', '\\').mapNotNull { segment ->
                segment
                    .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ' ' }
                    .trim('.', ' ')
                    .take(64)
                    .takeIf(String::isNotEmpty)
            }
            if (segments.isEmpty()) return null
            return segments.joinToString("/")
        }
    }
}

/** Lists the files Bram's agent has written, so a model knows what it has to work with. */
class ListFilesTool(context: Context) : ToolHandler {
    private val root = File(context.applicationContext.filesDir, FilesTool.AGENT_FILES_DIR)

    override val definition = ToolDefinition(
        name = "list_files",
        description = "List the files in Bram's private files area, with sizes, newest last.",
        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        readOnly = true,
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val entries = JSONArray()
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                entries.put(
                    JSONObject()
                        .put("path", file.relativeTo(root).path.replace('\\', '/'))
                        .put("bytes", file.length()),
                )
            }
            JSONObject().put("files", entries).toString()
        }.getOrElse { failure ->
            toolError("list_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

/** Reads a file back, capped at a size a context window can hold. */
class ReadFileTool(context: Context) : ToolHandler {
    private val root = File(context.applicationContext.filesDir, FilesTool.AGENT_FILES_DIR)

    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read a file from Bram's private files area, truncated beyond 64 KB.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "path":{"type":"string","description":"Relative path, e.g. notes/todo.txt."}},
             "required":["path"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
        approvalScopeKeys = listOf("path"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val path = FilesTool.safeRelativePath(arguments.optString("path"))
            ?: return@withContext toolError("invalid_path", "Path must be a relative path inside Bram's files")
        val file = File(root, path)
        if (!file.isFile) return@withContext toolError("not_found", "No file at $path")
        runCatching {
            val text = file.readText()
            val truncated = text.length > MAX_READ_CHARS
            JSONObject()
                .put("path", path)
                .put("bytes", text.toByteArray().size)
                .put("chars", text.length)
                .put("truncated", truncated)
                .put("text", if (truncated) text.take(MAX_READ_CHARS) + "\n…[truncated]…" else text)
                .toString()
        }.getOrElse { failure ->
            toolError("read_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

/** Reads or writes the system clipboard. Both are asked about: reading is private, writing clobbers. */
class ClipboardTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext
    private val clipboard: ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override val definition = ToolDefinition(
        name = "clipboard_get",
        description = "Read the current clipboard text. Ask first: the clipboard is private and " +
            "may not be readable while Bram runs in the background.",
        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        readOnly = true,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_CLIPBOARD),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val manager = clipboard ?: return@withContext toolError("unavailable", "No clipboard service")
        runCatching {
            val clip = manager.primaryClip ?: return@withContext JSONObject().put("text", "").toString()
            val text = (0 until clip.itemCount).joinToString("\n") { index ->
                clip.getItemAt(index).coerceToText(appContext).toString()
            }
            JSONObject().put("chars", text.length).put("text", text.take(MAX_CLIP_CHARS)).toString()
        }.getOrElse { failure ->
            if (failure is SecurityException) {
                toolError("clipboard_blocked", "Android blocks clipboard reads while Bram is not " +
                    "focused; bring Bram to the foreground and try again")
            } else {
                toolError("clipboard_failed", failure.message ?: failure::class.java.simpleName)
            }
        }
    }
}

/** Writes to the system clipboard. */
class ClipboardSetTool(context: Context) : ToolHandler {
    private val clipboard: ClipboardManager? =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override val definition = ToolDefinition(
        name = "clipboard_set",
        description = "Put text on the system clipboard, replacing what is there.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "text":{"type":"string","description":"The text to put on the clipboard."}},
             "required":["text"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_CLIPBOARD),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val manager = clipboard ?: return@withContext toolError("unavailable", "No clipboard service")
        val text = arguments.optString("text")
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("bram", text))
            JSONObject().put("chars", text.length).toString()
        }.getOrElse { failure ->
            toolError("clipboard_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

/**
 * Posts a notification from Bram.
 *
 * The notification appears in the shade like the app's own — it is Bram speaking, not a platform
 * action — so it rides on the same POST_NOTIFICATIONS permission the completion alert uses, which
 * is requested through the system dialog when a call is allowed and the permission is missing.
 */
class NotificationTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext

    override val definition = ToolDefinition(
        name = "post_notification",
        description = "Show a notification from Bram in the status shade.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "title":{"type":"string","description":"Short title."},
               "body":{"type":"string","description":"The message body."}},
             "required":["title","body"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_NOTIFICATIONS),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext toolError("permission_denied", "POST_NOTIFICATIONS is not granted")
        }
        val title = arguments.optString("title").take(120)
        val body = arguments.optString("body").take(500)
        runCatching {
            postAgentNotification(appContext, title = title, body = body)
            JSONObject().put("posted", title).toString()
        }.getOrElse { failure ->
            toolError("notification_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

/**
 * Posts a notification at a scheduled time through AlarmManager.
 *
 * Exact timing is used when the app can schedule exact alarms; otherwise the alarm is inexact and
 * the result says so, so the model does not promise precision the platform will not deliver. The
 * alarm is persisted, so it survives a reboot: anything whose time passed while the phone was off
 * fires when the phone is back on.
 */
class ScheduleNotificationTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext

    override val definition = ToolDefinition(
        name = "schedule_notification",
        description = "Schedule a notification from Bram to appear at a future time, e.g. a " +
            "reminder. Uses an exact alarm when allowed, otherwise approximate timing.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "title":{"type":"string","description":"Short title."},
               "body":{"type":"string","description":"The message body."},
               "in_minutes":{"type":"integer","description":"Minutes from now to fire. Required."}},
             "required":["title","body","in_minutes"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_NOTIFICATIONS),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext toolError("permission_denied", "POST_NOTIFICATIONS is not granted")
        }
        val minutes = arguments.optInt("in_minutes", 0)
        if (minutes < 1 || minutes > 60 * 24 * 30) {
            return@withContext toolError("invalid_time", "in_minutes must be between 1 and 43200")
        }
        val title = arguments.optString("title").take(120)
        val body = arguments.optString("body").take(500)
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        runCatching {
            val entry = ScheduledNotificationStore.Entry(
                id = UUID.randomUUID().toString(),
                triggerAtEpochMillis = triggerAt,
                title = title,
                body = body,
            )
            ScheduledNotificationStore(appContext).add(entry)
            armScheduledNotification(appContext, entry)
            val exact = canScheduleExact(appContext)
            JSONObject()
                .put("scheduled", title)
                .put("fires_at_epoch_millis", triggerAt)
                .put("exact", exact)
                .put("note", if (exact) "" else "Inexact timing: Android did not allow an exact alarm.")
                .toString()
        }.getOrElse { failure ->
            toolError("schedule_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

/** Fires an alarm set by [ScheduleNotificationTool] and turns it into the notification. */
class ScheduledNotificationReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID)
        if (title.isBlank()) return
        postAgentNotification(context.applicationContext, title = title, body = body)
        // The alarm is one-shot: take the entry off the store so a later reboot does not fire it
        // again. The write is tiny but off the main thread so onReceive returns immediately.
        if (id != null) {
            val pendingResult = goAsync()
            Thread {
                try {
                    runBlocking { ScheduledNotificationStore(context.applicationContext).remove(id) }
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }
}

/**
 * Persists the notifications [ScheduleNotificationTool] arms so they survive a reboot. Android
 * clears alarms on restart, so [rescheduleScheduledNotifications] (called from the boot receiver)
 * reads these back and re-arms each one, firing anything whose time passed while the phone was off.
 */
class ScheduledNotificationStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE)

    data class Entry(
        val id: String,
        val triggerAtEpochMillis: Long,
        val title: String,
        val body: String,
    )

    suspend fun load(): List<Entry> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.toEntry() }
        }.getOrDefault(emptyList())
    }

    suspend fun add(entry: Entry) = withContext(Dispatchers.IO) {
        save(load().filterNot { it.id == entry.id } + entry)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        save(load().filterNot { it.id == id })
    }

    private fun save(entries: List<Entry>) {
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomically(file, JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString())
        }
    }

    private fun Entry.toJson() = JSONObject()
        .put("id", id)
        .put("triggerAtEpochMillis", triggerAtEpochMillis)
        .put("title", title)
        .put("body", body)

    private fun JSONObject.toEntry() = Entry(
        id = optString("id"),
        triggerAtEpochMillis = optLong("triggerAtEpochMillis"),
        title = optString("title"),
        body = optString("body"),
    )

    private fun writeAtomically(target: File, contents: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(contents)
        if (!temporary.renameTo(target)) {
            target.writeText(contents)
            temporary.delete()
        }
    }

    private companion object {
        const val FILE = "scheduled_notifications.json"
    }
}

/** Arms one scheduled notification's alarm, keyed on its stable id so re-arming updates in place. */
private fun armScheduledNotification(context: Context, entry: ScheduledNotificationStore.Entry) {
    val appContext = context.applicationContext
    val alarm = appContext.getSystemService(AlarmManager::class.java)
    val exact = canScheduleExact(appContext)
    val pending = PendingIntent.getBroadcast(
        appContext,
        entry.id.hashCode(),
        Intent(appContext, ScheduledNotificationReceiver::class.java)
            .putExtra(ScheduledNotificationReceiver.EXTRA_ID, entry.id)
            .putExtra(ScheduledNotificationReceiver.EXTRA_TITLE, entry.title)
            .putExtra(ScheduledNotificationReceiver.EXTRA_BODY, entry.body),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    if (exact) {
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAtEpochMillis, pending)
    } else {
        alarm.set(AlarmManager.RTC_WAKEUP, entry.triggerAtEpochMillis, pending)
    }
}

/**
 * Re-arms every persisted scheduled notification after a reboot or app update, firing any whose
 * time passed while the alarms were cleared. Called from the boot receiver alongside the
 * automation and task re-arms.
 */
suspend fun rescheduleScheduledNotifications(context: Context) {
    val appContext = context.applicationContext
    val store = ScheduledNotificationStore(appContext)
    val now = System.currentTimeMillis()
    store.load().forEach { entry ->
        if (entry.triggerAtEpochMillis > now) {
            armScheduledNotification(appContext, entry)
        } else {
            postAgentNotification(appContext, entry.title, entry.body)
            store.remove(entry.id)
        }
    }
}

/** Opens a URI in the app that handles it — the agent handing work to the platform. */
class LaunchUriTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext

    override val definition = ToolDefinition(
        name = "launch_uri",
        description = "Open a URI (http, https, tel, mailto, geo, ...) in whatever app handles it.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "uri":{"type":"string","description":"Absolute URI to open."}},
             "required":["uri"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        approvalScopeKeys = listOf("uri"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val raw = arguments.optString("uri").trim()
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
            ?: return@withContext toolError("invalid_uri", "The URI was not valid")
        if (uri.scheme.isNullOrBlank()) {
            return@withContext toolError("invalid_uri", "The URI needs a scheme")
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            JSONObject().put("opened", uri.toString()).toString()
        }.getOrElse { failure ->
            toolError("launch_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

/** Searches the device contacts by name. Needs READ_CONTACTS, requested through the gate. */
class ContactsTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext

    override val definition = ToolDefinition(
        name = "search_contacts",
        description = "Search the device contacts by name and return matching names and phone " +
            "numbers. Requires the contacts permission.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "query":{"type":"string","description":"Name or part of a name to match."},
               "limit":{"type":"integer","description":"Maximum matches. Default 10."}},
             "required":["query"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_CONTACTS),
        approvalScopeKeys = listOf("query"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val query = arguments.optString("query").trim()
        if (query.isEmpty()) return@withContext toolError("invalid_query", "A query is needed")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext toolError("permission_denied", "READ_CONTACTS is not granted")
        }
        val limit = arguments.optInt("limit", 10).coerceIn(1, 25)
        runCatching {
            val matches = JSONArray()
            val resolver = appContext.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && matches.length() < limit) {
                    val name = cursor.getString(0) ?: ""
                    val number = cursor.getString(1) ?: ""
                    if (name.isNotBlank() || number.isNotBlank()) {
                        matches.put(JSONObject().put("name", name).put("number", number))
                    }
                }
            }
            JSONObject().put("query", query).put("matches", matches).toString()
        }.getOrElse { failure ->
            toolError("contacts_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

/** Reads calendar events in a window. Needs READ_CALENDAR, requested through the gate. */
class CalendarTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext

    override val definition = ToolDefinition(
        name = "read_calendar_events",
        description = "Read calendar events between two times. Times are epoch millis; " +
            "from_epoch_millis and to_epoch_millis are both required.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "from_epoch_millis":{"type":"integer","description":"Window start, epoch millis."},
               "to_epoch_millis":{"type":"integer","description":"Window end, epoch millis."},
               "limit":{"type":"integer","description":"Maximum events. Default 10."}},
             "required":["from_epoch_millis","to_epoch_millis"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_CALENDAR),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext toolError("permission_denied", "READ_CALENDAR is not granted")
        }
        val from = arguments.optLong("from_epoch_millis")
        val to = arguments.optLong("to_epoch_millis")
        if (from <= 0 || to <= from) {
            return@withContext toolError("invalid_window", "A non-empty window is required")
        }
        val limit = arguments.optInt("limit", 10).coerceIn(1, 25)
        runCatching {
            val events = JSONArray()
            val resolver = appContext.contentResolver
            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(from.toString(), to.toString())
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && events.length() < limit) {
                    events.put(
                        JSONObject()
                            .put("title", cursor.getString(0) ?: "")
                            .put("start_epoch_millis", cursor.getLong(1))
                            .put("end_epoch_millis", cursor.getLong(2))
                            .put("all_day", cursor.getInt(3) != 0),
                    )
                }
            }
            JSONObject().put("events", events).toString()
        }.getOrElse { failure ->
            toolError("calendar_failed", failure.message ?: failure::class.java.simpleName)
        }
    }
}

private const val MAX_WRITE_CHARS = 100_000
private const val MAX_READ_CHARS = 64_000
private const val MAX_CLIP_CHARS = 16_000

/** Whether exact alarms can be scheduled; true on API < 31 where the restriction does not exist. */
private fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 31) return true
    val alarm = context.getSystemService(AlarmManager::class.java)
    return alarm.canScheduleExactAlarms()
}

/** Posts a notification on a channel shared by the tool surface, creating it on first use. */
private fun postAgentNotification(context: Context, title: String, body: String) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                this.description = "Notifications posted by the agent at your request."
            },
        )
    }
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    manager.notify(
        title.hashCode(),
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build(),
    )
}

private const val CHANNEL_ID = "bram-agent-tools"
