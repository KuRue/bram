package io.github.kurue.bram.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.core.agent.GeneratingMemoryExtractor
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.MessageId
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.SamplerSettings
import io.github.kurue.bram.core.domain.parseExtractedMemories
import io.github.kurue.bram.runtime.llamacpp.LlamaCppRuntime
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one on-device check that the extraction feature actually works against a real model: loads a
 * GGUF through the real llamacpp :inference service, then asks the model to pull memories from a
 * sample exchange, capturing the raw output so a zero-result can be diagnosed. The model is pushed
 * to the app's external files dir (`/sdcard/Android/data/<pkg>/files/qwen.gguf`) before the run.
 *
 * DEVICE-ONLY and IGNORED: CI has no model and no device. To run it manually, push a GGUF, install
 * both APKs, and `am instrument` this class. On Qwen2.5-0.5B-Instruct (Q4_K_M) the pipeline runs
 * end-to-end but the model picks the wrong content (it recalls the assistant's reply, not the
 * user's facts) — extraction quality scales with model size, so prefer ≥1.5B for useful recall.
 */
@Ignore("device-only; push a GGUF and run manually, see kdoc")
@RunWith(AndroidJUnit4::class)
class MemoryExtractionOnDeviceTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realModelExtractsMemories() = runBlocking {
        val modelFile = File(ctx.getExternalFilesDir(null), "qwen.gguf")
        assertTrue("Missing model at ${modelFile.absolutePath}; adb push it before running.", modelFile.exists())

        val client = LlamaCppServiceClient(ctx)
        try {
            client.processFailureListener = { msg -> println("INFERENCE_PROCESS_FAILURE: $msg") }

            val probe = withTimeout(60_000L) { client.probe() }
            assertTrue("Native runtime did not link: $probe", probe.optBoolean("nativeRuntimeLinked"))

            val record = LocalModelRecord(
                id = ModelId("local:qwen-test"),
                displayName = "Qwen2.5 0.5B",
                fileName = modelFile.name,
                contentUri = "",
                localPath = modelFile.absolutePath,
                fileSizeBytes = modelFile.length(),
                sha256 = "",
                ggufVersion = 0,
                architecture = "",
                quantization = "",
                trainedContextTokens = 0,
                layerCount = 0,
                hasChatTemplate = true,
            )
            // CPU load; the service runs the self-test and throws on failure, so reaching past this
            // means the model is loaded and validated.
            withTimeout(180_000L) { client.load(record, threads = 4) }

            val runtime = LlamaCppRuntime(record, client)
            val userText = "My name is Alice and I live in Tokyo. Always answer me in French."
            val assistantReply = "Enchanté, Alice ! Je vous répondrai en français."

            // Capture the raw model output for the extraction prompt (the real extractor hides it),
            // so a zero-result can be diagnosed: did the model return [], prose, malformed JSON, or
            // fail? Same prompt the production extractor uses.
            val extractionSystemPrompt =
                "Read the exchange and pull out only durable memories the assistant should keep for " +
                    "future turns. Output ONLY a JSON array, no prose, no code fence. Each element has " +
                    "the shape {\"kind\": \"fact\" | \"instruction\", \"text\": string, \"importance\": number}. " +
                    "\"fact\": a statement true later (the user's name, tools or languages they use, " +
                    "project details, deadlines, preferences about content). " +
                    "\"instruction\": a standing directive about how to respond (tone, format, things to " +
                    "always or never do). Skip small talk, the current task, and anything that only " +
                    "matters right now. If nothing durable, return []."
            val raw = StringBuilder()
            var failed: String? = null
            withTimeout(180_000L) {
                runtime.generate(
                    GenerationRequest(
                        messages = listOf(
                            ConversationMessage(role = MessageRole.SYSTEM, content = extractionSystemPrompt),
                            ConversationMessage(role = MessageRole.USER, content = "User:\n$userText\n\nBram:\n$assistantReply"),
                        ),
                        tools = emptyList(),
                        maxOutputTokens = 256,
                        sampler = SamplerSettings(temperature = 0f),
                        requestId = "raw-extract",
                    ),
                ).toList().forEach { event ->
                    when (event) {
                        is GenerationEvent.TextDelta -> raw.append(event.text)
                        is GenerationEvent.Failed -> failed = event.message
                        else -> Unit
                    }
                }
            }
            println("EXTRACTION_RAW_FAILED=${failed ?: "null"}")
            println("EXTRACTION_RAW=<<<${raw.toString()}>>>")
            val parsed = parseExtractedMemories(raw.toString())
            println("EXTRACTION_PARSED=${parsed.size}")
            parsed.forEach { println("EXTRACTED [${it.kind}] importance=${it.importance} : ${it.text}") }

            // Also exercise the real extractor end-to-end.
            val extracted = withTimeout(180_000L) {
                GeneratingMemoryExtractor().extract(
                    conversationId = ConversationId("extract-on-device"),
                    userMessage = ConversationMessage(id = MessageId("u1"), role = MessageRole.USER, content = userText),
                    assistantReply = assistantReply,
                    runtime = runtime,
                )
            }
            println("EXTRACTOR_RESULT=${extracted.size}")

            assertTrue("Extraction generation failed: $failed", failed == null)
            assertTrue("Model produced no output at all", raw.isNotBlank())
        } finally {
            runCatching { client.unload() }
            client.close()
        }
    }
}
