package io.github.kurue.bram.app.test

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.app.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * A remote turn must always leave the generating phase, however it ends.
 *
 * This is the settling half of the Stop work, and it was invisible until a device trace showed a turn
 * stuck on "Writing…" with nothing left to cancel. The cause is in `runRound`'s completion block
 * (`MainViewModel.kt:4566-4572`): it only calls `pushModelStatus(ModelPhase.IDLE)` when a **local**
 * model is loaded, so a remote turn — which has neither `loadedModelId` nor `litertlmLoadedId` — always
 * took the other branch, stopped the foreground service and never reset the phase. The phase therefore
 * stayed `GENERATING` for the rest of the session, and because the label function checks `modelPhase`
 * before its idle fallback, the screen kept reading "Writing…" and the composer could never show a
 * settled turn again.
 *
 * So these two tests cover both endings: a remote turn that completes, and a remote turn whose stream
 * **fails**. The second matters more — a failed stream does not reach the completion block at all, so
 * clearing the phase there needs a `finally`, not just an extra line in the remote branch.
 */
class RemoteTurnSettlingTest {
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
    fun aCompletedRemoteTurnReturnsToTheIdlePhase() {
        assumeTrue("could not arm stream_chat", postScenario("stream_chat"))
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        composeRule.onNodeWithTag("composer-field").performTextInput(ASK)
        composeRule.onNodeWithTag("send-button").performClick()

        // The turn is over once the answer is on screen and the phase is idle again. "Writing…" never
        // being left behind is the whole point: a remote turn used to stay in GENERATING forever.
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(IDLE_PHASE) }
        composeRule.waitUntil(TIMEOUT_MILLIS) { !hasText(WRITING_PHASE) }
    }

    @Test
    fun aFailedRemoteStreamStillLeavesTheGeneratingPhase() {
        // `status_failed` answers with an error, so the read fails and the turn cannot complete
        // normally. Whatever happens next, the UI must not be left in the generating phase.
        assumeTrue("could not arm status_failed", postScenario("status_failed"))
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        composeRule.onNodeWithTag("composer-field").performTextInput(ASK)
        composeRule.onNodeWithTag("send-button").performClick()

        composeRule.waitUntil(TIMEOUT_MILLIS) { !hasText(WRITING_PHASE) }
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    companion object {
        private const val TIMEOUT_MILLIS = 60_000L
        private const val ASK = "tell me the answer"
        private const val IDLE_PHASE = "Ready"
        private const val WRITING_PHASE = "Writing"
        private const val ENDPOINTS_PREFS = "bram.remote_endpoints"
        private const val ENDPOINTS_KEY = "endpoints.v1"
        private const val ROUTING_PREFS = "bram-routing-v1"
        private const val MOCK_ENDPOINT_ID = "mock"
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
            previousSelectedRuntime = routingPrefs.getString(SELECTED_RUNTIME_KEY, null)
            routingPrefs.edit()
                .putString("routingMode", "remote_only")
                .putString("primaryTargetId", "remote:$MOCK_ENDPOINT_ID")
                // The chat runtime is chosen from `selectedRuntimeId`, and on a device with a local
                // model the profile restore would otherwise win and run the turn locally.
                .putString(SELECTED_RUNTIME_KEY, "remote:$MOCK_ENDPOINT_ID")
                .commit()
        }

        @AfterClass
        @JvmStatic
        fun restorePreferences() {
            // The mock's scenario is global state and outlives this class, so leave a responsive one
            // armed or the next class inherits the failure.
            postScenario("happy_tool_call")
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

        /** `RoutingSettingsStore.SELECTED_RUNTIME_KEY` — the persisted chat-runtime choice. */
        private const val SELECTED_RUNTIME_KEY = "selectedRuntimeId"

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
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
