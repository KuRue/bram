# Roadmap

## Milestone 0 — reproducible build baseline

- Clean-room GitHub Actions build with JDK 17 and Android SDK 36.
- JVM tests, Android lint, and debug APK assembly as one required verification command.
- Checked-in Gradle wrapper with distribution checksum verification.
- Debug APK and test/lint reports retained as workflow artifacts.
- Install and smoke-test the CI-built APK on the S25 Ultra.

Exit criterion: green CI plus a successful install and launch of its debug APK on the S25 Ultra.

## Phase 1 — runnable control plane (this scaffold)

- Device profiler and execution-plan data model.
- Context budgeter and tool-loop orchestration.
- OpenAI-compatible endpoint storage and chat.
- Native inference process contract.
- Versioned Bram default identity, independent of model runtime.

## Phase 2 — first local vertical slice

- Vendor a pinned llama.cpp revision and record its license/build fingerprint.
- GGUF metadata parser and tokenizer-backed exact token counts.
- CPU ARM64 backend with cancellation, progress, metrics, and safe cleanup.
- Model import/download verification and resumable transfers.
- LFM2.5-2.6B Q4_0 as an initial reference model.

Exit criterion: install, load, chat, cancel, unload, and recover from a killed inference process on the S25 Ultra.

## Phase 3 — hardware planner

- Native Vulkan/OpenCL/Hexagon probes with tiny correctness tests.
- CPU/Adreno/Hexagon candidate plans and automatic probation runs.
- KV type, context, batch, thread, and offload tuning.
- Live RAM, storage reads, thermals, prompt/decode speed, and fallback reason UI.
- Storage-assisted mode with explicit speed estimate and opt-in.

Exit criterion: cached safe plan selection plus automatic fallback across a small Snapdragon, Tensor, and MediaTek device matrix.

## Phase 4 — durable agent

- Room-backed conversations, run journal, memory provenance, and FTS retrieval.
- Optional embeddings/vector index selected per device.
- Permissioned built-in tools and approval UI.
- Versioned skill packages with validation, drafts, activation, and rollback.
- WorkManager automation execution and result notifications.

## Phase 5 — curated acceleration and routing

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
