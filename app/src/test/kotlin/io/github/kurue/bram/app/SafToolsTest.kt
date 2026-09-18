package io.github.kurue.bram.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafToolsTest {

    private class FakeTree(
        var entries: List<AgentDocumentEntry> = emptyList(),
    ) : AgentDocumentTree {
        val files = mutableMapOf<String, String>()

        override suspend fun list(path: String): List<AgentDocumentEntry> = entries

        override suspend fun read(path: String): String =
            files[path] ?: throw AgentDocumentException("No entry named \"$path\" inside the granted folder")

        override suspend fun write(path: String, text: String) {
            files[path] = text
        }
    }

    @Test
    fun `list_documents reports entries and folders`() = runBlocking {
        val tree = FakeTree(
            entries = listOf(
                AgentDocumentEntry("notes", isDirectory = true, bytes = 0),
                AgentDocumentEntry("todo.txt", isDirectory = false, bytes = 12),
            ),
        )
        val result = JSONObject(ListDocumentsTool { tree }.execute("{}"))
        assertEquals(2, result.getInt("count"))
        val first = result.getJSONArray("entries").getJSONObject(0)
        assertEquals("notes", first.getString("name"))
        assertEquals("folder", first.getString("type"))
        assertEquals("file", result.getJSONArray("entries").getJSONObject(1).getString("type"))
    }

    @Test
    fun `read_document returns the text and flags a truncation`() = runBlocking {
        val tree = FakeTree().apply { files["long.txt"] = "x".repeat(70_000) }
        val result = JSONObject(ReadDocumentTool { tree }.execute("""{"path":"long.txt"}"""))
        assertTrue(result.getBoolean("truncated"))
        assertEquals(70_000, result.getInt("chars"))
        assertTrue(result.getString("text").startsWith("x".repeat(64_000)))
        assertTrue(result.getString("text").endsWith("[truncated]…"))
    }

    @Test
    fun `write_document stores the text and answers with the path`() = runBlocking {
        val tree = FakeTree()
        val result = JSONObject(WriteDocumentTool { tree }.execute("""{"path":"notes/todo.txt","text":"milk"}"""))
        assertEquals("notes/todo.txt", result.getString("path"))
        assertTrue(result.getBoolean("written"))
        assertEquals("milk", tree.files["notes/todo.txt"])
    }

    @Test
    fun `without a granted folder every document tool answers no_folder`() = runBlocking {
        val list = JSONObject(ListDocumentsTool { null }.execute("{}"))
        val read = JSONObject(ReadDocumentTool { null }.execute("""{"path":"a.txt"}"""))
        val write = JSONObject(WriteDocumentTool { null }.execute("""{"path":"a.txt","text":"x"}"""))
        assertEquals("no_folder", list.getJSONObject("error").getString("code"))
        assertEquals("no_folder", read.getJSONObject("error").getString("code"))
        assertEquals("no_folder", write.getJSONObject("error").getString("code"))
    }

    @Test
    fun `write_document refuses a path that tries to leave the folder`() = runBlocking {
        val error = JSONObject(
            WriteDocumentTool { FakeTree() }.execute("""{"path":"../secret.txt","text":"x"}"""),
        ).getJSONObject("error")
        assertEquals("invalid_path", error.getString("code"))
    }

    @Test
    fun `read refuses a path that tries to leave the folder`() = runBlocking {
        val error = JSONObject(
            ReadDocumentTool { FakeTree() }.execute("""{"path":"notes/../../secret.txt"}"""),
        ).getJSONObject("error")
        assertEquals("invalid_path", error.getString("code"))
    }

    @Test
    fun `reading is read-only and writing is side-effecting`() {
        assertTrue(ListDocumentsTool { null }.definition.readOnly)
        assertTrue(ReadDocumentTool { null }.definition.readOnly)
        assertFalse(WriteDocumentTool { null }.definition.readOnly)
    }

    @Test
    fun `paths are split into safe segments`() {
        assertEquals(listOf("notes", "todo.txt"), splitPath("notes/todo.txt"))
        assertEquals(listOf("a.txt"), splitPath("/a.txt"))
        assertEquals(emptyList<String>(), splitPath("../a.txt"))
        assertEquals(emptyList<String>(), splitPath("notes/../a.txt"))
    }
}
