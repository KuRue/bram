package io.github.kurue.bram.app.test

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.app.EndpointHealth
import io.github.kurue.bram.app.MainActivity
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * A silent turn must neither be failed early nor lose the stop button.
 *
 * PR #41 put a stall monitor beside the agent flow, so the two risks it introduces are both
 * on this path: a healthy turn judged stalled, and the extra sibling coroutine swallowing the
 * cancellation a stop sends. The `stall_response` scenario accepts the request and then says
 * nothing for two minutes, so this drives a real turn into silence and checks that it is still
 * running well inside the five-minute budget, and that tapping the composer button (Stop while a
 * blank composer is generating) still settles the turn.
 *
 * The far side of the budget — the stall itself failing the round and letting the fallback loop
 * run — is `TurnStallWatchdogTest`'s subject and would cost five minutes of wall clock per run;
 * what this pins is the wiring around it.
 *
 * The second half is the stop path. It used to stop the *turn* only on paper: the remote read was a
 * blocking socket read that a coroutine cancellation could not interrupt, so after Stop the phase
 * stayed on "Writing…" until the peer answered or the 120s read timeout expired. Cancelling the
 * OkHttp call closes the socket, so the parked read now fails at once and the turn settles — which
 * is what `STOP_BUDGET_MILLIS` holds this to.
 *
 * The mock's `stall_response` answers with SSE headers and then stays silent, so the turn is parked
 * in its *body read* — the same place the runtime-level gate in `OpenAiCompatibleRuntimeTest`
 * parks, and the harder of the two. A peer that never answered at all would block on the response
 * instead and would not exercise this path.
 *
 * Preconditions, checked with an assumption so the suite skips cleanly without them:
 *   py -3 tools/harness-mock/mock_server.py --port 8099
 *   adb reverse tcp:8099 tcp:8099
 *
 * The endpoint and routing preferences are seeded for the run and restored afterwards.
 */
class TurnStallOnDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireMockServer() {
        assumeTrue(
            "mock server unreachable on 127.0.0.1:8099 — start tools/harness-mock/mock_server.py " +
                "and run `adb reverse tcp:8099 tcp:8099`",
            mockServerReachable(),
        )
        // The turn this class stops goes on to fail, and a failed remote turn marks the endpoint
        // down. The process is shared by the whole suite, so the mark has to be cleared here rather
        // than left for the next class to trip over — see RemoteTurnSettlingTest for the longer
        // account of what that costs.
        EndpointHealth.clear()
    }

    @Test
    fun aSilentTurnKeepsRunningAndCanBeStopped() {
        assumeTrue("could not arm the stall_response scenario", postScenario("stall_response"))
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        composeRule.onNodeWithTag("composer-field").performTextInput(ASK)
        composeRule.onNodeWithTag("send-button").performClick()

        // Prove the turn is actually live and writing before anything else. A turn that never
        // reached the provider (bad endpoint, auth, connection refused) settles on its own, and then
        // the Stop below is a no-op and `waitUntil(IDLE_PHASE)` returns instantly — a vacuous pass.
        // This gate must only ever report a Stop that had something to stop.
        //
        // The live signal is GENERATING ("Writing"). This was briefly changed to PREPARING on the
        // assumption that a peer which sends headers and then nothing never reaches GENERATING — a
        // device trace disproved it: the phase goes PREPARING -> GENERATING about 0.4s in, on the
        // headers themselves, and then simply stays there through the silence. "Ready" is the idle
        // label, and the label function shows it whenever an endpoint is merely selected
        // (BramApp.kt:620), so "Ready" means "not in a turn" and GENERATING means a live one.
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(WRITING_PHASE) }
        assertFalse(
            "the turn must not have settled before Stop was pressed",
            hasText(IDLE_PHASE),
        )

        // Well inside the five-minute stall budget, and long past the point where a premature
        // verdict would have settled the turn: neither a stop nor a stall failure may be on
        // screen while the run is still silent.
        Thread.sleep(SILENCE_MILLIS)
        assertFalse("the turn must not have settled while the run is still silent", hasText("Generation stopped"))
        assertFalse("a silent turn must not be failed before its budget", hasText(STALL_FAILURE))
        assertTrue(
            "the silent turn must still be in flight when Stop is pressed; a settled turn proves nothing",
            hasText(WRITING_PHASE),
        )

        // With a blank composer the same button is Stop, so this only acts on a live run.
        composeRule.onNodeWithTag("send-button").performClick()
        // The stop must now *settle* the turn, not merely register on screen. It used not to: the
        // remote read was a blocking socket read that a coroutine cancellation could not interrupt,
        // so the run waited out the provider's silence and the phase stayed on "Writing…".
        // Cancelling the OkHttp call closes the socket, so the parked read fails at once and the
        // phase returns to idle. A budget well under the old timeout, far above a local close.
        composeRule.waitUntil(STOP_BUDGET_MILLIS) { hasText(IDLE_PHASE) }
        assertFalse("the turn should be settled, not still writing", hasText(WRITING_PHASE))
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    companion object {
        private const val TIMEOUT_MILLIS = 60_000L
        private const val SILENCE_MILLIS = 25_000L

        /**
         * How long a stopped turn may take to settle. Far above a local socket close, and far below
         * the 120s read timeout the old reader waited out — so a regression to that behaviour fails
         * here instead of passing slowly.
         */
        private const val STOP_BUDGET_MILLIS = 20_000L

        /** The idle model phase, shown once a turn has settled and the runtime is not writing. */
        private const val IDLE_PHASE = "Ready"

        /** The phase shown while a turn is streaming; must be gone once the stop has settled it. */
        private const val WRITING_PHASE = "Writing"


        /** `RoutingSettingsStore.SELECTED_RUNTIME_KEY` — the persisted chat-runtime choice. */
        private const val SELECTED_RUNTIME_KEY = "selectedRuntimeId"

        private const val ENDPOINTS_PREFS = "bram.remote_endpoints"
        private const val ENDPOINTS_KEY = "endpoints.v1"
        private const val ROUTING_PREFS = "bram-routing-v1"
        private const val MOCK_ENDPOINT_ID = "mock"
        private const val ASK = "tell me the answer"
        private const val STALL_FAILURE = "stopped responding with no output"
        private const val MOCK_ENDPOINT_JSON =
            """{"id":"mock","displayName":"Mock endpoint","baseUrl":"http://127.0.0.1:8099/v1",""" +
                """"modelName":"mock-small","apiKind":"CHAT_COMPLETIONS","contextWindowTokens":8192,""" +
                """"supportsToolCalling":true,"allowInsecureHttp":true,"credentialAlias":"endpoint-api-key",""" +
                """"customHeaders":{},"bodyOptionsJson":"{}"}"""

        private var previousEndpoints: String? = null
        private var previousRoutingMode: String? = null
        private var previousPrimaryTarget: String? = null
        private var previousSelectedRuntime: String? = null

        @BeforeClass
        @JvmStatic
        fun seedEndpointPreferences() {
            val endpointsPrefs = targetContext().getSharedPreferences(ENDPOINTS_PREFS, Context.MODE_PRIVATE)
            previousEndpoints = endpointsPrefs.getString(ENDPOINTS_KEY, null)
            val endpoints = runCatching { JSONArray(previousEndpoints ?: "[]") }.getOrDefault(JSONArray())
            val hasMock = (0 until endpoints.length())
                .any { endpoints.optJSONObject(it)?.optString("id") == MOCK_ENDPOINT_ID }
            if (!hasMock) endpoints.put(JSONObject(MOCK_ENDPOINT_JSON))
            endpointsPrefs.edit().putString(ENDPOINTS_KEY, endpoints.toString()).commit()

            val routingPrefs = targetContext().getSharedPreferences(ROUTING_PREFS, Context.MODE_PRIVATE)
            previousRoutingMode = routingPrefs.getString("routingMode", null)
            previousPrimaryTarget = routingPrefs.getString("primaryTargetId", null)
            routingPrefs.edit()
                .putString("routingMode", "remote_only")
                .putString("primaryTargetId", "remote:$MOCK_ENDPOINT_ID")
                .commit()

            // Seeding the routing mode and pool was not enough to make the turn use the mock. The
            // chat runtime is chosen from `selectedRuntimeId`, and on a device that has a local model
            // the profile restore wins and a local turn runs instead — so on Ku's phone this gate was
            // quietly testing nothing, while on the AVD (no local models) it looked fine. Writing the
            // persisted choice the app now honours makes the route authoritative on any device, and
            // is what Phase 2 of the routing fix is required to satisfy. Restored below.
            previousSelectedRuntime = routingPrefs.getString(SELECTED_RUNTIME_KEY, null)
            routingPrefs.edit()
                .putString(SELECTED_RUNTIME_KEY, "remote:$MOCK_ENDPOINT_ID")
                .commit()
        }

        @AfterClass
        @JvmStatic
        fun restorePreferences() {
            // The mock's scenario is one piece of global state, and a stalled one outlives this
            // class: left armed, the next request that does not re-arm waits out the stall instead
            // of answering. Re-arm a responsive scenario so a following class cannot inherit it.
            postScenario("happy_tool_call")
            // The reachability mark this class's stopped turn leaves is the other piece of global
            // state, and it outlives the class just the same.
            EndpointHealth.clear()
            val endpointsPrefs = targetContext().getSharedPreferences(ENDPOINTS_PREFS, Context.MODE_PRIVATE)
            if (previousEndpoints == null) {
                endpointsPrefs.edit().remove(ENDPOINTS_KEY).commit()
            } else {
                endpointsPrefs.edit().putString(ENDPOINTS_KEY, previousEndpoints).commit()
            }
            val routingPrefs = targetContext().getSharedPreferences(ROUTING_PREFS, Context.MODE_PRIVATE)
            routingPrefs.edit()
                .also { editor ->
                    if (previousRoutingMode == null) editor.remove("routingMode") else editor.putString("routingMode", previousRoutingMode)
                    if (previousPrimaryTarget == null) editor.remove("primaryTargetId") else editor.putString("primaryTargetId", previousPrimaryTarget)
                    if (previousSelectedRuntime == null) editor.remove(SELECTED_RUNTIME_KEY) else editor.putString(SELECTED_RUNTIME_KEY, previousSelectedRuntime)
                }
                .commit()
        }

        private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

        private fun mockServerReachable(): Boolean = runCatching {
            val connection = URL("http://127.0.0.1:8099/__health").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)

        private fun postScenario(name: String): Boolean = runCatching {
            val connection = URL("http://127.0.0.1:8099/__scenario/$name").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            try {
                connection.outputStream.use { it.write("{}".toByteArray()) }
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
