# Roadmap

Where Bram is going next. What each finished milestone delivered, with its measurements and the
dead ends on the way, is in the [milestone history](docs/history/MILESTONES.md). The current
feature status is in the [project overview](docs/PROJECT_OVERVIEW.md).

## Done

| Milestone | Delivered |
|---|---|
| 0 — Build baseline | Clean-room CI, checked-in wrapper, debug APK artifacts |
| Foundation | Device profiler, context budgeter, tool loop, endpoint storage, versioned identity |
| 1 — Local CPU alpha | Pinned llama.cpp, GGUF import with SHA-256, chat templates, exact token counts, cancel/unload, recovery from a killed inference process |
| 2 — Accelerator validation | Teacher-forced agreement with CPU; Hexagon NPU validated, Vulkan rejected on Adreno 830 |
| 3–6 — Usable assistant | Durable conversations, collapsed reasoning and tool activity, background runs, retry/edit/copy/Markdown |
| 7 — Runtime performance | Flash attention and KV-cache type, KV reuse across turns, batch tuning |
| 8, 8b — Model profiles | Profiles as the unit a person picks, provisioned from the file and measured on save |
| 8c — OpenCL for Adreno | Opt-in OpenCL backend, validated at 96% agreement |
| 8d — Auto-configure | Measure every backend, pick the fastest that agrees with CPU, and show why |
| 9 — Permissions | An approval gate that asks, remembers per scope, and times out into a refusal |
| 10 — Local tool calling | Tools through the template, lazy grammars, the real parser, fenced recovery |
| 11 — Android tools | Files, web, clipboard, notifications, alarms, intents, contacts, calendar |
| 12 — Termux | `termux_exec` through `RUN_COMMAND`, with its three ordinary failures reported as such |
| 13 — Task queue | Named tasks in their own conversations, scheduled, deferred until a model loads |
| 14 — Compaction and memory | Summaries instead of dropped turns, SQLite/FTS memory and run journal, optional embeddings |
| 15 — Remote MCP | Streamable HTTP with pinned server identity; stdio is impossible on Android and documented as such |
| 16 — Skills and automations | Versioned skills with drafts, activation, and rollback; cron automations on the task queue |
| 17 — Routing (most of it) | Per-turn routing with fallback, per-conversation privacy, the Responses adapter, LiteRT-LM packages |
| 18 — Status notification | A foreground service for the loaded model's lifetime, with its phase and an opt-in completion alert |
| 19 — Device adaptation (phases 1–3) | Correctness-gated tuning sweeps, KleidiAI CPU kernels, measurement fingerprints, import compatibility report, on-device quant conversion, benchmarks |
| Harness overhaul M0–M6 | Mock-server test bed, persisted tool history, result budgets, gate polish, tools v2, skills v2, streaming endpoints ([plan and results](docs/HARNESS_OVERHAUL.md)) |
| First-run guide | Phone compatibility scan and a model recommendation for a fresh install (#58) |

## Planned product work

The owner’s requested navigation, profile/import UI, adaptive configuration, Home activity,
automations, child agents, hardware recipes, and mandatory Hexagon release packaging are
planned in [Product and execution plan](docs/PRODUCT_PLAN.md). This is a proposal with delivery
slices and acceptance gates; the application changes have not shipped.

## Now — finish device adaptation (Milestone 19)

Design: [DEVICE_ADAPTATION.md](docs/DEVICE_ADAPTATION.md).

- **Converted-Q8_0 replay hang.** A model converted on-device to Q8_0 intermittently hangs in
  teacher-forced replay under non-default configs (strict CPU masks, explicit poll, alternate load
  modes), on arm64 and x86_64 at the current pin. The candidate timeout, process restart, and
  failure note contain every observed instance; the cause is not found.
- **Conversion blocks chat.** Quant conversion runs on the inference service's single executor, so
  a chat turn waits until it finishes.
- **More device classes.** The exit criterion is four device classes; only Snapdragon has been
  measured. Dimensity, Tensor, and Exynos need hardware Bram does not have, and with them Vulkan
  measured per GPU vendor and a LiteRT-LM NPU path.
- **Efficiency and battery modes** (phase 5), and the **opt-in report** (phase 6).
- **SVE KleidiAI kernels** for CPUs with 128-bit SVE, such as the Snapdragon 8 Elite.

## Next

- **Auto-configure can retry forever.** The KV-cache and attention steps time a reference padded
  with 1,024 tokens against the 30-second limit meant for an unpadded one, and slow references are
  retried without a cap. On the emulator it parked 10 times in 28 minutes; a slow phone would too.
  Scale the limit to the prompt and cap the retries.
- **Explain excluded backends in auto-configure.** The S25 Ultra scan enumerates Vulkan, but
  `autoConfigure()` explicitly excludes it because of validation reliability. Show that policy
  exclusion instead of an empty Backends section; it is not a fresh validation failure.
- **First-run guide follow-ups.** Keep the example model families in `ModelAdvisor.tiers` current;
  lead with "Connect a server" when no local model can run. (The arm64 recommendation was confirmed
  on the S25 Ultra on 2026-10-17: 12 GB → about 4B comfortable, 8B at a push; ARMv8.6 with i8mm.)
- **The rest of Milestone 17.** Device-specific compiled caches for LiteRT-LM, speculative
  decoding, and task-specific worker models.
- **Retire the legacy HTTP reader.** OkHttp has been the default since #49/#50; the
  `HttpURLConnection` rollback in `RemoteTransport` was meant to last one slice.
- **Certificate pinning** for remote endpoints, the main open item in [SECURITY.md](SECURITY.md).
- **Test coverage.** `platform:android` (Keystore, conversation storage, memory database) has no
  unit tests. Decide whether the device suite should package the x86_64 runtime
  (`BRAM_EMULATOR_ABI=true`) so emulator runs cover local inference too.
- **Smaller UI files.** `MainViewModel.kt` and `BramApp.kt` are about 5,000 lines each. Split them
  by screen as features touch them, not in one pass.

## Release build requirement

- **Include Hexagon in normal ARM64 phone releases.** Requested by the owner on 2026-10-17.
  Verify the pinned Snapdragon toolchain/SDK route, enforce `bram.requireHexagon=true`, check
  packaged host/DSP artifacts and notices, and validate the actual APK on the S25 Ultra.
  Current releases still omit it; see the [build plan](docs/PRODUCT_PLAN.md#7-hexagon-included-in-release-apks).

## Needs a decision from the owner

- **A license.** None has been chosen, which blocks outside contributions.
- **In-app model downloads.** The first-run guide says what to look for, but the user still fetches
  the file in a browser.

## Needs the real phone

These cannot be checked on the emulator and are batched for the S25 Ultra: Hexagon NPU correctness
and speed, Adreno OpenCL and Vulkan, real-model speed, thermals, and memory, the ARMv8.2
fp16/dotprod requirement check, and a cancelled benchmark leaving the model usable.

## Testing matrix

- Pure JVM tests for context selection, routing, planning, and tool loops.
- Contract fixtures for OpenAI-compatible servers with missing/extra fields.
- Android tests for Keystore migration, process death, WorkManager, and storage permissions.
- Native correctness tests before performance tests for every backend/operator path.
- Soak tests under low memory, low battery, thermal throttling, app backgrounding, and cancellation.
- Tool permission tests that assert a side effect cannot be reached without a recorded decision.
- Termux integration tests for the three ordinary failure modes: not installed, external apps not
  allowed, and permission refused.
