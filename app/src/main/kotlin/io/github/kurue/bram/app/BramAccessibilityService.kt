package io.github.kurue.bram.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The system-bound side of screen reading.
 *
 * Deliberately inert: Bram does not react to accessibility events, so the service does no work on
 * its own. It exists so [rootInActiveWindow] has an owner, and everything it reads is pulled on
 * demand by [ReadScreenTool] and nothing is stored.
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

    /**
     * Walks the active window's hierarchy, parents before children, capping the walk so a huge or
     * misbehaving tree cannot hold the call. Null when the system exposes no active window.
     */
    internal fun walkActiveWindow(): ScreenSnapshot? = ScreenTreeReader.walk(rootInActiveWindow)

    private companion object {
        const val MAX_WALK_NODES = 600
    }
}

/**
 * Turns an [AccessibilityNodeInfo] tree into transport-safe nodes.
 *
 * Separate from the service so the walk can be exercised against a real window from a device test
 * (through the instrumentation's own accessibility automation), where binding a second service
 * during a run is not possible.
 */
object ScreenTreeReader {
    fun walk(root: AccessibilityNodeInfo?): ScreenSnapshot? {
        if (root == null) return null
        val nodes = mutableListOf<ScreenNode>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty() && nodes.size < MAX_WALK_NODES) {
            val (node, depth) = pending.removeLast()
            nodes += node.toScreenNode(depth)
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let { child -> pending.addLast(child to depth + 1) }
            }
        }
        return ScreenSnapshot(packageName = root.packageName?.toString().orEmpty(), nodes = nodes)
    }

    private fun AccessibilityNodeInfo.toScreenNode(depth: Int): ScreenNode {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return ScreenNode(
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

    internal fun attach(value: BramAccessibilityService) {
        service = value
    }

    internal fun detach(value: BramAccessibilityService) {
        if (service === value) service = null
    }

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

    /** What screen reading can do right now, waiting briefly for a reconnect when one is coming. */
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
        return ScreenAccess.Ready(
            ScreenReader {
                withContext(Dispatchers.Main.immediate) {
                    // A service detached between the check and the call must fail as "nothing to
                    // read" rather than crash the tool.
                    runCatching { current.walkActiveWindow() }.getOrNull()
                }
            },
        )
    }

    private const val CONNECT_WAIT_MILLIS = 2_000L
    private const val CONNECT_POLL_MILLIS = 100L
}
