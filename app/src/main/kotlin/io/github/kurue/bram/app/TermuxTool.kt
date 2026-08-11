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

    /**
     * The conversation whose turn is running, so [execute] can keep a per-conversation working
     * directory for `shell` calls. Null during scheduled tasks (unattended work stays stateless)
     * and until the ViewModel binds one at the start of a chat turn. Runs are serial, so a single
     * mutable token is enough.
     */
    @Volatile
    private var sessionToken: String? = null

    fun setSession(token: String?) {
        sessionToken = token
    }

    override val definition = ToolDefinition(
        name = "termux_exec",
        // stdout from an arbitrary program. `curl`, `git clone` and `pip install` all end with
        // somebody else'''s text in the conversation, and Bram cannot tell those runs from `ls`.
        returnsUntrustedContent = true,
        description = "Run a command in Termux and return its exit code, stdout, and stderr. " +
            "Prefer `shell` (a raw bash command line) for anything that chains, pipes, globs, or " +
            "uses builtins like cd/export: it runs under bash so `cd proj && make && git status` " +
            "works in one call, and within a conversation the working directory persists across " +
            "shell calls (cd into a project, then call again with just `git status`). Use " +
            "`command`+`args` for a single executable whose arguments must be passed verbatim " +
            "(no shell expansion); that path is stateless. Output is truncated to about 50 KB " +
            "per stream with the original lengths reported. Requires Termux with external-app " +
            "access enabled.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "shell":{"type":"string",
                        "description":"A raw bash command line: pipes (|), chains (&&, ;), globs (*), redirects, and builtins (cd, export, source) all work. When set, command/args are ignored. In a chat turn the working directory persists across shell calls."},
               "command":{"type":"string","description":"The executable to run, e.g. \"git\". Ignored when `shell` is set."},
               "args":{"type":"array","items":{"type":"string"},
                       "description":"Arguments for the executable, e.g. [\"status\"]. Ignored when `shell` is set."},
               "workdir":{"type":"string",
                          "description":"Working directory inside Termux. For `command` calls this is the start directory; for `shell` calls it overrides the remembered cwd and becomes the new one. Defaults to Termux home."},
               "stdin":{"type":"string","description":"Optional stdin for the command."},
               "timeout_seconds":{"type":"integer",
                                  "description":"How long to wait before giving up. Default 120."}},
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
        val shellLine = arguments.optString("shell").trim()
        val command = arguments.optString("command").trim()
        if (shellLine.isEmpty() && command.isEmpty()) {
            return@withContext toolError("invalid_command", "Provide `shell` or `command`.")
        }
        if (shellLine.length > MAX_COMMAND_CHARS || command.length > MAX_COMMAND_CHARS) {
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

        // The wrapped `shell` path runs under bash so pipes, chains, globs, and builtins work, and
        // (in a chat turn) restores/saves the working directory so `cd` survives across calls. The
        // direct `command` path execs the named binary with verbatim args and is stateless.
        val (executablePath, execArgs) = if (shellLine.isNotEmpty()) {
            buildShellInvocation(shellLine, workdir)
        } else {
            resolveExecutable(command) to args
        }

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
                putExtra(EXTRA_COMMAND_PATH, executablePath)
                putExtra(EXTRA_ARGUMENTS, execArgs)
                // The wrapped shell path manages cwd itself (restore + save inside the snippet), so
                // it does not set the workdir extra; only the direct exec path uses Termux's start
                // directory.
                if (shellLine.isEmpty()) workdir?.let { putExtra(EXTRA_WORKDIR, it) }
                // Background execution is the only mode that keeps stdout and stderr separate,
                // and it is what the RUN_COMMAND permission covers.
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_COMMAND_LABEL, if (shellLine.isNotEmpty()) "bash -c" else command)
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
            outcome.toToolJson(if (shellLine.isNotEmpty()) "bash -c" else command)
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

    /**
     * Builds the `bash -c` invocation for a `shell` call: restores the conversation's last working
     * directory (or cds into an explicit override), runs the model's command line, and saves the
     * resulting directory back. Returns the executable path and the argument array for the
     * RUN_COMMAND intent.
     */
    private fun buildShellInvocation(shellLine: String, workdir: String?): Pair<String, Array<String>> {
        val statePath = sessionToken
            ?.takeIf { SESSION_TOKEN.matches(it) }
            ?.let { id -> "$BRAM_SESSION_DIR/session-$id.cwd" }
        val snippet = buildString {
            if (statePath != null) {
                append("mkdir -p ")
                append(shellQuote(BRAM_SESSION_DIR))
                append(" 2>/dev/null; ")
                if (workdir != null) {
                    append("cd ")
                    append(shellQuote(workdir))
                } else {
                    append("cd \"\$(cat ")
                    append(shellQuote(statePath))
                    append(" 2>/dev/null)\" 2>/dev/null")
                }
                append("; ")
                append(shellLine)
                append("; __B=\$?; pwd > ")
                append(shellQuote(statePath))
                append(" 2>/dev/null; exit \$__B")
            } else {
                if (workdir != null) {
                    append("cd ")
                    append(shellQuote(workdir))
                    append("; ")
                }
                append(shellLine)
            }
        }
        return resolveExecutable("bash") to arrayOf("-c", snippet)
    }

    /** Single-quotes a path so it is a literal inside a bash snippet, even with spaces or $. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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

// Where per-conversation shell session state (the last working directory) lives. Inside Termux's
// home, so Termux's own uid reads and writes it; Bram only hands it the conversation token.
private const val BRAM_SESSION_DIR = "$TERMUX_HOME/.bram"
private val SESSION_TOKEN = Regex("\\A[A-Za-z0-9_-]+\\Z")

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
