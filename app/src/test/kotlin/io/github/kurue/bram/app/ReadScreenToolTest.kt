package io.github.kurue.bram.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadScreenToolTest {

    private fun node(
        depth: Int = 0,
        text: String = "",
        description: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
    ) = ScreenNode(
        depth = depth,
        text = text,
        description = description,
        viewId = viewId,
        className = className,
        left = 10,
        top = 20,
        right = 110,
        bottom = 120,
        clickable = clickable,
        scrollable = scrollable,
        editable = editable,
    )

    private fun tool(snapshot: ScreenSnapshot?) = ReadScreenTool {
        ScreenAccess.Ready(ScreenReader { snapshot })
    }

    @Test
    fun `read_screen reports the app and the labeled controls`() = runBlocking {
        val snapshot = ScreenSnapshot(
            packageName = "com.example.app",
            nodes = listOf(
                node(depth = 0, className = "android.widget.FrameLayout"),
                node(depth = 1, text = "Inbox", clickable = true, viewId = "com.example.app:id/inbox"),
                node(depth = 1, text = "Search", className = "android.widget.EditText", editable = true),
            ),
        )
        val result = JSONObject(tool(snapshot).execute("{}"))
        assertEquals("com.example.app", result.getString("app"))
        assertEquals(2, result.getInt("count"))
        assertEquals(2, result.getInt("total"))
        assertFalse(result.getBoolean("truncated"))
        val inbox = result.getJSONArray("nodes").getJSONObject(0)
        assertEquals("Inbox", inbox.getString("text"))
        assertEquals("com.example.app:id/inbox", inbox.getString("id"))
        assertEquals(10, inbox.getJSONArray("bounds").getInt(0))
        assertEquals(120, inbox.getJSONArray("bounds").getInt(3))
        assertTrue(inbox.getBoolean("clickable"))
        assertFalse(inbox.has("editable"))
    }

    @Test
    fun `unlabeled layout nodes are dropped`() = runBlocking {
        val snapshot = ScreenSnapshot(
            packageName = "com.example.app",
            nodes = listOf(
                node(depth = 0),
                node(depth = 1, description = "Back", clickable = true),
                node(depth = 1, scrollable = true),
                node(depth = 2),
            ),
        )
        val result = JSONObject(tool(snapshot).execute("{}"))
        assertEquals(2, result.getInt("count"))
        assertEquals("Back", result.getJSONArray("nodes").getJSONObject(0).getString("desc"))
        assertTrue(result.getJSONArray("nodes").getJSONObject(1).getBoolean("scrollable"))
    }

    @Test
    fun `long text is collapsed and capped`() = runBlocking {
        val snapshot = ScreenSnapshot(
            packageName = "com.example.app",
            nodes = listOf(node(text = "a\n\n  b   " + "c".repeat(500))),
        )
        val text = JSONObject(tool(snapshot).execute("{}")).getJSONArray("nodes").getJSONObject(0).getString("text")
        assertTrue(text.startsWith("a b ccc"))
        assertTrue(text.endsWith("…"))
        assertEquals(160 + 1, text.length)
    }

    @Test
    fun `the node cap reports what was left out`() = runBlocking {
        val snapshot = ScreenSnapshot(
            packageName = "com.example.app",
            nodes = (0 until 100).map { node(text = "row $it") },
        )
        val result = JSONObject(tool(snapshot).execute("{}"))
        assertEquals(80, result.getInt("count"))
        assertEquals(100, result.getInt("total"))
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun `a disabled service is reported with the fix`() = runBlocking {
        val result = JSONObject(ReadScreenTool { ScreenAccess.Disabled }.execute("{}"))
        assertEquals("accessibility_off", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun `a service that has not connected yet is distinguished from a disabled one`() = runBlocking {
        val result = JSONObject(ReadScreenTool { ScreenAccess.NotConnected }.execute("{}"))
        assertEquals("accessibility_starting", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun `no readable window answers screen_unavailable`() = runBlocking {
        val result = JSONObject(tool(null).execute("{}"))
        assertEquals("screen_unavailable", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun `reading is read-only but its content is untrusted`() {
        val definition = ReadScreenTool { ScreenAccess.Disabled }.definition
        assertTrue(definition.readOnly)
        assertTrue(definition.returnsUntrustedContent)
        assertTrue(definition.requiredPermissions.isEmpty())
    }
}
