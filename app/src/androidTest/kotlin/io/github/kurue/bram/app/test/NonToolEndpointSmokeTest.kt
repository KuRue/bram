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
import io.github.kurue.bram.app.MainActivity
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * The capability flag is not decoration: an endpoint configured as not supporting tool calling is
 * sent no definitions at all, and the run still completes on plain text. The mock's scenario would
 * ask for `device_status` if it were offered tools, so a clean final reply proves tools were
 * withheld rather than merely ignored.
 *
 * Preconditions mirror RemoteToolLoopSmokeTest: tools/harness-mock with `adb reverse tcp:8099`.
 */
class NonToolEndpointSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireMockServer() {
        assumeTrue(
            "mock server unreachable on 127.0.0.1:8099 — start tools/harness-mock/mock_server.py " +
                "and run `adb reverse tcp:8099 tcp:8099`",
            mockServerReachable(),
        )
        assumeTrue("could not arm the happy_tool_call scenario", postScenario("happy_tool_call"))
    }

    @Test
    fun aNonToolEndpointIsSentNoToolsAndStillAnswers() {
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        composeRule.onNodeWithTag("composer-field").performTextInput("check device")
        composeRule.onNodeWithTag("send-button").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(FINAL_TEXT) }
        composeRule.onNodeWithText(FINAL_TEXT).assertIsDisplayed()

        val request = lastChatCompletionRequest()
        assertNotNull("no chat completion reached the mock", request)
        assertEquals("no tool definitions may reach this endpoint", 0, request!!.optInt("tools"))
        assertEquals(
            "the transcript must show no tool call",
            false,
            hasText("Called device_status"),
        )
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    companion object {
        private const val TIMEOUT_MILLIS = 120_000L
        private const val FINAL_TEXT = "Mock endpoint: tool result received."
        private const val ENDPOINTS_PREFS = "bram.remote_endpoints"
        private const val ENDPOINTS_KEY = "endpoints.v1"
        private const val ROUTING_PREFS = "bram-routing-v1"
        private const val MOCK_ENDPOINT_JSON =
            """{"id":"mock","displayName":"Mock endpoint","baseUrl":"http://127.0.0.1:8099/v1",""" +
                """"modelName":"mock-small","apiKind":"CHAT_COMPLETIONS","contextWindowTokens":8192,""" +
                """"supportsToolCalling":false,"allowInsecureHttp":true,"credentialAlias":"endpoint-api-key",""" +
                """"customHeaders":{},"bodyOptionsJson":"{}"}"""

        private var previousEndpoints: String? = null
        private var previousRoutingMode: String? = null
        private var previousPrimaryTarget: String? = null

        @BeforeClass
        @JvmStatic
        fun seedPreferences() {
            val endpointsPrefs = targetContext().getSharedPreferences(ENDPOINTS_PREFS, Context.MODE_PRIVATE)
            previousEndpoints = endpointsPrefs.getString(ENDPOINTS_KEY, null)
            val endpoints = runCatching { JSONArray(previousEndpoints ?: "[]") }.getOrDefault(JSONArray())
            val withoutMock = JSONArray().apply {
                for (index in 0 until endpoints.length()) {
                    val entry = endpoints.optJSONObject(index) ?: continue
                    if (entry.optString("id") != "mock") put(entry)
                }
            }
            withoutMock.put(JSONObject(MOCK_ENDPOINT_JSON))
            endpointsPrefs.edit().putString(ENDPOINTS_KEY, withoutMock.toString()).commit()

            val routingPrefs = targetContext().getSharedPreferences(ROUTING_PREFS, Context.MODE_PRIVATE)
            previousRoutingMode = routingPrefs.getString("routingMode", null)
            previousPrimaryTarget = routingPrefs.getString("primaryTargetId", null)
            routingPrefs.edit()
                .putString("routingMode", "remote_only")
                .putString("primaryTargetId", "remote:mock")
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

        private fun lastChatCompletionRequest(): JSONObject? = runCatching {
            val connection = URL("http://127.0.0.1:8099/__log").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val requests = JSONObject(body).optJSONArray("requests") ?: return null
                for (index in requests.length() - 1 downTo 0) {
                    val entry = requests.optJSONObject(index) ?: continue
                    if (entry.optString("path") == "/v1/chat/completions") return entry
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
