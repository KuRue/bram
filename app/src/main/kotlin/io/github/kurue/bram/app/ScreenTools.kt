package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import org.json.JSONArray
import org.json.JSONObject

/** One control or piece of text on screen, in the transport shape a tool result can carry. */
data class ScreenNode(
    /** Position in the tree walk: what an action addresses, never shown to the model. */
    val walkIndex: Int,
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
) {
    /** Whether two snapshots are talking about the same control, ignoring where it sat in a walk. */
    fun sameControl(other: ScreenNode): Boolean =
        viewId == other.viewId && text == other.text && description == other.description &&
            left == other.left && top == other.top && right == other.right && bottom == other.bottom

    /** How the control is named back to the model and shown on an approval card. */
    fun label(): String = when {
        text.isNotBlank() -> text
        description.isNotBlank() -> description
        viewId.isNotBlank() -> viewId
        else -> "the control at ${left},${top}"
    }
}

/** The active window as read through the accessibility service. */
data class ScreenSnapshot(val packageName: String, val nodes: List<ScreenNode>)

/** What the model named: text or label, view id, an index from the last read_screen, or a point. */
sealed interface ScreenTarget {
    data class Text(val value: String) : ScreenTarget
    data class Id(val value: String) : ScreenTarget
    data class Index(val value: Int) : ScreenTarget
    data class Point(val x: Int, val y: Int) : ScreenTarget
}

enum class ScrollDirection { UP, DOWN }

/** The outcome of an action, in terms the tools can turn into an answer for the model. */
sealed interface ScreenActionResult {
    data class Done(val label: String, val how: String) : ScreenActionResult
    data object NotFound : ScreenActionResult
    data object NotInteractive : ScreenActionResult
    data object StaleSnapshot : ScreenActionResult
    data object NoWindow : ScreenActionResult
    data class Failed(val message: String) : ScreenActionResult
}

/**
 * Reading and acting on the screen, behind an interface so the tools are testable without a device.
 *
 * One interface for both because they share the same live window and the same consent: an action
 * can only be aimed at what a read just saw, and the same session answers whether either is
 * possible at all.
 */
interface ScreenSession {
    suspend fun snapshot(): ScreenSnapshot?
    suspend fun tap(target: ScreenTarget): ScreenActionResult
    suspend fun typeText(text: String, target: ScreenTarget?): ScreenActionResult
    suspend fun scroll(direction: ScrollDirection, target: ScreenTarget?): ScreenActionResult
}

/** What screen reading can do right now, so the tools can say which fix applies. */
sealed interface ScreenAccess {
    /** The service is on and connected. */
    data class Ready(val session: ScreenSession) : ScreenAccess

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
        ScreenAccess.Disabled -> screenOffError()
        ScreenAccess.NotConnected -> screenStartingError()
        is ScreenAccess.Ready -> when (val snapshot = current.session.snapshot()) {
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
 * Taps a control on the current screen.
 *
 * Side-effecting, so the approval gate asks whenever the mode asks about changes; the target is
 * the scope, not the tool, so an "always" grant vouches for one control rather than for tapping
 * whatever a later turn dreams up. A tap that lands on a screenful of untrusted text is exactly
 * the place a page-suggested call would point, which is what the untrusted-content marking and
 * the visible target on the approval card are for.
 */
class TapTool(
    private val access: suspend () -> ScreenAccess,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "tap",
        description = "Tap a control on the current screen. Name it by its text or label, view " +
            "id, an index from read_screen, or a point (x, y). Returns what was tapped.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "text":{"type":"string","description":"The control's text or accessibility label, e.g. \"Send\". Case-insensitive."},
               "id":{"type":"string","description":"The control's view id, e.g. btn_send."},
               "index":{"type":"integer","description":"An index from the last read_screen result."},
               "x":{"type":"integer","description":"Screen x of a point to tap; needs y too."},
               "y":{"type":"integer","description":"Screen y of a point to tap; needs x too."}},
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        approvalScopeKeys = listOf("text", "id"),
        timeoutMillis = 30_000,
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        val target = arguments.screenTarget()
            ?: return toolError("invalid_arguments", "Name the control with text, id, or index")
        return when (val current = access()) {
            ScreenAccess.Disabled -> screenOffError()
            ScreenAccess.NotConnected -> screenStartingError()
            is ScreenAccess.Ready -> when (val result = current.session.tap(target)) {
                is ScreenActionResult.Done -> JSONObject()
                    .put("tapped", true)
                    .put("target", result.label)
                    .put("how", result.how)
                    .toString()
                ScreenActionResult.NotFound -> toolError(
                    "not_found",
                    "No control on the current screen matches ${target.describe()}. Call read_screen " +
                        "to see what is there.",
                )
                ScreenActionResult.NotInteractive -> toolError(
                    "not_interactive",
                    "${target.describe()} is not something that can be tapped. Call read_screen again " +
                        "and pick a control marked clickable.",
                )
                ScreenActionResult.StaleSnapshot -> staleScreenError()
                ScreenActionResult.NoWindow -> noWindowError()
                is ScreenActionResult.Failed -> toolError("action_failed", result.message)
            }
        }
    }
}

/**
 * Types into a field on the current screen.
 *
 * The content is the approval scope: "always allow type_text with \"hello\"" vouches for that
 * text, not for typing anything into anything later. Without a target it types into the focused
 * field, which is where a preceding tap left focus.
 */
class TypeTextTool(
    private val access: suspend () -> ScreenAccess,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "type_text",
        description = "Type text into the focused field, or into the field named by view id or an " +
            "index from read_screen.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "text":{"type":"string","description":"What to type. Replaces the field's contents."},
               "id":{"type":"string","description":"The field's view id, e.g. search_input."},
               "index":{"type":"integer","description":"An index from the last read_screen result."}},
             "required":["text"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        approvalScopeKeys = listOf("text"),
        timeoutMillis = 30_000,
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        if (!arguments.has("text")) return toolError("invalid_arguments", "Missing required argument \"text\"")
        val text = arguments.optString("text")
        val target = arguments.fieldTarget()
        return when (val current = access()) {
            ScreenAccess.Disabled -> screenOffError()
            ScreenAccess.NotConnected -> screenStartingError()
            is ScreenAccess.Ready -> when (val result = current.session.typeText(text, target)) {
                is ScreenActionResult.Done -> JSONObject()
                    .put("typed", true)
                    .put("field", result.label)
                    .put("how", result.how)
                    .toString()
                ScreenActionResult.NotFound -> toolError(
                    "not_found",
                    if (target == null) {
                        "No field has focus. Tap the field first, or name it by id or index."
                    } else {
                        "No field on the current screen matches ${target.describe()}. Call read_screen " +
                            "to see what is there."
                    },
                )
                ScreenActionResult.NotInteractive -> toolError(
                    "not_editable",
                    "The target is not a text field. Call read_screen again and pick a control " +
                        "marked editable.",
                )
                ScreenActionResult.StaleSnapshot -> staleScreenError()
                ScreenActionResult.NoWindow -> noWindowError()
                is ScreenActionResult.Failed -> toolError("action_failed", result.message)
            }
        }
    }
}

/**
 * Scrolls the screen or one scrollable area.
 *
 * Low-harm on its own, but still a change to what the user is looking at, so it is side-effecting
 * and asks where the mode asks about changes. The scope is the direction, since that is what the
 * grant means ("always allow scroll for down").
 */
class ScrollTool(
    private val access: suspend () -> ScreenAccess,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "scroll",
        description = "Scroll the current screen or a scrollable area up or down. Returns what was " +
            "scrolled.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "direction":{"type":"string","description":"\"down\" or \"up\"."},
               "id":{"type":"string","description":"The scrollable area's view id; omit for the first one on screen."},
               "index":{"type":"integer","description":"An index from the last read_screen result."}},
             "required":["direction"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        approvalScopeKeys = listOf("direction"),
        timeoutMillis = 30_000,
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        val direction = when (arguments.optString("direction").trim().lowercase()) {
            "down" -> ScrollDirection.DOWN
            "up" -> ScrollDirection.UP
            else -> return toolError("invalid_arguments", "direction must be \"up\" or \"down\"")
        }
        val target = arguments.fieldTarget()
        return when (val current = access()) {
            ScreenAccess.Disabled -> screenOffError()
            ScreenAccess.NotConnected -> screenStartingError()
            is ScreenAccess.Ready -> when (val result = current.session.scroll(direction, target)) {
                is ScreenActionResult.Done -> JSONObject()
                    .put("scrolled", direction.name.lowercase())
                    .put("area", result.label)
                    .put("how", result.how)
                    .toString()
                ScreenActionResult.NotFound -> toolError(
                    "not_found",
                    if (target == null) {
                        "Nothing on the current screen can scroll. Call read_screen to see what is there."
                    } else {
                        "No scrollable area matches ${target.describe()}. Call read_screen to see what " +
                            "is there."
                    },
                )
                ScreenActionResult.NotInteractive -> toolError(
                    "not_scrollable",
                    "The target cannot scroll. Call read_screen again and pick a control marked scrollable.",
                )
                ScreenActionResult.StaleSnapshot -> staleScreenError()
                ScreenActionResult.NoWindow -> noWindowError()
                is ScreenActionResult.Failed -> toolError("action_failed", result.message)
            }
        }
    }
}

/**
 * Projects the walked hierarchy into the result JSON.
 *
 * Only content and controls are kept: a node with no text, label, or interaction is layout, and
 * sending layout to a model spends context on nothing. Depth is kept so indentation survives, and
 * positions are kept so interaction can aim at what this listed.
 */
internal fun compactScreen(
    snapshot: ScreenSnapshot,
    maxNodes: Int = MAX_SCREEN_NODES,
    maxTextChars: Int = MAX_SCREEN_TEXT_CHARS,
): JSONObject {
    val interesting = interestingNodes(snapshot)
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

/** The nodes worth showing or acting on: content and controls, not layout. */
internal fun interestingNodes(snapshot: ScreenSnapshot): List<ScreenNode> =
    snapshot.nodes.filter { node ->
        node.text.isNotBlank() || node.description.isNotBlank() ||
            node.clickable || node.scrollable || node.editable
    }

/**
 * Finds the control a target names in a list of nodes.
 *
 * Text and label matching prefers an exact match before a substring one, so "Send" does not land
 * on "Send feedback" when both are present. Ids match the whole resource name or its final
 * segment, since models see either. Index is the list read_screen returned, which the caller keeps
 * and staleness-checks.
 */
internal fun resolveScreenTarget(nodes: List<ScreenNode>, target: ScreenTarget): ScreenNode? = when (target) {
    is ScreenTarget.Index -> nodes.getOrNull(target.value)
    is ScreenTarget.Point -> {
        val containing = nodes.filter {
            target.x in it.left until it.right && target.y in it.top until it.bottom
        }
        // The deepest control containing the point is the one under the finger; a clickable one
        // wins over its container, which is what makes an unlabeled icon button reachable.
        containing.filter(ScreenNode::clickable).maxByOrNull(ScreenNode::depth)
            ?: containing.maxByOrNull(ScreenNode::depth)
    }
    is ScreenTarget.Text -> {
        val wanted = target.value.trim()
        nodes.firstOrNull {
            it.text.trim().equals(wanted, ignoreCase = true) ||
                it.description.trim().equals(wanted, ignoreCase = true)
        } ?: nodes.firstOrNull {
            it.text.contains(wanted, ignoreCase = true) || it.description.contains(wanted, ignoreCase = true)
        }
    }
    is ScreenTarget.Id -> {
        val wanted = target.value.trim()
        nodes.firstOrNull {
            it.viewId == wanted || it.viewId.endsWith("/$wanted") || it.viewId.endsWith(":id/$wanted")
        }
    }
}

/** The target the arguments name, or null when they name none. */
internal fun JSONObject.screenTarget(): ScreenTarget? {
    optString("text").trim().takeIf(String::isNotEmpty)?.let { return ScreenTarget.Text(it) }
    optString("id").trim().takeIf(String::isNotEmpty)?.let { return ScreenTarget.Id(it) }
    fieldTarget()?.let { return it }
    if (has("x") && has("y") && !isNull("x") && !isNull("y")) {
        return ScreenTarget.Point(optInt("x"), optInt("y"))
    }
    return null
}

/** The target a field/area argument names: id or index only, since `text` is the content. */
internal fun JSONObject.fieldTarget(): ScreenTarget? {
    optString("id").trim().takeIf(String::isNotEmpty)?.let { return ScreenTarget.Id(it) }
    if (has("index") && !isNull("index")) {
        val index = optInt("index", -1)
        if (index >= 0) return ScreenTarget.Index(index)
    }
    return null
}

internal fun ScreenTarget.describe(): String = when (this) {
    is ScreenTarget.Text -> "\"$value\""
    is ScreenTarget.Id -> "id $value"
    is ScreenTarget.Index -> "index $value"
    is ScreenTarget.Point -> "the point ($x, $y)"
}

internal fun screenOffError(): String = toolError(
    "accessibility_off",
    "Screen reading is off. The user turns it on in Android Settings > Accessibility > " +
        "Installed apps > Screen reading for Bram; continue without it.",
)

internal fun screenStartingError(): String = toolError(
    "accessibility_starting",
    "Screen reading is enabled but the service has not connected yet. Try again in a moment.",
)

private fun staleScreenError(): String = toolError(
    "stale_snapshot",
    "The screen changed since read_screen. Call read_screen again, then use the new index or target.",
)

private fun noWindowError(): String = toolError(
    "screen_unavailable",
    "There is no screen to act on right now - it may be off or locked, or the app in front " +
        "blocks accessibility.",
)

/** Collapses the whitespace screen text is full of, then caps it to what one row can carry. */
private fun String.compact(maxChars: Int): String {
    val collapsed = trim().replace(WHITESPACE_RUNS, " ")
    return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars) + "…"
}

private val WHITESPACE_RUNS = Regex("\\s+")

private const val MAX_SCREEN_NODES = 80
private const val MAX_SCREEN_TEXT_CHARS = 160
