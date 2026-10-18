package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.RecipeVerdict
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecipeValidationHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `interrupted checks recover without becoming approval or a permanent exclusion`() {
        val file = File(temporary.newFolder(), "history.json")
        val key = "a".repeat(64)
        RecipeValidationHistory(file).save(RecipeValidationRecord(key, RecipeVerdict.CHECKING, "Checking"))
        val recovered = RecipeValidationHistory(file).recover().single()
        assertEquals(RecipeVerdict.INTERRUPTED, recovered.verdict)
        assertEquals(recovered, RecipeValidationHistory(file).read().single())
    }

    @Test fun `terminal results survive restart and new results replace only their exact identity`() {
        val file = File(temporary.newFolder(), "history.json")
        val history = RecipeValidationHistory(file)
        val before = RecipeValidationRecord("a".repeat(64), RecipeVerdict.REJECTED, "Agreement failed")
        history.save(before)
        val other = before.copy(key = "b".repeat(64), verdict = RecipeVerdict.APPLIED, detail = "Validated", incumbentMillis = 200, proposedMillis = 160)
        history.save(other)
        assertEquals(listOf(other, before), RecipeValidationHistory(file).recover())
        val retry = before.copy(verdict = RecipeVerdict.KEPT, detail = "Within noise")
        history.save(retry)
        assertEquals(listOf(retry, other), RecipeValidationHistory(file).read())
    }

    @Test fun `history is bounded and refuses corrupt data without silently replacing it`() {
        val file = File(temporary.newFolder(), "history.json")
        val history = RecipeValidationHistory(file)
        repeat(130) { index -> history.save(RecipeValidationRecord(index.toString(), RecipeVerdict.KEPT, "No improvement")) }
        assertEquals(128, history.read().size)
        assertEquals("129", history.read().first().key)
        file.writeText("corrupted")
        assertTrue(runCatching { history.save(RecipeValidationRecord("x", RecipeVerdict.APPLIED, "Never")) }.isFailure)
        assertEquals("corrupted", file.readText())
    }
}
