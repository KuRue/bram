package io.github.kurue.bram.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The system-bound side of screen reading and interaction.
 *
 * Deliberately inert: Bram does not react to accessibility events, so the service does no work on
 * its own. It exists so [rootInActiveWindow] has an owner, and everything it reads or taps is
 * pulled on demand by the tools and nothing is stored beyond what one call needs.
 */
class BramAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        AccessibilityBridge.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        const val MAX_WALK_NODES = 600
    }
}

/** A walk's transport data plus the live nodes it came from, aligned by `walkIndex`. */
internal class ScreenTreeWalk(
    val snapshot: ScreenSnapshot,
    val nodes: List<AccessibilityNodeInfo>,
)

/**
 * Turns an [AccessibilityNodeInfo] tree into transport-safe nodes.
 *
 * Separate from the service so the walk can be exercised against a real window from a device test
 * (through the instrumentation's own accessibility automation), where binding a second service
 * during a run is not possible.
 */
object ScreenTreeReader {
    fun walk(root: AccessibilityNodeInfo?): ScreenSnapshot? = walkWithNodes(root)?.snapshot

    internal fun walkWithNodes(root: AccessibilityNodeInfo?): ScreenTreeWalk? {
        if (root == null) return null
        val data = mutableListOf<ScreenNode>()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty() && data.size < MAX_WALK_NODES) {
            val (node, depth) = pending.removeLast()
            data += node.toScreenNode(walkIndex = data.size, depth = depth)
            nodes += node
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let { child -> pending.addLast(child to depth + 1) }
            }
        }
        return ScreenTreeWalk(ScreenSnapshot(root.packageName?.toString().orEmpty(), data), nodes)
    }

    private fun AccessibilityNodeInfo.toScreenNode(walkIndex: Int, depth: Int): ScreenNode {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return ScreenNode(
            walkIndex = walkIndex,
            depth = depth,
            text = text?.toString().orEmpty(),
            description = contentDescription?.toString().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            className = className?.toString()?.substringAfterLast('.').orEmpty(),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            clickable = isClickable,
            scrollable = isScrollable,
            editable = isEditable,
        )
    }

    private const val MAX_WALK_NODES = 600
}

/**
 * The seam between the service and the tools.
 *
 * "Enabled" and "connected" are different: the setting says the user consented, and the instance
 * says the system has bound the service in this process. A tool call can land in the gap (the
 * process was just killed and is being restarted), so [access] waits briefly for the rebind before
 * answering, and reports the two states separately when it gives up.
 */
object AccessibilityBridge {
    @Volatile
    private var service: BramAccessibilityService? = null

    /**
     * The nodes read_screen last returned. An index target is only meaningful against what the
     * model was shown, so the list is kept until the next read or until the service goes away; a
     * stale index is detected by matching the remembered control against the live tree.
     */
    @Volatile
    private var lastTargets: List<ScreenNode> = emptyList()

    internal fun attach(value: BramAccessibilityService) {
        service = value
    }

    internal fun detach(value: BramAccessibilityService) {
        if (service === value) {
            service = null
            lastTargets = emptyList()
        }
    }

    internal fun rememberTargets(nodes: List<ScreenNode>) {
        lastTargets = nodes
    }

    internal fun targets(): List<ScreenNode> = lastTargets

    /**
     * Whether the user has the service enabled in system accessibility settings.
     *
     * Read from the secure settings rather than from
     * [AccessibilityManager.getEnabledAccessibilityServiceList]: that list only carries services
     * the system has bound, so it reads as "disabled" in the window between the user turning the
     * service on and the system binding it, and in a process that was force-stopped (an
     * instrumentation run). The setting is the consent itself, so it is what "enabled" means here;
     * whether the service is connected is [access]'s separate question.
     */
    fun enabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val className = BramAccessibilityService::class.java.name
        val shortName = ".${BramAccessibilityService::class.java.simpleName}"
        return enabled.split(':').any { entry ->
            if ('/' !in entry) return@any false
            val packageName = entry.substringBefore('/').trim()
            val componentClass = entry.substringAfter('/').trim()
            packageName == context.packageName && (componentClass == className || componentClass == shortName)
        }
    }

    /** What screen access can do right now, waiting briefly for a reconnect when one is coming. */
    suspend fun access(context: Context): ScreenAccess {
        if (!enabled(context)) return ScreenAccess.Disabled
        var connected = service
        if (connected == null) {
            withTimeoutOrNull(CONNECT_WAIT_MILLIS) {
                while (connected == null) {
                    delay(CONNECT_POLL_MILLIS)
                    connected = service
                }
            }
        }
        val current = connected ?: return ScreenAccess.NotConnected
        return ScreenAccess.Ready(ServiceScreenSession(current))
    }

    private const val CONNECT_WAIT_MILLIS = 2_000L
    private const val CONNECT_POLL_MILLIS = 100L
}

/**
 * The live session: reads walk the active window, actions resolve a target against the tree they
 * are about to act on. Everything runs on the main thread, which is where accessibility node
 * access belongs.
 */
private class ServiceScreenSession(private val service: BramAccessibilityService) : ScreenSession {

    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.Main.immediate) {
        val walk = runCatching { ScreenTreeReader.walkWithNodes(service.rootInActiveWindow) }.getOrNull()
            ?: return@withContext null
        AccessibilityBridge.rememberTargets(interestingNodes(walk.snapshot))
        walk.snapshot
    }

    override suspend fun tap(target: ScreenTarget): ScreenActionResult =
        withContext(Dispatchers.Main.immediate) {
            val resolved = when (val outcome = resolveLive(target)) {
                is Resolved.Found -> outcome
                Resolved.NotFound -> return@withContext ScreenActionResult.NotFound
                Resolved.Stale -> return@withContext ScreenActionResult.StaleSnapshot
                Resolved.NoWindow -> return@withContext ScreenActionResult.NoWindow
            }
            val node = resolved.node
            if (node.isClickable) {
                return@withContext click(node, resolved.data)
            }
            // The label often sits on a child of the clickable row, so a tap that lands on text
            // clicks the nearest clickable ancestor instead of giving up.
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return@withContext click(parent, resolved.data)
                parent = parent.parent
            }
            ScreenActionResult.NotInteractive
        }

    override suspend fun typeText(text: String, target: ScreenTarget?): ScreenActionResult =
        withContext(Dispatchers.Main.immediate) {
            val node: AccessibilityNodeInfo
            val label: String
            if (target == null) {
                val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return@withContext ScreenActionResult.NotFound
                node = focused
                label = focused.describe()
            } else {
                when (val outcome = resolveLive(target)) {
                    is Resolved.Found -> {
                        node = outcome.node
                        label = outcome.data.label()
                    }
                    Resolved.NotFound -> return@withContext ScreenActionResult.NotFound
                    Resolved.Stale -> return@withContext ScreenActionResult.StaleSnapshot
                    Resolved.NoWindow -> return@withContext ScreenActionResult.NoWindow
                }
            }
            if (!node.isEditable) return@withContext ScreenActionResult.NotInteractive
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                ScreenActionResult.Done(label, "set-text")
            } else {
                ScreenActionResult.Failed("The field refused the text.")
            }
        }

    override suspend fun scroll(direction: ScrollDirection, target: ScreenTarget?): ScreenActionResult =
        withContext(Dispatchers.Main.immediate) {
            val walk = runCatching { ScreenTreeReader.walkWithNodes(service.rootInActiveWindow) }
                .getOrNull() ?: return@withContext ScreenActionResult.NoWindow
            val interesting = interestingNodes(walk.snapshot)
            val data = if (target == null) {
                interesting.firstOrNull(ScreenNode::scrollable)
                    ?: return@withContext ScreenActionResult.NotFound
            } else {
                when (val outcome = resolveIn(walk, interesting, target)) {
                    is Resolved.Found -> outcome.data
                    Resolved.NotFound -> return@withContext ScreenActionResult.NotFound
                    Resolved.Stale -> return@withContext ScreenActionResult.StaleSnapshot
                    Resolved.NoWindow -> return@withContext ScreenActionResult.NoWindow
                }
            }
            var node = walk.nodes.getOrNull(data.walkIndex)
                ?: return@withContext ScreenActionResult.StaleSnapshot
            while (!node.isScrollable) {
                node = node.parent ?: return@withContext ScreenActionResult.NotInteractive
            }
            val action = if (direction == ScrollDirection.DOWN) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.performAction(action)) {
                ScreenActionResult.Done(data.label(), direction.name.lowercase())
            } else {
                ScreenActionResult.Failed("The screen refused the scroll.")
            }
        }

    private fun click(node: AccessibilityNodeInfo, data: ScreenNode): ScreenActionResult =
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            ScreenActionResult.Done(data.label(), "click")
        } else {
            ScreenActionResult.Failed("The system refused the tap.")
        }

    private fun resolveLive(target: ScreenTarget): Resolved {
        val walk = runCatching { ScreenTreeReader.walkWithNodes(service.rootInActiveWindow) }
            .getOrNull() ?: return Resolved.NoWindow
        return resolveIn(walk, interestingNodes(walk.snapshot), target)
    }

    /**
     * Resolves a target against the tree about to be acted on. An index is looked up in the list
     * read_screen returned and then matched back into the live tree by identity of the control;
     * if the control is gone or changed, the index is stale rather than wrong.
     */
    private fun resolveIn(
        walk: ScreenTreeWalk,
        interesting: List<ScreenNode>,
        target: ScreenTarget,
    ): Resolved {
        val data = when (target) {
            is ScreenTarget.Index -> {
                val wanted = AccessibilityBridge.targets().getOrNull(target.value)
                    ?: return Resolved.NotFound
                interesting.firstOrNull { it.sameControl(wanted) } ?: return Resolved.Stale
            }
            else -> resolveScreenTarget(interesting, target) ?: return Resolved.NotFound
        }
        val node = walk.nodes.getOrNull(data.walkIndex) ?: return Resolved.Stale
        return Resolved.Found(node, data)
    }

    private sealed interface Resolved {
        data class Found(val node: AccessibilityNodeInfo, val data: ScreenNode) : Resolved
        data object NotFound : Resolved
        data object Stale : Resolved
        data object NoWindow : Resolved
    }

    private fun AccessibilityNodeInfo.describe(): String {
        val label = text?.toString()?.takeIf(String::isNotBlank)
            ?: contentDescription?.toString()?.takeIf(String::isNotBlank)
            ?: viewIdResourceName?.takeIf(String::isNotBlank)
        return label ?: "the focused field"
    }
}
