package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolHandler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenInteractionToolsTest {

    private fun node(
        walkIndex: Int,
        text: String = "",
        description: String = "",
        viewId: String = "",
        left: Int = 0,
        top: Int = 0,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
    ) = ScreenNode(
        walkIndex = walkIndex,
        depth = 0,
        text = text,
        description = description,
        viewId = viewId,
        className = "android.widget.TextView",
        left = left,
        top = top,
        right = left + 100,
        bottom = top + 100,
        clickable = clickable,
        scrollable = scrollable,
        editable = editable,
    )

    private val inbox = node(walkIndex = 1, text = "Inbox", viewId = "com.example.app:id/inbox", clickable = true)
    private val send = node(walkIndex = 2, text = "Send", clickable = true)
    private val search = node(walkIndex = 3, text = "Search", viewId = "com.example.app:id/search_input", editable = true)
    private val list = listOf(inbox, send, search)

    @Test
    fun `text targets prefer an exact match over a substring`() {
        val nodes = listOf(
            node(walkIndex = 0, text = "Send feedback"),
            node(walkIndex = 1, text = "Send"),
        )
        assertEquals(1, resolveScreenTarget(nodes, ScreenTarget.Text("Send"))?.walkIndex)
        assertEquals(0, resolveScreenTarget(nodes, ScreenTarget.Text("feedback"))?.walkIndex)
    }

    @Test
    fun `text targets match labels too`() {
        val nodes = listOf(node(walkIndex = 0, description = "Navigate up"))
        assertEquals(0, resolveScreenTarget(nodes, ScreenTarget.Text("navigate up"))?.walkIndex)
    }

    @Test
    fun `id targets match the full resource name or its last segment`() {
        assertEquals(1, resolveScreenTarget(list, ScreenTarget.Id("com.example.app:id/inbox"))?.walkIndex)
        assertEquals(1, resolveScreenTarget(list, ScreenTarget.Id("inbox"))?.walkIndex)
        assertNull(resolveScreenTarget(list, ScreenTarget.Id("missing")))
    }

    @Test
    fun `index targets address the list read_screen returned`() {
        assertEquals("Send", resolveScreenTarget(list, ScreenTarget.Index(1))?.text)
        assertNull(resolveScreenTarget(list, ScreenTarget.Index(9)))
    }

    @Test
    fun `point targets pick the deepest clickable control under the point`() {
        val container = node(walkIndex = 0, left = 0, top = 0, clickable = false)
            .copy(depth = 0, right = 1000, bottom = 2000)
        val icon = node(walkIndex = 1, left = 66, top = 132)
            .copy(depth = 2, right = 114, bottom = 180, clickable = true)
        assertEquals(icon.walkIndex, resolveScreenTarget(listOf(container, icon), ScreenTarget.Point(90, 156))?.walkIndex)
        assertNull(resolveScreenTarget(listOf(container, icon), ScreenTarget.Point(1500, 2500)))
    }

    @Test
    fun `tap accepts a bare point`() = runBlocking {
        val session = FakeScreenSession(tapResult = ScreenActionResult.Done("icon", "click"))
        TapTool { ScreenAccess.Ready(session) }.execute("""{"x":90,"y":156}""")
        assertEquals(ScreenTarget.Point(90, 156), session.taps.single())
    }

    @Test
    fun `tap reports what it tapped`() = runBlocking {
        val session = FakeScreenSession(tapResult = ScreenActionResult.Done("Send", "click"))
        val result = JSONObject(TapTool { ScreenAccess.Ready(session) }.execute("""{"text":"Send"}"""))
        assertEquals(true, result.getBoolean("tapped"))
        assertEquals("Send", result.getString("target"))
        assertEquals(listOf<ScreenTarget>(ScreenTarget.Text("Send")), session.taps)
    }

    @Test
    fun `tap prefers text, then id, then index`() = runBlocking {
        val byText = FakeScreenSession(tapResult = ScreenActionResult.Done("x", "click"))
        TapTool { ScreenAccess.Ready(byText) }.execute("""{"text":"Send","id":"inbox"}""")
        assertEquals(ScreenTarget.Text("Send"), byText.taps.single())

        val byId = FakeScreenSession(tapResult = ScreenActionResult.Done("x", "click"))
        TapTool { ScreenAccess.Ready(byId) }.execute("""{"id":"inbox","index":2}""")
        assertEquals(ScreenTarget.Id("inbox"), byId.taps.single())

        val byIndex = FakeScreenSession(tapResult = ScreenActionResult.Done("x", "click"))
        TapTool { ScreenAccess.Ready(byIndex) }.execute("""{"index":2}""")
        assertEquals(ScreenTarget.Index(2), byIndex.taps.single())
    }

    @Test
    fun `tap without a target explains how to name one`() = runBlocking {
        val result = JSONObject(TapTool { ScreenAccess.Ready(FakeScreenSession()) }.execute("{}"))
        assertEquals("invalid_arguments", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun `tap outcomes are reported as distinct errors`() = runBlocking {
        suspend fun code(result: ScreenActionResult): String = JSONObject(
            TapTool { ScreenAccess.Ready(FakeScreenSession(tapResult = result)) }.execute("""{"text":"x"}"""),
        ).getJSONObject("error").getString("code")
        assertEquals("not_found", code(ScreenActionResult.NotFound))
        assertEquals("not_interactive", code(ScreenActionResult.NotInteractive))
        assertEquals("stale_snapshot", code(ScreenActionResult.StaleSnapshot))
        assertEquals("screen_unavailable", code(ScreenActionResult.NoWindow))
        assertEquals("action_failed", code(ScreenActionResult.Failed("no")))
    }

    @Test
    fun `tap is side-effecting and scoped to the control`() {
        val definition = TapTool { ScreenAccess.Disabled }.definition
        assertFalse(definition.readOnly)
        assertEquals(listOf("text", "id"), definition.approvalScopeKeys)
    }

    @Test
    fun `type_text passes content and the named field`() = runBlocking {
        val session = FakeScreenSession(typeResult = ScreenActionResult.Done("Search", "set-text"))
        val result = JSONObject(
            TypeTextTool { ScreenAccess.Ready(session) }.execute("""{"text":"hello","id":"search_input"}"""),
        )
        assertEquals(true, result.getBoolean("typed"))
        assertEquals("Search", result.getString("field"))
        assertEquals("hello" to ScreenTarget.Id("search_input"), session.typed)
    }

    @Test
    fun `type_text without a target uses the focused field`() = runBlocking {
        val session = FakeScreenSession(typeResult = ScreenActionResult.Done("field", "set-text"))
        TypeTextTool { ScreenAccess.Ready(session) }.execute("""{"text":"hello"}""")
        assertEquals("hello" to null, session.typed)
    }

    @Test
    fun `type_text requires the text argument`() = runBlocking {
        val result = JSONObject(
            TypeTextTool { ScreenAccess.Ready(FakeScreenSession()) }.execute("""{"id":"search_input"}"""),
        )
        assertEquals("invalid_arguments", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun `type_text reports a non-editable target as such`() = runBlocking {
        val session = FakeScreenSession(typeResult = ScreenActionResult.NotInteractive)
        val result = JSONObject(
            TypeTextTool { ScreenAccess.Ready(session) }.execute("""{"text":"hi","index":1}"""),
        )
        assertEquals("not_editable", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun `type_text is scoped to the content being typed`() {
        val definition = TypeTextTool { ScreenAccess.Disabled }.definition
        assertFalse(definition.readOnly)
        assertEquals(listOf("text"), definition.approvalScopeKeys)
    }

    @Test
    fun `scroll moves in the asked direction over the first scrollable area`() = runBlocking {
        val session = FakeScreenSession(scrollResult = ScreenActionResult.Done("feed", "down"))
        val result = JSONObject(ScrollTool { ScreenAccess.Ready(session) }.execute("""{"direction":"down"}"""))
        assertEquals("down", result.getString("scrolled"))
        assertEquals(ScrollDirection.DOWN to null, session.scrolled)
    }

    @Test
    fun `scroll accepts an area by index and rejects a bad direction`() = runBlocking {
        val session = FakeScreenSession(scrollResult = ScreenActionResult.Done("feed", "up"))
        ScrollTool { ScreenAccess.Ready(session) }.execute("""{"direction":"up","index":2}""")
        assertEquals(ScrollDirection.UP to ScreenTarget.Index(2), session.scrolled)

        val bad = JSONObject(ScrollTool { ScreenAccess.Ready(session) }.execute("""{"direction":"sideways"}"""))
        assertEquals("invalid_arguments", bad.getJSONObject("error").getString("code"))
    }

    @Test
    fun `scroll reports a non-scrollable target as such`() = runBlocking {
        val session = FakeScreenSession(scrollResult = ScreenActionResult.NotInteractive)
        val result = JSONObject(
            ScrollTool { ScreenAccess.Ready(session) }.execute("""{"direction":"down","id":"feed"}"""),
        )
        assertEquals("not_scrollable", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun `scroll is side-effecting and scoped to the direction`() {
        val definition = ScrollTool { ScreenAccess.Disabled }.definition
        assertFalse(definition.readOnly)
        assertEquals(listOf("direction"), definition.approvalScopeKeys)
    }

    @Test
    fun `every interaction tool distinguishes a disabled service from one still connecting`() = runBlocking {
        listOf(
            TapTool { ScreenAccess.Disabled },
            TypeTextTool { ScreenAccess.Disabled },
            ScrollTool { ScreenAccess.Disabled },
        ).forEach {
            assertEquals("accessibility_off", JSONObject(it.execute(it.callArgs())).getJSONObject("error").getString("code"))
        }
        listOf(
            TapTool { ScreenAccess.NotConnected },
            TypeTextTool { ScreenAccess.NotConnected },
            ScrollTool { ScreenAccess.NotConnected },
        ).forEach {
            assertEquals("accessibility_starting", JSONObject(it.execute(it.callArgs())).getJSONObject("error").getString("code"))
        }
    }

    private fun ToolHandler.callArgs(): String =
        if (definition.name == "scroll") """{"direction":"down"}""" else """{"text":"x"}"""
}
