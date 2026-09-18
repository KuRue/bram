package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import org.json.JSONArray
import org.json.JSONObject

/** One control or piece of text on screen, in the transport shape a tool result can carry. */
data class ScreenNode(
    val depth: Int,
    val text: String,
    val description: String,
    val viewId: String,
    val className: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
)

/** The active window as read through the accessibility service. */
data class ScreenSnapshot(val packageName: String, val nodes: List<ScreenNode>)

/**
 * Reads the active window, behind an interface so the tool is testable without a device.
 *
 * Returns null when there is nothing to read — the screen is off or locked, or the foreground
 * window is one the system marks secure and never exposes.
 */
fun interface ScreenReader {
    suspend fun snapshot(): ScreenSnapshot?
}

/** What screen reading can do right now, so the tool can say which fix applies. */
sealed interface ScreenAccess {
    /** The service is on and connected. */
    data class Ready(val reader: ScreenReader) : ScreenAccess

    /** The user has not enabled screen reading in system accessibility settings. */
    data object Disabled : ScreenAccess

    /** Enabled in settings, but the service has not connected yet — starting, or recently killed. */
    data object NotConnected : ScreenAccess
}

/**
 * Reads the current screen: text, labeled controls, and their positions.
 *
 * The consent is the system accessibility toggle, not a runtime permission and not the approval
 * card: the user turns the service on in Settings, and the tool answers with instructions when it
 * is off. Reading is read-only, so it runs silently like the other read-only tools wherever the
 * mode allows reading at all — but its result carries content authored by other apps, so it is
 * marked untrusted and a recovered call that follows it is treated with the same suspicion as one
 * following a fetched page.
 */
class ReadScreenTool(
    private val access: suspend () -> ScreenAccess,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "read_screen",
        description = "Read the current screen: the app, and its text and labeled controls with " +
            "positions. Needs screen reading enabled in the accessibility settings.",
        inputSchemaJson = """
            {"type":"object","properties":{},"additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
        returnsUntrustedContent = true,
        timeoutMillis = 30_000,
    )

    override suspend fun execute(argumentsJson: String): String = when (val current = access()) {
        ScreenAccess.Disabled -> toolError(
            "accessibility_off",
            "Screen reading is off. The user turns it on in Android Settings > Accessibility > " +
                "Installed apps > Screen reading for Bram; continue without it.",
        )
        ScreenAccess.NotConnected -> toolError(
            "accessibility_starting",
            "Screen reading is enabled but the service has not connected yet. Try again in a moment.",
        )
        is ScreenAccess.Ready -> when (val snapshot = current.reader.snapshot()) {
            null -> toolError(
                "screen_unavailable",
                "There is no screen to read right now - it may be off or locked, or the app in " +
                    "front blocks accessibility.",
            )
            else -> compactScreen(snapshot).toString()
        }
    }
}

/**
 * Projects the walked hierarchy into the result JSON.
 *
 * Only content and controls are kept: a node with no text, label, or interaction is layout, and
 * sending layout to a model spends context on nothing. Depth is kept so indentation survives, and
 * positions are kept so a later interaction slice can aim at what this listed.
 */
internal fun compactScreen(
    snapshot: ScreenSnapshot,
    maxNodes: Int = MAX_SCREEN_NODES,
    maxTextChars: Int = MAX_SCREEN_TEXT_CHARS,
): JSONObject {
    val interesting = snapshot.nodes.filter { node ->
        node.text.isNotBlank() || node.description.isNotBlank() ||
            node.clickable || node.scrollable || node.editable
    }
    val emitted = interesting.take(maxNodes)
    val nodes = JSONArray()
    emitted.forEachIndexed { index, node ->
        nodes.put(
            JSONObject().apply {
                put("i", index)
                put("depth", node.depth)
                put("text", node.text.compact(maxTextChars))
                if (node.description.isNotBlank()) put("desc", node.description.compact(maxTextChars))
                if (node.viewId.isNotBlank()) put("id", node.viewId)
                if (node.className.isNotBlank()) put("class", node.className)
                put("bounds", JSONArray(listOf(node.left, node.top, node.right, node.bottom)))
                if (node.clickable) put("clickable", true)
                if (node.scrollable) put("scrollable", true)
                if (node.editable) put("editable", true)
            },
        )
    }
    return JSONObject()
        .put("app", snapshot.packageName)
        .put("nodes", nodes)
        .put("count", emitted.size)
        .put("total", interesting.size)
        .put("truncated", interesting.size > emitted.size)
}

/** Collapses the whitespace screen text is full of, then caps it to what one row can carry. */
private fun String.compact(maxChars: Int): String {
    val collapsed = trim().replace(WHITESPACE_RUNS, " ")
    return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars) + "…"
}

private val WHITESPACE_RUNS = Regex("\\s+")

private const val MAX_SCREEN_NODES = 80
private const val MAX_SCREEN_TEXT_CHARS = 160
