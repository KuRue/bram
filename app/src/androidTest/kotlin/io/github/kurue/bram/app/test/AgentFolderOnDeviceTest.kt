package io.github.kurue.bram.app.test

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.AgentFolderAccess
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent folder's safety property on a real device: remembering a URI is not access. Only a
 * persisted system grant counts, and the document tools answer "no folder" without one — the
 * state a revoked or never-granted folder leaves behind.
 */
@RunWith(AndroidJUnit4::class)
class AgentFolderOnDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<BramApplication>()

    @After
    fun clearFolder() {
        AgentFolderAccess.clear(context)
    }

    @Test
    fun aStoredUriWithoutAPersistedGrantIsNotAccess() {
        AgentFolderAccess.clear(context)
        // A URI shaped like the picker's, but never granted: takePersistable throws and is
        // swallowed, and the validation must still refuse it.
        AgentFolderAccess.grant(
            context,
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload"),
        )

        assertNull(AgentFolderAccess.grantedTree(context))
    }

    @Test
    fun withoutAFolderTheDocumentToolsAnswerNoFolder() {
        AgentFolderAccess.clear(context)
        val container = context.container
        assertNull(container.agentDocumentTree())
        val result = kotlinx.coroutines.runBlocking {
            JSONObject(
                io.github.kurue.bram.app.ReadDocumentTool { container.agentDocumentTree() }
                    .execute("""{"path":"a.txt"}"""),
            )
        }
        assertEquals("no_folder", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun capabilitiesShowsTheAgentFolderControl() {
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("Capabilities").performClick()
        composeRule.onNodeWithText("Tools").performClick()
        composeRule.onNodeWithText("Agent folder").assertIsDisplayed()
        composeRule.onNodeWithTag("agent-folder-choose").assertIsDisplayed()
    }
}
