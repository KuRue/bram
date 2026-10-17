package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareRecipeTest {

    private val valid = """
        {
          "schema": "bram.hardware-recipe",
          "schemaVersion": 1,
          "id": "samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu",
          "version": 1,
          "maintainer": "Bram",
          "evidenceDate": "2026-10-17",
          "match": {
            "manufacturers": ["samsung"],
            "models": ["sm-s938u1", "sm-s938b"],
            "socs": ["sm8750", "snapdragon 8 elite"],
            "requireCpuFeatures": ["fp16", "dotprod", "i8mm"],
            "minAndroidApi": 35
          },
          "runtime": {
            "engineCommits": ["a94d563ed801d1da1b8c2432946de07d0231bb3d"],
            "settingsSchemaVersion": 1,
            "availableBackends": ["cpu"]
          },
          "model": {
            "architectures": ["lfm2"],
            "quantizations": ["q4_0"],
            "sha256": "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe",
            "sizeBytes": 1593894912,
            "minContextTokens": 4096
          },
          "workload": {
            "promptTokens": 512,
            "decodeTokens": 128,
            "contextDepth": 4096,
            "preference": "chat"
          },
          "proposal": {
            "backend": "cpu",
            "contextTokens": 8192,
            "threads": 4,
            "batchTokens": 512,
            "flashAttention": "auto",
            "kvCache": "f16",
            "loadMode": "auto",
            "cpuMask": "",
            "poll": -1
          },
          "evidence": {
            "method": "Bram benchmark: three repetitions, 180 s cooldown before the depth test",
            "agreement": 1.0,
            "promptTokPerSec": 100.8,
            "promptSpread": 27.6,
            "decodeTokPerSec": 27.9,
            "decodeSpread": 3.9,
            "depthDecodeTokPerSec": 20.9,
            "depthDecodeSpread": 1.8,
            "batteryCelsiusStart": 30.7,
            "batteryCelsiusEnd": 32.0
          },
          "limits": {
            "expiresAfterDays": 180,
            "revalidateOn": ["engine_commit", "model_hash", "settings_schema"],
            "incompatible": ["A Q8_0 KV cache was not measured on this device"]
          }
        }
    """.trimIndent()

    private fun recipeJson(
        proposalExtra: String = "",
        matchExtra: String = "",
        topExtra: String = "",
    ): String = valid
        .replace("\"poll\": -1", "\"poll\": -1$proposalExtra")
        .replace("\"minAndroidApi\": 35", "\"minAndroidApi\": 35$matchExtra")
        .replace("\"evidenceDate\": \"2026-10-17\",", "\"evidenceDate\": \"2026-10-17\",$topExtra")

    private fun refused(json: String): List<String> {
        val result = HardwareRecipe.parse(json)
        assertTrue(
            "expected refusal, got $result",
            result is RecipeParse.Refused,
        )
        return (result as RecipeParse.Refused).problems
    }

    private fun problems(json: String): List<String> = refused(json).let { problems ->
        assertTrue("expected at least one problem", problems.isNotEmpty())
        problems
    }

    @Test
    fun `a complete recipe parses with every field group`() {
        val result = HardwareRecipe.parse(valid)
        assertTrue("expected a parse, got $result", result is RecipeParse.Parsed)
        val recipe = (result as RecipeParse.Parsed).value
        assertEquals("samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu", recipe.id)
        assertEquals(1, recipe.version)
        assertEquals("Bram", recipe.maintainer)
        assertEquals("2026-10-17", recipe.evidenceDate)
        assertEquals(listOf("sm-s938u1", "sm-s938b"), recipe.match.models)
        assertEquals(setOf(RecipeCpuFeature.FP16, RecipeCpuFeature.DOTPROD, RecipeCpuFeature.I8MM), recipe.match.requireCpuFeatures)
        assertEquals(35, recipe.match.minAndroidApi)
        assertEquals(listOf("a94d563ed801d1da1b8c2432946de07d0231bb3d"), recipe.runtime.engineCommits)
        assertEquals(setOf(RecipeBackend.CPU), recipe.runtime.availableBackends)
        assertEquals(1593894912L, recipe.model.sizeBytes)
        assertEquals(4096, recipe.workload.contextDepth)
        assertEquals(RecipeProposal(
            backend = RecipeBackend.CPU,
            contextTokens = 8192,
            threads = 4,
            batchTokens = 512,
            flashAttention = FlashAttentionMode.AUTO,
            kvCache = KvCacheType.F16,
            loadMode = LoadMode.AUTO,
            cpuMask = "",
            poll = -1,
        ), recipe.proposal)
        assertEquals(100.8, recipe.evidence.promptTokPerSec!!, 0.0001)
        assertEquals(1.0, recipe.evidence.agreement!!, 0.0001)
        assertEquals(180, recipe.limits.expiresAfterDays)
        assertEquals(
            setOf(RecipeRevalidation.ENGINE_COMMIT, RecipeRevalidation.MODEL_HASH, RecipeRevalidation.SETTINGS_SCHEMA),
            recipe.limits.revalidateOn,
        )
    }

    @Test
    fun `a proposal with no fields parses as no changes`() {
        val json = valid.replace(
            Regex("\"proposal\": \\{[^}]*\\}", RegexOption.DOT_MATCHES_ALL),
            "\"proposal\": {}",
        )
        val recipe = (HardwareRecipe.parse(json) as RecipeParse.Parsed).value
        assertEquals(RecipeProposal(), recipe.proposal)
    }

    @Test
    fun `an unknown field is refused by name, not dropped`() {
        val problems = problems(recipeJson(proposalExtra = ", \"samplerTemperature\": 0.2"))
        assertEquals(listOf("proposal.samplerTemperature: unknown field"), problems)
    }

    @Test
    fun `a recipe cannot carry a prompt, a script, or a download`() {
        // The four things the plan says a recipe must never be able to do. Each is simply not in
        // the vocabulary, so each is refused by name.
        val forbidden = listOf(
            "\"systemPrompt\": \"you are helpful\"" to "recipe.systemPrompt",
            "\"script\": \"rm -rf\" " to "recipe.script",
            "\"downloadUrl\": \"https://example.com/lib.so\"" to "recipe.downloadUrl",
            "\"memoryLimitBytes\": 999999999999" to "recipe.memoryLimitBytes",
            "\"permissions\": [\"android.permission.INTERNET\"]" to "recipe.permissions",
            "\"agreementGate\": false" to "recipe.agreementGate",
        )
        for ((field, path) in forbidden) {
            val problems = problems(recipeJson(topExtra = " $field,"))
            assertEquals(listOf("$path: unknown field"), problems)
        }
    }

    @Test
    fun `numbers outside their bounds are refused with the bound stated`() {
        assertEquals(
            listOf("proposal.threads: must be between 1 and 256, got 0"),
            problems(recipeJson(proposalExtra = ", \"threads\": 0").replace("\"threads\": 4,", "")),
        )
        assertEquals(
            listOf("proposal.threads: must be between 1 and 256, got 1000"),
            problems(recipeJson(proposalExtra = ", \"threads\": 1000").replace("\"threads\": 4,", "")),
        )
        assertEquals(
            listOf("proposal.batchTokens: must be between 1 and 16384, got 0"),
            problems(recipeJson(proposalExtra = ", \"batchTokens\": 0").replace("\"batchTokens\": 512,", "")),
        )
        assertEquals(
            listOf("proposal.contextTokens: must be between 512 and 1048576, got 8"),
            problems(recipeJson(proposalExtra = ", \"contextTokens\": 8").replace("\"contextTokens\": 8192,", "")),
        )
    }

    @Test
    fun `a mistyped number is refused as a type error`() {
        assertEquals(
            listOf("proposal.threads: expected an integer"),
            problems(recipeJson(proposalExtra = ", \"threads\": \"four\"").replace("\"threads\": 4,", "")),
        )
    }

    @Test
    fun `an unknown backend names the ones that exist`() {
        val problems = problems(recipeJson(proposalExtra = ", \"backend\": \"tensor\"")
            .replace("\"backend\": \"cpu\",", ""))
        assertEquals(
            listOf("proposal.backend: unknown value \"tensor\" (expected one of: cpu, vulkan, opencl, hexagon)"),
            problems,
        )
    }

    @Test
    fun `every problem is reported, not just the first`() {
        val problems = problems(
            recipeJson(proposalExtra = ", \"threads\": 999, \"nonsense\": 1")
                .replace("\"threads\": 4,", "")
                .replace("\"batchTokens\": 512,", "\"batchTokens\": 0,"),
        )
        assertEquals(
            listOf(
                "proposal.batchTokens: must be between 1 and 16384, got 0",
                "proposal.nonsense: unknown field",
                "proposal.threads: must be between 1 and 256, got 999",
            ),
            problems.sorted(),
        )
    }

    @Test
    fun `a shape the app does not read is refused`() {
        val problems = problems(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        assertTrue(problems.any { it.startsWith("recipe.schemaVersion") })
        val wrongSchema = problems(valid.replace("bram.hardware-recipe", "bram.hardware-prompt"))
        assertEquals(listOf("recipe.schema: must be \"bram.hardware-recipe\""), wrongSchema)
    }

    @Test
    fun `a bad hash or date is refused`() {
        assertEquals(
            listOf("model.sha256: expected 64 lowercase hex characters"),
            problems(valid.replace(
                "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe",
                "E1A61BF9",
            )),
        )
        assertEquals(
            listOf("recipe.evidenceDate: expected yyyy-MM-dd, got \"17 October\""),
            problems(valid.replace("2026-10-17\",", "17 October\",")),
        )
    }

    @Test
    fun `a missing required section is named`() {
        val problems = problems(valid.replace("\"limits\": {", "\"notes\": {").replace("\"expiresAfterDays\": 180,", ""))
        assertTrue(problems.contains("recipe.notes: unknown field"))
        assertTrue(problems.contains("limits: required"))
    }

    @Test
    fun `a document that is not an object is refused, not thrown`() {
        val problems = problems("[1, 2, 3]")
        assertTrue(problems.first().startsWith("recipe: not a JSON object"))
    }

    @Test
    fun `an oversized document is refused before parsing`() {
        val problems = problems(" " + valid + " ".repeat(MAX_RECIPE_DOCUMENT_BYTES))
        assertTrue(problems.first().startsWith("recipe: document is"))
    }

    @Test
    fun `an evidence rate above the ceiling is refused`() {
        assertEquals(
            listOf("evidence.promptTokPerSec: must be between 0.0 and 100000.0, got 100001.0"),
            problems(valid.replace("\"promptTokPerSec\": 100.8", "\"promptTokPerSec\": 100001")),
        )
    }

    @Test
    fun `a proposal may name the NPU without claiming it is faster`() {
        val recipe = (HardwareRecipe.parse(
            valid.replace("\"backend\": \"cpu\"", "\"backend\": \"hexagon\""),
        ) as RecipeParse.Parsed).value
        assertEquals(RecipeBackend.HEXAGON, recipe.proposal.backend)
        // Speed is evidence, not vocabulary: the format has no "fastest" or "preferred" field.
        assertTrue(problems(valid.replace("\"maintainer\": \"Bram\"", "\"maintainer\": \"Bram\", \"fastest\": true"))
            .contains("recipe.fastest: unknown field"))
    }

    // ------------------------------------------------------------------ the index

    private val index = """
        {
          "schema": "bram.hardware-recipes.index",
          "schemaVersion": 1,
          "source": { "id": "bram.official", "name": "Bram official recipes", "kind": "official" },
          "revision": "2026-10-17.1",
          "generatedAt": "2026-10-17",
          "recipes": [
            {
              "id": "samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu",
              "version": 1,
              "file": "samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu.json",
              "sha256": "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an index parses with its source and hashes`() {
        val parsed = RecipeIndex.parse(index)
        assertTrue("expected a parse, got $parsed", parsed is RecipeParse.Parsed)
        val value = (parsed as RecipeParse.Parsed).value
        assertEquals("bram.official", value.source.id)
        assertEquals(RecipeSourceKind.OFFICIAL, value.source.kind)
        assertEquals("2026-10-17.1", value.revision)
        assertEquals(1, value.recipes.size)
        assertEquals("samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu.json", value.recipes.first().file)
    }

    @Test
    fun `an index refuses a path that leaves the source`() {
        val problems = (RecipeIndex.parse(
            index.replace(
                "\"file\": \"samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu.json\"",
                "\"file\": \"../elsewhere.json\"",
            ),
        ) as RecipeParse.Refused).problems
        assertEquals(listOf("index.recipes[0].file: expected a plain file name inside the source"), problems)
    }

    @Test
    fun `an index refuses two entries with the same id`() {
        val duplicated = """
            {
              "schema": "bram.hardware-recipes.index",
              "schemaVersion": 1,
              "source": { "id": "bram.official", "name": "Bram official recipes", "kind": "official" },
              "revision": "2026-10-17.1",
              "generatedAt": "2026-10-17",
              "recipes": [
                {
                  "id": "samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu",
                  "version": 1,
                  "file": "one.json",
                  "sha256": "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe"
                },
                {
                  "id": "samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu",
                  "version": 1,
                  "file": "two.json",
                  "sha256": "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe"
                }
              ]
            }
        """.trimIndent()
        val problems = (RecipeIndex.parse(duplicated) as RecipeParse.Refused).problems
        assertEquals(listOf("index.recipes: duplicate recipe ids"), problems)
    }

    @Test
    fun `an index hash that is not a hash is refused`() {
        val problems = (RecipeIndex.parse(index.replace("\"sha256\": \"e1a61bf9", "\"sha256\": \"zzz"))
            as RecipeParse.Refused).problems
        assertEquals(listOf("index.recipes[0].sha256: expected 64 lowercase hex characters"), problems)
    }
}
