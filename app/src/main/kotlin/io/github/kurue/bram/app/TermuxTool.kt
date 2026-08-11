package io.github.kurue.bram.app

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Runs a command inside Termux and waits for its result.
 *
 * This is the widest capability Bram has: arbitrary command execution as the user, inside Termux's
 * userland. It works through Termux's `RUN_COMMAND` service — an explicit intent the platform lets
 * Termux receive — so no executable is downloaded or exec'd by Bram itself, which Android forbids
 * for app data. The command runs in Termux's process and its output comes back through a
 * `PendingIntent`, which is also how Bram learns whether Termux refused it at all.
 *
 * Three ordinary failures are reported distinctly rather than as a crash:
 *  - Termux is not installed.
 *  - Bram has not been granted `com.termux.permission.RUN_COMMAND` (asked for through the gate).
 *  - `allow-external-apps` is unset in `~/.termux/termux.properties` (Termux answers with a
 *    permission error, translated here into the fix).
 *
 * Output is capped to what a context window can hold, and the original lengths are reported
 * alongside so the model can tell a truncated result from a complete one — the same rule the
 * M12 design calls for.
 */
class TermuxCommandTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext

    override val definition = ToolDefinition(
        name = "termux_exec",
        // stdout from an arbitrary program. `curl`, `git clone` and `pip install` all end with
        // somebody else'''s text in the conversation, and Bram cannot tell those runs from `ls`.
        returnsUntrustedContent = true,
        description = "Run a command in Termux and return its exit code, stdout, and stderr. " +
            "The command must be an executable already installed in Termux (e.g. \"ls\", " +
            "\"git\", \"python\"). Output is truncated to about 50 KB per stream; the original " +
            "lengths are reported so you can tell a truncated result from a complete one. " +
            "Requires Termux with external-app access enabled.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "command":{"type":"string","description":"The executable to run, e.g. \"git\"."},
               "args":{"type":"array","items":{"type":"string"},
                       "description":"Arguments for the executable, e.g. [\"status\"]."},
               "workdir":{"type":"string",
                          "description":"Working directory inside Termux. Defaults to Termux home."},
               "stdin":{"type":"string","description":"Optional stdin for the command."},
               "timeout_seconds":{"type":"integer",
                                  "description":"How long to wait before giving up. Default 120."}},
             "required":["command"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_TERMUX),
        // An allowance is for one executable, not for whatever a later call happens to name.
        approvalScopeKeys = listOf("command"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val command = arguments.optString("command").trim()
        if (command.isEmpty()) return@withContext toolError("invalid_command", "A command is needed")
        if (command.length > MAX_COMMAND_CHARS) {
            return@withContext toolError("invalid_command", "Command is too long")
        }
        if (!isTermuxInstalled()) {
            return@withContext toolError(
                "termux_not_installed",
                "Termux is not installed. Install it (F-Droid or GitHub releases), open it once, " +
                    "and set allow-external-apps=true in ~/.termux/termux.properties.",
            )
        }
        if (appContext.checkSelfPermission(RuntimePermissions.TERMUX_RUN_COMMAND_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext toolError(
                "termux_permission_denied",
                "Bram does not have the Run commands in Termux permission. Grant it in " +
                    "Android Settings > Apps > Bram > Permissions > Additional permissions.",
            )
        }

        val args = arguments.optJSONArray("args")
            ?.let { array -> (0 until array.length()).map { index -> array.optString(index) }.toTypedArray() }
            .orEmpty()
        val workdir = arguments.optString("workdir").trim().takeIf(String::isNotEmpty)
        val stdin = arguments.optString("stdin").take(MAX_STDIN_CHARS)
        val timeoutSeconds = arguments.optInt("timeout_seconds", 120).coerceIn(5, 600)
        val token = java.util.UUID.randomUUID().toString()

        val result = runCatching {
            val pending = PendingIntent.getService(
                appContext,
                token.hashCode(),
                Intent(appContext, TermuxResultService::class.java)
                    .putExtra(TermuxResultService.EXTRA_TOKEN, token),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
            )
            val intent = Intent(EXTRA_COMMAND_PATH_ACTION).apply {
                component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, resolveExecutable(command))
                putExtra(EXTRA_ARGUMENTS, args)
                workdir?.let { putExtra(EXTRA_WORKDIR, it) }
                // Background execution is the only mode that keeps stdout and stderr separate,
                // and it is what the RUN_COMMAND permission covers.
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_COMMAND_LABEL, command)
                if (stdin.isNotEmpty()) putExtra(EXTRA_STDIN, stdin)
                putExtra(EXTRA_PENDING_INTENT, pending)
            }
            appContext.startService(intent)
        }.getOrElse { failure ->
            return@withContext toolError(
                "termux_blocked",
                "Termux did not accept the command: ${failure.message ?: failure::class.java.simpleName}",
            )
        }

        val expected = CompletableDeferred<TermuxCommandResult>()
        TermuxResultRegistry.pending[token] = expected
        return@withContext try {
            val outcome = withTimeout(timeoutSeconds * 1_000L) { expected.await() }
            outcome.toToolJson(command)
        } catch (_: TimeoutCancellationException) {
            toolError(
                "termux_timeout",
                "The command did not finish within ${timeoutSeconds}s. It may still be running in Termux.",
            )
        } finally {
            // A late result for a timed-out call must not leak into the next call.
            TermuxResultRegistry.pending.remove(token)
        }
    }

    private fun isTermuxInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Resolves a bare executable name to an absolute path under Termux's $PREFIX/bin.
     * `$PREFIX/` and `~/` prefixes in the model's input are expanded like Termux does.
     */
    private fun resolveExecutable(command: String): String = when {
        command.startsWith("~/") -> "$TERMUX_HOME/${command.removePrefix("~/")}"
        command.startsWith("\$PREFIX/") -> "$TERMUX_PREFIX/${command.removePrefix("\$PREFIX/")}"
        command.startsWith('/') -> command
        else -> "$TERMUX_PREFIX/bin/$command"
    }
}

/** What came back from Termux for one command. */
data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val stdoutOriginalLength: Int,
    val stderrOriginalLength: Int,
    val exitCode: Int,
    /** Termux-internal error code, or Activity.RESULT_OK when none. */
    val err: Int,
    val errmsg: String,
) {
    fun toToolJson(command: String): String {
        if (err != android.app.Activity.RESULT_OK) {
            val message = errmsg.take(MAX_ERROR_CHARS)
            val code = when {
                "permission denied" in message.lowercase() -> "termux_not_configured"
                else -> "termux_internal_error"
            }
            val hint = if (code == "termux_not_configured") {
                " Termux refused the command: set allow-external-apps=true in " +
                    "~/.termux/termux.properties and grant Bram the Run commands in Termux " +
                    "permission, then try again."
            } else {
                ""
            }
            return toolError(code, message.trim().ifEmpty { "Termux reported an internal error" } + hint)
        }
        return JSONObject()
            .put("command", command)
            .put("exit_code", exitCode)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_original_length", stdoutOriginalLength)
            .put("stderr_original_length", stderrOriginalLength)
            .put(
                "stdout_truncated",
                stdout.length < stdoutOriginalLength || stdout.length >= MAX_STREAM_CHARS,
            )
            .put(
                "stderr_truncated",
                stderr.length < stderrOriginalLength || stderr.length >= MAX_STREAM_CHARS,
            )
            .toString()
    }
}

/** In-process waiters keyed by command token; the result service resolves them. */
object TermuxResultRegistry {
    val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()
}

/**
 * Receives Termux's result bundle and hands it to the waiting tool call.
 *
 * A bare service rather than an IntentService: each command is a separate start, and all the work
 * is a lookup plus a couple of reads, so a coroutine is more machinery than is needed.
 */
class TermuxResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (token.isNotEmpty()) {
            val bundle = intent?.let(::readResultBundle)
            val result = TermuxCommandResult(
                stdout = bundle?.getString(KEY_STDOUT) ?: "",
                stderr = bundle?.getString(KEY_STDERR) ?: "",
                stdoutOriginalLength = bundle?.getInt(KEY_STDOUT_ORIGINAL_LENGTH, 0) ?: 0,
                stderrOriginalLength = bundle?.getInt(KEY_STDERR_ORIGINAL_LENGTH, 0) ?: 0,
                exitCode = bundle?.getInt(KEY_EXIT_CODE, 0) ?: 0,
                err = bundle?.getInt(KEY_ERR, android.app.Activity.RESULT_OK)
                    ?: android.app.Activity.RESULT_OK,
                errmsg = bundle?.getString(KEY_ERRMSG) ?: "",
            )
            TermuxResultRegistry.pending.remove(token)?.complete(result)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * The bundle key moved between Termux versions. Modern Termux delivers the result bundle under
     * `result`; older versions used the long `com.termux.RUN_COMMAND_RESULT_BUNDLE` key. Reading
     * both costs one lookup and keeps both working.
     */
    private fun readResultBundle(intent: Intent): Bundle? =
        intent.getBundleExtra(KEY_RESULT_BUNDLE) ?: intent.getBundleExtra(LEGACY_KEY_RESULT_BUNDLE)

    companion object {
        const val EXTRA_TOKEN = "bram.termux.token"
    }
}

// Termux's own constants, spelled out here rather than hardcoded at each use. The values are the
// string literals TermuxConstants.java publishes for the RUN_COMMAND intent and its result bundle.
private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

// RUN_COMMAND intent extras (com.termux.RUN_COMMAND_*).
private const val EXTRA_COMMAND_PATH_ACTION = "com.termux.RUN_COMMAND"
private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

// Result bundle keys (the bundle delivered with the PendingIntent).
private const val KEY_RESULT_BUNDLE = "result"
private const val LEGACY_KEY_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT_BUNDLE"
private const val KEY_STDOUT = "stdout"
private const val KEY_STDERR = "stderr"
private const val KEY_EXIT_CODE = "exitCode"
private const val KEY_ERR = "err"
private const val KEY_ERRMSG = "errmsg"
private const val KEY_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
private const val KEY_STDERR_ORIGINAL_LENGTH = "stderr_original_length"

private const val MAX_STREAM_CHARS = 50_000
private const val MAX_STDIN_CHARS = 100_000
private const val MAX_COMMAND_CHARS = 8_000
private const val MAX_ERROR_CHARS = 2_000
