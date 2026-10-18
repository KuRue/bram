package io.github.kurue.bram.core.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/** Untrusted documents must be refused, never crash; identity fields are required, not optional. */
class RecipeMissingIdentityTest {
    private fun recipe(missing: String, value: String? = null): String {
        val fields = mutableListOf(
            "\"schema\": \"bram.hardware-recipe\"",
            "\"schemaVersion\": 1",
            "\"id\": \"example-device-model-cpu\"",
            "\"version\": 1",
            "\"maintainer\": \"Someone\"",
            "\"evidenceDate\": \"2026-10-18\"",
        )
        if (value == null) fields.removeAll { it.startsWith("\"$missing\"") } else {
            fields.replaceAll { if (it.startsWith("\"$missing\"")) "\"$missing\": $value" else it }
        }
        return """
            { ${fields.joinToString(",")},
              "match": { "socs": ["sm8750"], "requireCpuFeatures": [] },
              "runtime": { "engineCommits": ["a94d563ed801d1da1b8c2432946de07d0231bb3d"], "settingsSchemaVersion": 1, "availableBackends": ["cpu"] },
              "model": { "architectures": ["lfm2"], "quantizations": ["q4_0"] },
              "workload": { "promptTokens": 512, "decodeTokens": 128, "contextDepth": 0, "preference": "chat" },
              "proposal": { "threads": 4 },
              "evidence": { "method": "test" },
              "limits": { "revalidateOn": [] }
            }
        """.trimIndent()
    }

    @Test
    fun `a document missing an identity field is refused, not thrown`() {
        for (missing in listOf("schema", "schemaVersion", "id", "version", "maintainer", "evidenceDate")) {
            val outcome = runCatching { HardwareRecipe.parse(recipe(missing)) }
            val result = outcome.getOrElse { error ->
                throw AssertionError("missing $missing threw ${error::class.simpleName}: ${error.message}")
            }
            assertTrue("missing $missing was accepted", result is RecipeParse.Refused)
        }
    }

    @Test
    fun `a number written as a string is refused, the same way the schema refuses it`() {
        val withQuotedRate = recipe("__none__").replace(
            "\"evidence\": { \"method\": \"test\" }",
            "\"evidence\": { \"method\": \"test\", \"agreement\": \"1.0\" }",
        )
        val refused = HardwareRecipe.parse(withQuotedRate)
        assertTrue("a quoted number was accepted", refused is RecipeParse.Refused)
        assertTrue((refused as RecipeParse.Refused).problems.any { it.startsWith("evidence.agreement") })
    }

    @Test
    fun `a document with a non-string identity field is refused`() {
        for ((field, value) in listOf("id" to "7", "maintainer" to "[1]", "evidenceDate" to "17")) {
            val result = HardwareRecipe.parse(recipe(field, value))
            assertTrue("$field=$value was accepted", result is RecipeParse.Refused)
        }
    }
}
