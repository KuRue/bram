package io.github.kurue.bram.app.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.InteractiveApprovalGate
import io.github.kurue.bram.app.PendingToolApproval
import io.github.kurue.bram.app.ToolPermissions
import io.github.kurue.bram.core.domain.PermissionMode
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolDefinition
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks down the approval gate's notification path: a tool call nobody is at the card to answer
 * parks on a request the notifier posts, instead of being silently denied. The notify hook returns
 * whether it could post; when it cannot (no permission, or no hook at all), the old fast-deny still
 * applies so an invisible ask never holds the run. Bypass still runs without asking, and the
 * attended path still parks on the in-app card rather than the shade.
 *
 * The gate is pure Kotlin; this lives in androidTest because the app module has no JVM source set.
 */
@RunWith(AndroidJUnit4::class)
class InteractiveApprovalGateNotificationTest {

    // Side-effecting so AUTO does not silently allow it: the gate must reach the ask.
    private val tool = ToolDefinition(
        name = "do_thing",
        description = "does a thing with side effects",
        inputSchemaJson = "{}",
        readOnly = false,
    )

    private fun emptyPermissions() = object : ToolPermissions {
        override fun alwaysAllowed(): Set<String> = emptySet()
        override fun allowAlways(toolName: String) {}
        override fun withdraw(toolName: String) {}
    }

    @Test
    fun unattendedParksAndNotifiesAndResolves() = runBlocking {
        val gate = InteractiveApprovalGate(emptyPermissions()).apply {
            setMode(PermissionMode.AUTO)
            setAttended(false)
        }
        lateinit var posted: PendingToolApproval
        lateinit var resolved: PendingToolApproval
        gate.notifyRequest = { request ->
            posted = request
            // The shade would answer here; resolve from inside the hook so decide() can return.
            request.resolve(ToolApprovalDecision.ALLOW_ONCE)
            true
        }
        gate.onRequestResolved = { resolved = it }

        val decision = gate.decide(tool, "{}", recovered = false, untrustedContext = false)

        assertEquals(ToolApprovalDecision.ALLOW_ONCE, decision)
        assertEquals("do_thing", posted.toolName)
        assertEquals(posted.id, resolved.id)
    }

    @Test
    fun unattendedWhenNotifierCannotPostFastDenies() = runBlocking {
        val gate = InteractiveApprovalGate(emptyPermissions()).apply {
            setMode(PermissionMode.AUTO)
            setAttended(false)
            notifyRequest = { false }
        }
        val decision = gate.decide(tool, "{}", recovered = false, untrustedContext = false)
        assertEquals(ToolApprovalDecision.DENY, decision)
    }

    @Test
    fun unattendedWithNoNotifierFastDenies() = runBlocking {
        // notifyRequest left null: nothing can be posted, so the run is not held for an unanswered
        // timeout — the pre-notification behaviour is preserved.
        val gate = InteractiveApprovalGate(emptyPermissions()).apply {
            setMode(PermissionMode.AUTO)
            setAttended(false)
        }
        val decision = gate.decide(tool, "{}", recovered = false, untrustedContext = false)
        assertEquals(ToolApprovalDecision.DENY, decision)
    }

    @Test
    fun bypassAllowsWithoutNotifyingEvenWhenUnattended() = runBlocking {
        val gate = InteractiveApprovalGate(emptyPermissions()).apply {
            setMode(PermissionMode.BYPASS)
            setAttended(false)
            notifyRequest = { error("bypass must not reach the notifier") }
        }
        val decision = gate.decide(tool, "{}", recovered = false, untrustedContext = false)
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, decision)
    }

    @Test
    fun attendedParksOnTheCardWithoutNotifying() = runBlocking {
        val gate = InteractiveApprovalGate(emptyPermissions()).apply {
            setMode(PermissionMode.AUTO)
            setAttended(true)
            notifyRequest = { error("the attended path answers at the card, not the shade") }
        }
        val pending = async { gate.decide(tool, "{}", recovered = false, untrustedContext = false) }
        // The in-app card appears; resolve it as the UI would.
        val card = gate.pending.first { it != null }!!
        card.resolve(ToolApprovalDecision.DENY)

        assertEquals(ToolApprovalDecision.DENY, pending.await())
        // The card is cleared once the call settles, whatever the outcome.
        assertNull(gate.pending.value)
    }
}
