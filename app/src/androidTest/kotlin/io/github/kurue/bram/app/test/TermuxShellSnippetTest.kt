package io.github.kurue.bram.app.test

import io.github.kurue.bram.app.ShellSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ShellSnippet.build` is where the Termux `shell` path assembles the bash -c line: cwd restore,
 * the model's command, cwd save, and the single-quoting of every path. A bug here breaks every
 * shell call, and a quoting slip is an injection, so the cases below pin the shape rather than
 * trust it. The object is pure; these run without a device, but live in androidTest because the
 * app module has no JVM source set.
 */
class TermuxShellSnippetTest {

    @Test
    fun noSessionNoWorkdirRunsLineAsIs() {
        assertEquals("git status", ShellSnippet.build("git status", workdir = null, sessionToken = null))
    }

    @Test
    fun noSessionWithWorkdirCdsFirst() {
        // Tasks stay stateless: bash features work, cwd does not persist.
        val snippet = ShellSnippet.build("git status", workdir = "/data/data/com.termux/files/home/proj", sessionToken = null)
        assertEquals("cd '/data/data/com.termux/files/home/proj'; git status", snippet)
    }

    @Test
    fun sessionRestoresAndSavesWorkingDirectory() {
        val snippet = ShellSnippet.build("git status", workdir = null, sessionToken = "conv-1234")
        // Restores from the per-conversation state file, runs the line, saves pwd back, and keeps
        // the exit code of the line (not of pwd) on the way out.
        assertTrue("ensures the bram dir exists", snippet.contains("mkdir -p "))
        assertTrue("reads prior cwd", snippet.contains("session-conv-1234.cwd"))
        assertTrue("runs the line", snippet.contains("; git status; "))
        assertTrue("saves pwd", snippet.contains("pwd > "))
        assertTrue("preserves the line's exit code", snippet.contains("__B=\$?") && snippet.contains("exit \$__B"))
        // The state path appears twice: once for the cat (restore), once for pwd (save).
        assertEquals(2, snippet.split("session-conv-1234.cwd").size - 1)
    }

    @Test
    fun explicitWorkdirOverridesAndPersists() {
        val snippet = ShellSnippet.build("ls", workdir = "/data/data/com.termux/files/home/proj", sessionToken = "conv-1234")
        assertTrue("cds into the override", snippet.contains("cd '/data/data/com.termux/files/home/proj'; ls"))
        // The save still runs, so the override becomes the remembered cwd for the next call.
        assertTrue(snippet.contains("pwd > "))
    }

    @Test
    fun malformedSessionTokenIsIgnored() {
        // Path traversal and shell metacharacters both fail the [A-Za-z0-9_-]+ guard; the call is
        // treated as stateless rather than written into a filename.
        val snippet = ShellSnippet.build("ls", workdir = null, sessionToken = "../../../etc")
        assertEquals("ls", snippet)
        assertFalse(snippet.contains("session"))
    }

    @Test
    fun quoteEscapesSingleQuotes() {
        assertEquals("'plain'", ShellSnippet.quote("plain"))
        // The standard sh escape: close, insert an escaped quote, reopen.
        assertEquals("'a'\\''b'", ShellSnippet.quote("a'b"))
        // Spaces and $ stay literal inside the single quotes.
        assertEquals("'\$HOME with spaces'", ShellSnippet.quote("\$HOME with spaces"))
    }
}
