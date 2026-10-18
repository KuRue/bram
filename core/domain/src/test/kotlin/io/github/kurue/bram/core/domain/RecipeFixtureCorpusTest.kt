package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fixture corpus in `recipes/`, read through Bram's own parser.
 *
 * The JSON Schemas are the format's public face and this parser is its enforcement, so both are
 * checked against one corpus: `recipes/validate.py` asserts what the schemas accept and refuse,
 * this test asserts the parser does the same. Each invalid case names the field it must be refused
 * at, in the words both implementations use, so a rule that drifts on either side fails here.
 */
class RecipeFixtureCorpusTest {

    private val fixtures: File = run {
        var directory = File(".").absoluteFile
        while (directory.parentFile != null) {
            if (File(directory, "recipes/fixtures").isDirectory) return@run File(directory, "recipes/fixtures")
            directory = directory.parentFile!!
        }
        throw AssertionError("recipes/fixtures not found above ${File(".").absolutePath}")
    }

    /** Null when the document is accepted, otherwise every problem the parser named. */
    private fun parse(kind: String, json: String): List<String>? = when (kind) {
        "recipe" -> when (val result = HardwareRecipe.parse(json)) {
            is RecipeParse.Parsed -> null
            is RecipeParse.Refused -> result.problems
        }

        "index" -> when (val result = RecipeIndex.parse(json)) {
            is RecipeParse.Parsed -> null
            is RecipeParse.Refused -> result.problems
        }

        else -> throw AssertionError("unknown fixture kind $kind")
    }

    private fun cases(kind: String, validity: String): List<File> {
        val directory = File(fixtures, "$kind/$validity")
        assertTrue("no fixtures at ${directory.path}", directory.isDirectory)
        return directory.listFiles().orEmpty()
            .filter { if (validity == "valid") it.extension == "json" else it.isDirectory }
            .sortedBy(File::getName)
    }

    @Test
    fun `every valid fixture is accepted`() {
        for (kind in listOf("recipe", "index")) {
            for (file in cases(kind, "valid")) {
                val problems = parse(kind, file.readText())
                assertTrue("${file.name} was refused: $problems", problems == null)
            }
        }
    }

    @Test
    fun `every invalid fixture is refused at the field the corpus names`() {
        for (kind in listOf("recipe", "index")) {
            for (case in cases(kind, "invalid")) {
                val expectation = File(case, "expect.txt").readText().trim()
                val documents = case.listFiles().orEmpty().filter { it.extension == "json" }
                assertEquals("${case.name}: exactly one document per case", 1, documents.size)
                val problems = parse(kind, documents.single().readText())
                assertTrue("${case.name} was accepted, expected a problem at $expectation", problems != null)
                assertTrue(
                    "${case.name}: expected a problem at $expectation, got $problems",
                    problems.orEmpty().any { it.startsWith(expectation) },
                )
            }
        }
    }

    @Test
    fun `the corpus keeps a case for each promise the format makes`() {
        // Guards against the corpus quietly losing its coverage: the things a recipe may never
        // carry, the bounds, the identity fields, and the index's trust and path rules.
        val recipes = cases("recipe", "invalid").map(File::getName)
        val indexes = cases("index", "invalid").map(File::getName)
        for (expected in listOf(
            "forbidden-top-level-field",
            "proposal-unknown-field",
            "threads-out-of-range",
            "rate-above-ceiling",
            "unknown-backend",
            "bad-model-hash",
            "missing-maintainer",
            "missing-schema-version",
            "match-without-models-or-socs",
        )) {
            assertTrue("no recipe fixture named $expected (have $recipes)", expected in recipes)
        }
        for (expected in listOf("bad-hash", "source-without-kind", "file-escapes-source", "unknown-field")) {
            assertTrue("no index fixture named $expected (have $indexes)", expected in indexes)
        }
    }
}
