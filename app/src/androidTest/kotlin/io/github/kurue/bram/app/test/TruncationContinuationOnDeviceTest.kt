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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * Drives the two shapes of a length-limited reply through the remote endpoint path against the
 * harness mock (tools/harness-mock/mock_server.py). No model file is involved.
 *
 *  - `truncated_then_answer`: the first scripted reply is empty with finish_reason "length", so
 *    the turn must run once more carrying the nudge, and settle with the scripted answer — the
 *    continuation path found on the phone when a reasoning model spent its whole reply budget
 *    thinking.
 *  - `truncated_partial`: the scripted reply exists but was cut off, so it settles with the
 *    truncation notice rather than being run again.
 *
 * Preconditions, checked with an assumption so the suite skips cleanly without them:
 *   py -3 tools/harness-mock/mock_server.py --port 8099
 *   adb reverse tcp:8099 tcp:8099
 *
 * The endpoint and routing preferences are seeded for the run and restored afterwards.
 */
class TruncationContinuationOnDeviceTest {

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
    fun aCutOffRoundWithNoAnswerRunsAgainAndAnswers() {
        assumeTrue("could not arm the truncated_then_answer scenario", postScenario("truncated_then_answer"))
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        // The scenario is stamped on every logged request, so counting this test's own requests
        // survives other classes' entries in the shared log.
        val before = loggedChatCompletions().count { it.isTruncationTurnRequest() }
        send(ASK)
        // The scripted answer goes only to a request carrying the nudge, so seeing it at all is
        // the continuation having run; the log check below pins how.
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(TRUNCATED_FINAL_TEXT) }
        composeRule.onNodeWithText(TRUNCATED_FINAL_TEXT).assertIsDisplayed()

        val mine = loggedChatCompletions().filter { it.isTruncationTurnRequest() }
        assertTrue(
            "expected the truncated round and its continuation, saw ${mine.size - before}",
            mine.size - before >= 2,
        )
        assertTrue(
            "the continuation request must carry the nudge as its last message",
            mine.drop(before).any { it.endsWithSystemMessage() },
        )
    }

    @Test
    fun aCutOffReplyThatExistsKeepsItsTextAndCarriesTheNotice() {
        assumeTrue("could not arm the truncated_partial scenario", postScenario("truncated_partial"))
        composeRule.onNodeWithTag("new-chat").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Mock endpoint") }
        val before = loggedChatCompletions().count { it.optString("scenario") == "truncated_partial" && it.carriesUserAsk() }
        send(ASK)
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText(TRUNCATED_PARTIAL_TEXT) }
        composeRule.waitUntil(TIMEOUT_MILLIS) { countText(NOTICE_MARKER) >= 1 }

        // A reply that exists is not run again: the one turn request is all the mock should see.
        // The memory extraction that follows is stamped with the same scenario, so the count
        // keeps to requests carrying the user's ask itself.
        assertEquals(
            "a cut-off reply that exists must not be run again",
            1,
            loggedChatCompletions().count { it.optString("scenario") == "truncated_partial" && it.carriesUserAsk() } - before,
        )
    }

    private fun send(text: String) {
        composeRule.onNodeWithTag("composer-field").performTextInput(text)
        composeRule.onNodeWithTag("send-button").performClick()
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun countText(text: String): Int =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    /**
     * Whether this logged request is one of this class's own turn requests: stamped with the
     * truncation scenario and carrying the user's ask as its own user message. The memory
     * extraction run shares the scenario stamp, but its only user item is the whole exchange it
     * was asked to read, which is longer than the ask.
     */
    private fun JSONObject.isTruncationTurnRequest(): Boolean =
        optString("scenario") == "truncated_then_answer" && carriesUserAsk()

    /** Whether the request carries the user's ask as its own user message. */
    private fun JSONObject.carriesUserAsk(): Boolean {
        val items = optJSONArray("items") ?: return false
        return (0 until items.length()).any { index ->
            items.optJSONObject(index)?.let {
                it.optString("role") == "user" && it.optInt("contentLength") == ASK.length
            } == true
        }
    }

    /** Whether the item list ends on a system message — the shape the continuation nudge gives. */
    private fun JSONObject.endsWithSystemMessage(): Boolean {
        val items = optJSONArray("items") ?: return false
        return items.length() > 0 && items.optJSONObject(items.length() - 1)?.optString("role") == "system"
    }

    companion object {
        private const val TIMEOUT_MILLIS = 120_000L
        private const val ENDPOINTS_PREFS = "bram.remote_endpoints"
        private const val ENDPOINTS_KEY = "endpoints.v1"
        private const val ROUTING_PREFS = "bram-routing-v1"
        private const val MOCK_ENDPOINT_ID = "mock"
        private const val ASK = "tell me the answer"
        private const val TRUNCATED_FINAL_TEXT = "Mock endpoint: answered after the cut-off."
        private const val TRUNCATED_PARTIAL_TEXT = "The answer so far is forty-two, and the reasoning was long."
        private const val NOTICE_MARKER = "length limit"
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

        /** Every chat completion the mock has logged so far, oldest first. */
        private fun loggedChatCompletions(): List<JSONObject> = runCatching {
            val connection = URL("http://127.0.0.1:8099/__log").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            try {
                val requests = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .optJSONArray("requests") ?: return emptyList()
                (0 until requests.length())
                    .mapNotNull { requests.optJSONObject(it) }
                    .filter { it.optString("path") == "/v1/chat/completions" }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(emptyList())

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
