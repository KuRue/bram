package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionTest {
    @Test
    fun `parses a clean array of facts and instructions`() {
        val out = parseExtractedMemories(
            """[{"kind":"fact","text":"The user lives in Tokyo"},{"kind":"instruction","text":"Reply in French"}]""",
        )
        assertEquals(2, out.size)
        assertEquals(MemoryKind.SEMANTIC_FACT, out[0].kind)
        assertEquals("The user lives in Tokyo", out[0].text)
        assertEquals(MemoryKind.USER_INSTRUCTION, out[1].kind)
    }

    @Test
    fun `strips a json code fence and surrounding prose`() {
        val out = parseExtractedMemories(
            "Here is what I kept:\n```json\n[{\"kind\":\"fact\",\"text\":\"Uses Python\"}]\n```\nDone.",
        )
        assertEquals(1, out.size)
        assertEquals("Uses Python", out[0].text)
    }

    @Test
    fun `defaults importance per kind when omitted`() {
        val out = parseExtractedMemories(
            """[{"kind":"fact","text":"A fact"},{"kind":"instruction","text":"An instruction"}]""",
        )
        assertEquals(0.6, out[0].importance, 1e-9)
        assertEquals(0.8, out[1].importance, 1e-9)
    }

    @Test
    fun `skips unknown kinds, blank text, and too-short text`() {
        val out = parseExtractedMemories(
            """[
              {"kind":"emotion","text":"the user is happy"},
              {"kind":"fact","text":"   "},
              {"kind":"fact","text":"hi"},
              {"kind":"instruction","text":"Be concise"}
            ]""",
        )
        assertEquals(listOf("Be concise"), out.map { it.text })
    }

    @Test
    fun `returns empty for an empty array, missing array, or malformed json`() {
        assertTrue(parseExtractedMemories("[]").isEmpty())
        assertTrue(parseExtractedMemories("The user likes cats.").isEmpty())
        assertTrue(parseExtractedMemories("[not actually json]").isEmpty())
    }

    @Test
    fun `caps the number of extracted items`() {
        val items = (0 until 12).joinToString(",") { """{"kind":"fact","text":"fact number $it"}""" }
        assertEquals(8, parseExtractedMemories("[$items]").size)
    }

    @Test
    fun `toRecord is deterministic and clamps importance`() {
        val memory = ExtractedMemory(MemoryKind.SEMANTIC_FACT, "The user lives in Tokyo", importance = 5.0)
        val record = memory.toRecord(sourceMessageId = MessageId("m1"))

        assertEquals(MemoryKind.SEMANTIC_FACT, record.kind)
        assertEquals("The user lives in Tokyo", record.text)
        assertEquals(1.0, record.importance, 1e-9)
        assertEquals(listOf(MessageId("m1")), record.sourceMessageIds)
        assertEquals("extraction", record.metadata["source"])
        assertEquals(
            "Re-extracting the same text must yield the same id (dedup via put-replace).",
            record.id,
            memory.copy(importance = 0.1).toRecord(MessageId("m2")).id,
        )
    }
}
