package io.github.kurue.bram.app.test

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.app.MainActivity
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertFalse
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
    }

    @Test
    fun aSilentTurnKeepsRunningAndStillStops() {
        assumeTrue("could not arm the stall_response scenario", postScenario("stall_response"))
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        composeRule.onNodeWithTag("composer-field").performTextInput(ASK)
        composeRule.onNodeWithTag("send-button").performClick()

        // Well inside the five-minute stall budget, and long past the point where a premature
        // verdict would have settled the turn: neither a stop nor a stall failure may be on
        // screen while the run is still silent.
        Thread.sleep(SILENCE_MILLIS)
        assertFalse("the turn must not have settled while the run is still silent", hasText("Generation stopped"))
        assertFalse("a silent turn must not be failed before its budget", hasText(STALL_FAILURE))

        // Self-proving that the turn was in flight: with a blank composer the same button is
        // Stop, so this settles only if the run was still live and cancellation still reached it.
        composeRule.onNodeWithTag("send-button").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Generation stopped") }
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    companion object {
        private const val TIMEOUT_MILLIS = 60_000L
        private const val SILENCE_MILLIS = 25_000L
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
        }

        @AfterClass
        @JvmStatic
        fun restorePreferences() {
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
