# Roadmap

## Milestone 0 — reproducible build baseline (complete)

- Clean-room GitHub Actions build with JDK 17 and Android SDK 36.
- JVM tests, Android lint, and debug APK assembly as one required verification command.
- Checked-in Gradle wrapper with distribution checksum verification.
- Debug APK and test/lint reports retained as workflow artifacts.
- Install and smoke-test the CI-built APK on the S25 Ultra.

Exit criterion: green CI plus a successful install and launch of its debug APK on the S25 Ultra.

Result: complete. PR #1 was squash-merged into `main`; its CI-built APK installed and launched on
the S25 Ultra.

## Foundation — runnable control plane (complete)

- Device profiler and execution-plan data model.
- Context budgeter and tool-loop orchestration.
- OpenAI-compatible endpoint storage and chat.
- Native inference process contract.
- Versioned Bram default identity, independent of model runtime.

## Milestone 1 — local CPU alpha (complete)

- Vendor a pinned llama.cpp revision and record its license/build fingerprint.
- Model-first Chat/Models UI; remote endpoints move under Settings.
- Persisted Storage Access Framework GGUF catalog, bounded metadata parser, and SHA-256 verification.
- GGUF chat-template application and tokenizer-backed exact token counts.
- CPU ARM64 backend with cancellation, progress, metrics, and safe cleanup.
- Model import verification. Built-in downloads and resumable transfers remain deferred.
- LFM2.5-2.6B Q4_0 as an initial reference model.

Exit criterion: install, load, chat, cancel, unload, and recover from a killed inference process on the S25 Ultra.

Result: complete. All ten acceptance steps passed on the S25 Ultra, including recovery from a
killed inference process. Getting there required two fixes: llama.cpp's native logs were being
discarded, hiding the real failure; and the model was handed to native code as a `/proc/self/fd`
path, which scoped storage refuses to let it re-open. Imports are now copied into app-private
storage and loaded from a real path.

## Milestone 2 — accelerator validation (complete for Vulkan and Hexagon)

- Vulkan and Hexagon backends compiled for ARM64 and packaged.
- Correctness measured against CPU output rather than benchmarked.
- Per-backend selection and layer bisection in the app.

Exit criterion: an accelerator is reported as validated only when it reproduces the CPU reference.

Result on the S25 Ultra with `LFM2.5-2.6B-Q4_0`:

| Backend | Offload | Agreement with CPU | Verdict |
|---|---|---|---|
| Hexagon NPU (HTP v79) | 31/31 layers | 23/24 (95.8%) | validated, 1.5–1.9x CPU |
| Vulkan (Adreno 830) | 1 layer | 18/24 (75%) | not validated |
| Vulkan (Adreno 830) | 7+ layers | all-zero logits | not validated |

Vulkan reports no error while returning garbage, which is why validation compares output rather
than measuring speed: a speed-only check called the broken backend working.

The comparison is teacher-forced — both backends are fed the same reference tokens and asked only
for the next-token prediction at each position — because free-running generation lets one differing
token derail everything after it, making a small numerical difference indistinguishable from a
broken kernel.

Deferred to a later milestone: OpenCL, LiteRT, automatic plan selection and probation runs,
storage-assisted mode, and a multi-vendor device matrix. KV and batch tuning became Milestone 7.

## Milestones 3 to 6 — a usable local assistant (complete)

Delivered as four smaller milestones once local CPU inference was working, on the principle that
Bram had to be pleasant to use before it could be extended.

- **Durable conversations.** One JSON file per thread with a rebuildable index, multiple threads,
  titles derived from the first message, restored on launch. Survives the app being killed.
- **Agentic transcript.** Messages carry ordered activity entries for reasoning and tool calls,
  rendered as collapsed lines that expand on tap and persisted with the conversation. Reasoning is
  a per-model setting, off by default, because a reasoning model can spend paragraphs deciding how
  to say hello.
- **Background runs.** Agent work belongs to an application scope and holds a foreground service,
  so a run continues and writes its reply when the user leaves the app.
- **Chat quality of life.** Retry, edit-and-resend, copy, Markdown rendering, auto-scroll.

Also in this stretch: emulator support (an opt-in `x86_64` ABI), which exposed two robustness bugs
a phone would also hit — a CPU load failing because an unusable GPU was merely present, and a model
being unusable because its Jinja template could not be rendered.

## Milestone 7 — runtime performance (in progress)

Promotes the deferred "KV/batch tuning" line above to a milestone of its own, because measurement
showed it is the largest user-visible cost left. Every turn built a fresh `llama_context` and
re-decoded the whole prompt, so at the measured 16.2 tok/s prompt speed a conversation grown to
2,000 tokens spent about two minutes before its first token, worsening with every turn.

- **KV reuse across turns.** Keep the context alive between turns, keep the longest common token
  prefix, and decode only what is new.
- **KV cache quantization.** `type_k`/`type_v` at `q8_0` roughly halves KV memory. The payoff is
  context length within a phone's RAM rather than speed.
- **FlashAttention.** Per-backend rather than global: supported on CPU, and to be confirmed on the
  Hexagon HTP path before being enabled there. Also a practical prerequisite for quantized KV.
- **Batch tuning.** `n_ubatch` is pinned at 128; both it and `n_batch` should follow measured
  prompt throughput instead of a fixed guess.

Exit criterion: each optimization reproduces the CPU reference under teacher forcing before it is
reported as working. A prefix-matching bug produces plausible wrong output rather than a crash,
which is the failure mode that already made a broken Vulkan backend look healthy.

## Milestone 8 — model profiles (not started)

Replaces the model card with saved configurations. Per-model preferences already exist as fields on
the GGUF record; this makes them a named record instead, many profiles to one file.

- A profile owns a name, a GGUF reference, sampler settings, context size, backend, reasoning
  on/off, and a system prompt.
- Sampler settings become per-profile rather than fixed. Only temperature currently crosses the
  process boundary; `top_k` is pinned at 40 and `top_p` at 0.95 in the JNI layer.
- The status pill selects a profile rather than a model.

Ordering note: Milestone 7 lands first so profiles have the KV and attention settings to expose,
rather than needing a second pass to add them.

## Milestone 9 — durable agent (not started)

- Room-backed run journal, memory provenance, and FTS retrieval.
- Optional embeddings/vector index selected per device.
- Permissioned built-in tools beyond the single read-only `device_status`, and an approval UI.
- Versioned skill packages with validation, drafts, activation, and rollback.
- A task queue with scheduled execution and result notifications. `AgentTaskService` is the
  foundation; there is no queue or per-task UI yet.

## Milestone 10 — curated runtimes and routing (not started)

- LiteRT-LM packages and device-specific compiled caches.
- OpenAI Responses adapter where supported.
- Capability/quality/latency/battery-aware routing.
- Per-conversation privacy and remote-fallback policies.
- Optional speculative decoding and task-specific worker models.

## Testing matrix

- Pure JVM tests for context selection, routing, planning, and tool loops.
- Contract fixtures for OpenAI-compatible servers with missing/extra fields.
- Android tests for Keystore migration, process death, WorkManager, and storage permissions.
- Native correctness tests before performance tests for every backend/operator path.
- Soak tests under low memory, low battery, thermal throttling, app backgrounding, and cancellation.
