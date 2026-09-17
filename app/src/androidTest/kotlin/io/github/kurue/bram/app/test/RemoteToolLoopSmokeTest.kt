package io.github.kurue.bram.app.test

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.app.FilesTool
import io.github.kurue.bram.app.MainActivity
import io.github.kurue.bram.core.domain.ToolResultBudget
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * Drives scripted tool turns through the remote endpoint path against the harness mock server
 * (tools/harness-mock/mock_server.py). No model file is involved: the scripted endpoint answers
 * with a `device_status` call, and then with a fixed final reply once the tool result arrives.
 *
 * Preconditions, checked with an assumption so the suite skips cleanly without them:
 *   py -3 tools/harness-mock/mock_server.py --port 8099
 *   adb reverse tcp:8099 tcp:8099
 *
 * The endpoint and routing preferences are seeded for the run and restored afterwards.
 */
class RemoteToolLoopSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun armMockScenario() {
        assumeTrue(
            "mock server unreachable on 127.0.0.1:8099 — start tools/harness-mock/mock_server.py " +
                "and run `adb reverse tcp:8099 tcp:8099`",
            mockServerReachable(),
        )
        assumeTrue("could not arm the happy_tool_call scenario", postScenario("happy_tool_call"))
    }

    @Test
    fun remoteToolTurnRunsAndShowsTheResult() {
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        composeRule.onNodeWithTag("composer-field").performTextInput("check device")
        composeRule.onNodeWithTag("send-button").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Called device_status") }
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(FINAL_TEXT) }
        composeRule.onNodeWithText(FINAL_TEXT).assertIsDisplayed()
    }

    @Test
    fun manualModeShowsTheApprovalCardBeforeRunningTheTool() {
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("Conversation").performClick()
        composeRule.onNodeWithText("Always ask").performClick()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("composer-field").performTextInput("check device")
        composeRule.onNodeWithTag("send-button").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Allow Bram to device_status?") }
        composeRule.onNodeWithText("Allow once").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(FINAL_TEXT) }
        composeRule.onNodeWithText(FINAL_TEXT).assertIsDisplayed()
    }

    @Test
    fun toolHistorySurvivesAcrossTurns() {
        assumeTrue("could not arm the history_check scenario", postScenario("history_check"))
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        send("check device")
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(FINAL_TEXT) }
        // Sending while the first turn is still wrapping up is safe: it queues and starts its own
        // turn when the reply settles.
        send("check again")
        composeRule.waitUntil(TIMEOUT_MILLIS) { countText(FINAL_TEXT) >= 2 }

        // The scenario answers 400 when an assistant tool call has no matching result, so the
        // second turn completing at all already means the history replayed. This makes it explicit.
        val request = lastChatCompletionRequest()
        assertNotNull("no chat completion reached the mock", request)
        val items = request!!.optJSONArray("items")
        val callIds = mutableSetOf<String>()
        val answered = mutableSetOf<String>()
        for (index in 0 until (items?.length() ?: 0)) {
            val item = items!!.optJSONObject(index) ?: continue
            item.optJSONArray("toolCallIds")?.let { ids ->
                for (idIndex in 0 until ids.length()) callIds += ids.optString(idIndex)
            }
            item.optString("toolCallId").takeIf(String::isNotBlank)?.let(answered::add)
        }
        assertTrue("the replayed request carried no earlier tool call", callIds.isNotEmpty())
        assertTrue("a replayed tool call had no matching result: $callIds vs $answered", answered.containsAll(callIds))
    }

    @Test
    fun oversizedToolResultIsBoundedBeforeItReplays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesRoot = File(context.filesDir, FilesTool.AGENT_FILES_DIR)
        filesRoot.mkdirs()
        val seeded = File(filesRoot, "oversized.txt")
        seeded.writeText("x".repeat(120_000))
        try {
            assumeTrue("could not arm the read_file_oversized scenario", postScenario("read_file_oversized"))
            composeRule.onNodeWithTag("new-chat").performClick()
            composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
            send("read the oversized file")
            composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(FINAL_TEXT) }

            // The request that carried the tool result must hold the bounded copy, not the 64 KB
            // read_file returned: the endpoint's window is 8K tokens, so the cap is a quarter of it.
            val request = lastChatCompletionRequest()
            assertNotNull("no chat completion reached the mock", request)
            val items = request!!.optJSONArray("items")
            var toolContentLength = -1
            for (index in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(index) ?: continue
                if (item.optString("role") == "tool") toolContentLength = item.optInt("contentLength", -1)
            }
            val cap = ToolResultBudget.capChars(ENDPOINT_CONTEXT_TOKENS)
            assertTrue("expected a tool result in the replayed request", toolContentLength >= 0)
            assertTrue("tool result of $toolContentLength chars exceeds the $cap-char cap", toolContentLength <= cap)
        } finally {
            seeded.delete()
        }
    }

    private fun send(text: String) {
        composeRule.onNodeWithTag("composer-field").performTextInput(text)
        composeRule.onNodeWithTag("send-button").performClick()
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun countText(text: String): Int =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    companion object {
        private const val TIMEOUT_MILLIS = 120_000L
        private const val FINAL_TEXT = "Mock endpoint: tool result received."
        private const val ENDPOINT_CONTEXT_TOKENS = 8_192
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

        /** The latest completion the app sent with tools offered, as the mock logged it. */
        private fun lastChatCompletionRequest(): JSONObject? = runCatching {
            val connection = URL("http://127.0.0.1:8099/__log").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val requests = JSONObject(body).optJSONArray("requests") ?: return null
                for (index in requests.length() - 1 downTo 0) {
                    val entry = requests.optJSONObject(index) ?: continue
                    if (entry.optString("path") != "/v1/chat/completions") continue
                    if (entry.optInt("tools") <= 0) continue
                    return entry
                }
                null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

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
