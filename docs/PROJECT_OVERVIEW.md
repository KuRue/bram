# Project overview

Bram is an Android-first local AI runtime and agent harness. It profiles the phone, measures
execution plans, and supports CPU, GPU, NPU, and remote endpoints. Runtime availability and
validation results determine which execution plans can be selected.

The model is only one component. Bram is built around tool use, durable conversations, context
management, hardware-aware inference, versioned skills, scheduled work, and optional
OpenAI-compatible remote models behind an explicit privacy policy.

This page is the detailed status reference. [ARCHITECTURE.md](../ARCHITECTURE.md) explains how it
is built, [ROADMAP.md](../ROADMAP.md) what is next, and the
[milestone history](history/MILESTONES.md) how each piece got here.

## Runtime behavior

- **Accelerator validation.** A backend must reproduce the CPU reference before
  Bram uses it. This detected a GPU backend that initialized successfully but returned incorrect
  output.
- **Hardware-aware planning.** Candidate plans account for model weights, KV cache, compute
  buffers, backend repacking, current memory pressure, and a safety reserve.
- **Process isolation.** Native inference lives in a separate Android process, so a driver or backend
  crash does not have to kill the conversation.
- **Runtime-neutral agent.** The same tool loop runs on llama.cpp, LiteRT-LM, or an
  OpenAI-compatible endpoint.
- **Context as a budget.** The transcript stays canonical; summaries, retrieved memories, and KV
  caches are replaceable projections of it.
- **Remote routing.** Conversation privacy settings determine whether remote execution and
  fallback are allowed.

## Status

Bram is **alpha** software, version `0.3.0-alpha01`. Local chat, conversations, tools, memory,
skills, and automations work. Hardware results come from one phone, a Galaxy S25 Ultra
(Snapdragon 8 Elite: Hexagon HTP v79, Adreno 830); the `s25u_sim` emulator covers the UI and the
agent harness against a scripted mock server.

### Using Bram

| Area | State |
|---|---|
| First-run guide: phone scan and a model recommendation | Working |
| Chat: streaming, Markdown, retry, edit-and-resend, copy, Stop | Working |
| Durable conversations, history, restore on launch | Working |
| Collapsed reasoning and tool activity in the transcript | Working |
| Runs that continue while Bram is in the background | Working |
| Status notification for the loaded model, opt-in completion alert | Working |

### Models and runtimes

| Area | State |
|---|---|
| GGUF import, including split models, with metadata validation and SHA-256 | Working |
| Profiles: per-model backend, context, sampler, reasoning, KV and attention settings | Working |
| Auto-configure: eligible backends measured against CPU, fastest agreeing one chosen | Working; Vulkan is currently excluded by policy |
| Tuning sweeps (threads, CPU mask, poll, load mode, batch), each correctness-gated | Working |
| Benchmarks: prompt, decode, decode at depth, energy, sustained thermal curve, memory-bandwidth ceiling | Working |
| Import compatibility report (CPU/GPU/NPU offload share per file) | Working |
| On-device quant conversion to Q4_0 / Q8_0 | Working; a converted Q8_0 model can hang in replay under non-default configs (contained by a timeout) |
| OpenAI-compatible endpoints: Chat Completions and Responses, streaming, tool calls, reasoning | Working |
| LiteRT-LM packages (`.litertlm`) on CPU or GPU, with benchmarks | Ships in the build; not validated across devices |

### Backends

| Backend | Evidence |
|---|---|
| llama.cpp CPU (ARMv8.2 fp16 + dotprod required on arm64) | Validated on device. KleidiAI kernels took the LFM2.5-2.6B Q4_0 prompt speed from about 16 to about 86 tok/s. |
| Hexagon NPU | Validated at 95.8% agreement with CPU. It was 1.5–1.9x the old CPU path; against the KleidiAI CPU it is slower on that model, and auto-configure picks the CPU. Needs Qualcomm's Hexagon SDK at build time, so release builds do not include it. |
| OpenCL (Adreno) | Validated at 96% agreement (Qwen3.5 Q4_0, Milestone 8c), when it was 1.11x the CPU of the time. Re-measured 2026-10-17 against the KleidiAI CPU on LFM2.5-2.6B Q4_0, both from one build with the backend the only difference, it loses on every axis: 32.6 vs 100.8 prompt, 19.1 vs 27.9 decode, 13.3 vs 20.9 decode-at-4K tok/s. Opt-in at build time; needs a vendor library pulled from a device, so CI cannot build it. |
| Vulkan (Adreno 830) | Builds and runs, but fails validation: 75% agreement with one offloaded layer, all-zero logits past about seven, with no error reported. Not used by default. |

### The agent

| Area | State |
|---|---|
| Tool loop with schema validation, per-tool timeouts, parallel read-only calls | Working |
| Approval gate: Ask when needed / Always ask / Auto-approve, scoped grants, timeouts | Working |
| Local tool calling for models whose own template renders tools | Working; others are told plainly that tools are off |
| 28 built-in tools: device, notes and files, web, clipboard, notifications and reminders, intents, contacts, calendar, weather, memory, skills, a document folder, screen reading and interaction, Termux | Working; calendar, contacts, and screen tools need the user's consent |
| Remote MCP servers (streamable HTTP) | Working; stdio servers cannot run on Android |
| Context compaction, SQLite/FTS memory, optional embedding recall | Working |
| Versioned skills with drafts, review diff, activation, rollback, disable | Working |
| Task queue and cron automations | Working |
| Per-chat profile choice with thinking and tool-approval overrides; privacy classes, fallback | Working |
| Endpoint API keys and custom headers encrypted with Android Keystore | Working |

## Bram's default identity

The harness supplies a versioned `AgentIdentity` to each run. Version `bram/0.1` is intentionally
small: Bram is direct, tool-aware, evidence-conscious, and prefers private local execution when
policy and capability allow.

The identity is isolated from model adapters in
[BramDefaults.kt](../app/src/main/kotlin/io/github/kurue/bram/app/BramDefaults.kt). Increment its
version whenever behaviorally meaningful prompt text changes, so prompt and KV caches can
invalidate safely.
