# Session handoff

Last updated: 2026-08-14 (continuation-ready)

## Current source of truth

| Item | Value |
|---|---|
| Repository | Private `KuRue/bram` |
| main | [`51c8009`](https://github.com/Kurue/bram/commit/51c8009) - MTP/DFlash test-model search documented |
| Target phone | Samsung `SM-S938U1` (Snapdragon 8 Elite, HTP v79), 10.9 GB app-visible RAM |
| Test emulator | AVD `Pixel_9a`, x86_64, 6 GB RAM / 16 GB storage |
| Reference models | `LFM2.5-2.6B-Q4_0.gguf` (phone, tool-capable), `Qwen3.5-0.8B-Q4_0.gguf` (emulator) |
| Phone's model inventory | Qwen3.5-0.8B (Q4_0 + converted Q8_0), LFM2.5-2.6B-Q4_0, `Nvidia-Nemotron-3.5-Lightning-30B-A3B` (edcb5d4650796ed2fb412498.gguf, ~17GB, has nextn heads, runs ~0.5 tok/s decode) |
| llama.cpp pin | `a94d563e` (2026-08-13, bumped from 132753bf for 31 upstream commits including Lightning Indexer fused ops, FA vectorize, LFM2 tool-call fix; did NOT fix the mask/8-threads arm64 hang) |
| Build note | The Hexagon skel ExternalProject now needs Ninja on PATH: `export PATH=/home/s14/Android/Sdk/cmake/3.30.5/bin:$PATH` before building in WSL. Emulator builds need `BRAM_EMULATOR_ABI=true`. |

## Session continuation (start here)

- **NEXT UP: MoE expert streaming (feature branch `feat/expert-streaming`).** Building the ability
  to run MoE models several times larger than RAM, losslessly, by reading only each token's routed
  experts from flash instead of resident-loading the whole file — the technique
  [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) uses to run DeepSeek V4 Flash (91 GB) at
  ~1 tok/s on a 12 GB phone (its `docs/seam.md` + `docs/architecture.md` are the reference; we
  reimplement from the public seam, no copied code). Why Bram needs it: Bram uses vanilla llama.cpp
  mmap, which on a >>RAM model thrashes the page cache and **thermally deadlocks** — proven this
  session (DeepSeek auto-configure never completed one measurement; `pausing for cooldown` loop).
  - **Seam verified on our pin `a94d563e`:** routing node `ffn_moe_topk` (llama-graph.cpp:2034,
    cb appends `-<il>`); expert tensors `blk.%d.ffn_{gate,up,down}_exps`; `cb_eval`/`cb_eval_user_data`
    in `llama_context_params`; `gguf_get_data_offset`/`gguf_get_tensor_offset` + `no_alloc`;
    `ggml_compute_forward_mul_mat_id` (ggml-cpu.c:1534) for the overlap hook.
  - **P0 DONE (commit `10c99c1` on `feat/expert-streaming`, not pushed):** `expert_stream.{h,cpp}` —
    `GgufOffsetMap` (parse each shard header `no_alloc`, record every tensor's `(shard, offset,
    nbytes)`, serve `pread`) + arch recipe registry (deepseek4, qwen3moe, qwen35moe, deepseek2).
    Compiles into `bram_llama` (added to CMake sources).
  - **Scope chosen: full serial + `--overlap`.** Overlap needs a ~25-line `ggml_cpu_set_expert_ready_hook`
    in `ggml_compute_forward_mul_mat_id` — apply via a `PATCH_COMMAND` on the FetchContent llama.cpp
    (keeps our exact pin + one commit), CMake probes for `BMOE_HAVE_EXPERT_READY_HOOK`; serial path
    must still build on stock upstream.
  - **Remaining phases (tasks P1–P4 + overlap):** P1 capture warm-up (set `use_extra_bufts=false`,
    build the offset map from **shard paths** — needs extending native `load` + `NativeLlamaBridge`/
    `LlamaCppRuntime` to pass the parts list, not just the primary path; install `cb_eval`; one warm-up
    decode scans `node->src[]` and records expert `ggml_tensor*` by `(il,suffix)`; checkpoint: log
    "captured N experts, offsets resolved" on DeepSeek). P2 serial reads + `->data` rebind, gated by
    byte-identical greedy output. P3 hot-expert LRU cache (RAM budget) + `--dense-weights anon` (copy
    always-used weights to anon memory so the OS can't reclaim them mid-gen — their ~3.2× lever).
    P-overlap hook. P4 JNI/Kotlin flag + telemetry (flash bytes/token, cache hit, tok/s) + validate
    DeepSeek → ~1 tok/s.
  - **Gate model:** pull **Qwen3.5-MoE-0.87B** (arch `qwen35moe`, already in the recipe) onto the phone
    — small enough to run resident, so streamed output can be compared byte-for-byte against resident.
  - **Already staged:** DeepSeek V4 Flash 0731 (IQ2_M, 84.7 GB, 3 shards) is **imported into Bram's
    `files/models/`** (validated the multi-part import at 91 GB scale). Both Nemotron quants were
    deleted this session to free ~44 GB (→ ~136 GB free); the dead Nemotron profile record may remain.
- **Phone is disconnected** — the last wireless adb endpoint was `192.168.1.169:45953`, which
  expires. To reconnect: on the phone, Developer options → Wireless debugging → give the new
  "IP address & Port" (and the pairing port + 6-digit code the first time). Then
  `adb connect <ip>:<port>`. The emulator (`emulator-5554`) is up and usable for app-level
  work, but it cannot run the phone-only models (Nemotron) and its UI automation has proven
  unreliable for the model picker/chat send.
- **Multi-part GGUF load — DONE and validated on device** (2026-08-14). A proper `llama-gguf-split`
  pair of the phone's Qwen3.5-0.8B Q4_0 imports, loads (both siblings, `output_norm.weight` and all),
  and produces a real CPU reference (auto-configure `CPU ✓ 125/30`). This first real-split run exposed
  two bugs in `LocalModelStore.importModel`, both fixed in this session's uncommitted change / commit:
  (a) metadata was read from `uris.first()` (first *selected*) but only part 00001 carries
  `general.architecture`; (b) the copy loop paired `sorted[index]` names with `uris[index]` bytes, so
  out-of-order selection wrote each part under the other's name. The earlier "test split" in
  `Temp\opencode\split\` was a naive byte split with no `split.*` metadata — it masked bug (a) and could
  never load; regenerate real splits with a host-built `llama-gguf-split`. `importModel` still has no JVM
  test (needs Android ContentResolver/Uri). Minor UX: a total load failure renders as CPU `✓ 0/0`.
- **Nemotron MTP assert — CAPTURED and diagnosed** (2026-08-14; all temporary diagnostics reverted,
  tree clean). On device the Nemotron loads with `arch=nemotron_h_moe` and
  **`llama_model_n_layer_nextn=1`** (the MTP head IS exposed at pin `a94d563e`), so the line-341 nextn
  guard passes and the arch gate at ~line 349 is the real block. With the gate bypassed and MTP init
  forced *before* prefill (a temp `init_speculative()` call after chat-context creation — needed
  because the real call at ~line 1075 only runs after the Nemotron's ~3000-token, uncached prefill,
  which never completed on device), the exact abort is:
  `nemotron-h-moe.cpp:21: GGML_ASSERT(layer.nextn.eh_proj && layer.nextn.enorm && layer.nextn.hnorm) failed`
  → SIGABRT in `:inference` (the **app UI process survived** — process isolation held).
  **Root cause (upstream llama.cpp gap, not the model):** the GGUF DOES carry the tensors — dumping
  its header shows `blk.52.nextn.{eh_proj,enorm,hnorm,shared_head_norm}` (block 52 = the MTP block;
  `n_layer_all`=53, so `layers[52]` is allocated). But the `nemotron_h_moe` loader in `llama-model.cpp`
  at pin `a94d563e` **never creates the base nextn tensors**: the only `LLM_TENSOR_NEXTN_*` references
  are the optional fp8 `scale`/`input_scale` variants (lines ~1459–1528), each guarded by
  `&& layer.nextn.eh_proj` — a base tensor that is never `create_tensor`'d. So `layer.nextn.{eh_proj,
  enorm,hnorm}` stay null and the `graph_mtp` ctor asserts. **Fix path:** a llama.cpp pin (or local
  patch) that adds base `blk.%d.nextn.{eh_proj,enorm,hnorm,...}` loading for `nemotron_h_moe`, then
  re-test. (The GGUF is also missing `nextn.embed_tokens` — has a `tok_embd` fallback — and
  `nextn.shared_head_head`, which may surface next once base loading lands.) The gate stays until then.
  (Capturing the abort needed a pre-prefill `init_speculative()` hook: a normal chat send never
  completes the 30B's ~3000-token prefill on device, and reference/warm-up contexts tore down before decode.)
- **KV-cache + flash-attention tuning — VALIDATED on device** (2026-08-14). A full auto-configure
  pass on Qwen3.5-0.8B·Q8_0 swept both new dimensions at the 1K padded context, picked winners, and
  applied them (profile summary now reads `Attention Auto · KV cache Exact`, which it lacked before).
  Measured (prompt/decode tok/s at 1K pad): **KV cache — Exact (F16) 409/41 (winner) vs Compact
  (Q8_0) 305/41**; **Attention — Auto 401/35 (winner) vs On 293/36 vs Off 254/35**. So **Q8_0 KV does
  not help this model**: decode identical (41=41), prompt worse (quantize/dequantize overhead with no
  decode win at 1K context) — its value is KV *memory*, not speed. Flash-attn Auto beats forced On/Off.
  Caveat: this is a 0.8B model at a 1K pad, NOT the Nemotron long-context regime where Q8_0 KV's halved
  cache footprint could matter — that yardstick is still untested (Nemotron generation unreliable on
  device). Full pass also picked 4 threads / All cores / Aggressive polling / No mmap / Batch 256/128,
  backend NPU (136 tok/s); Mask 0x3, Mask 0xff, and 8 threads timed out (the documented arm64 hang).
- No pending on-device validations remain from the prior list.
- **Working tree is clean**; all work is on `main`, pushed.

main is healthy. The local Windows checkout is on `main`; the WSL build tree is a synced copy
of the same working tree (`bash.exe ./sync-wsl.sh`).

## Landed since the previous refresh

- **Multi-part GGUF import and loading** (`4c7da94`) — the model picker is now multi-select; a
  complete `name-NNNNN-of-MMMMM.gguf` set is validated as a unit, copied with its original
  names (llama.cpp derives the sibling list from the pattern + `split.count` metadata), and
  catalogued as one record with a parts list. The size check sums the parts, and orphan
  reclamation/removal are parts-aware. Single-file imports unchanged. The split-file format and
  the loader's sibling derivation were verified against the llama.cpp source; a real split pair
  was produced from the Qwen GGUF for testing. The final on-device load of a split set is now
  validated (2026-08-14) after fixing two selection-order bugs in `importModel` — see the
  session-continuation note above.
- **MTP speculative decoding, gated and diagnosed** (`eafe759`, `59f45ca`) — the chat generation
  path now auto-detects models with nextn heads and runs the speculative loop (draft from the
  model's own MTP head, one batched verification, common_sampler accept), with a fallback to the
  token-by-token loop. Two blockers found: the Nemotron-H-MoE MTP graph builder aborts at this
  pin (upstream asserts single-MTP-block support; the abort message now routes to logcat via
  `ggml_set_abort_callback`), and the Qwen3.5-0.8B carries no nextn tensors at all. The
  framework is dormant until a pin ships a working Nemotron MTP builder.
  **Test-model search done** (this commit): no small public GGUF ships MTP heads — verified by
  reading headers of Qwen3-0.6B/1.7B/4B (ggml-org, unsloth, mradermacher), Qwen3.5-0.8B/4B/8B,
  Qwen3.5-MoE-0.87B (arch qwen35/qwen35moe — correct arch, no nextn tensors), MiMo-2 (no GGUF),
  GLM-DSA (no GGUF). The only GGUFs with nextn heads are large models (Nemotron-Lightning-30B,
  Qwen3.5-35B-A3B-MTP, DeepSeek-V4). The MTP test therefore needs the phone + the user's
  Nemotron: temporarily remove the arch gate, capture the exact ggml_abort message (now routed
  to logcat), and evaluate a pin bump against it. DFlash drafts are trained artifacts (cannot be
  generated from a target GGUF); published only for Qwen3/DeepSeek backbones at large sizes, so
  it is blocked on the same upstream/pin work plus a small public draft pair.
- **KV cache and flash attention as tuning dimensions; padded context for the context-sensitive
  knobs** (`2afb2d9`, `0617a67`) — the auto-configure overlay now sweeps two more dimensions that
  directly affect long-context decode speed: KV-cache quantization (F16 vs Q8_0) and flash
  attention (Auto/On/Off). These are measured at a 1K-token padded context (1024 neutral filler
  tokens prepended to the benchmark prompt) so their effect is visible — at the bare 63-token
  prompt neither matters. Every other dimension (threads, masks, poll, load mode, batch) stays on
  the fast short-context measurement. The teacher-forced replay now carries its own prompt_ms +
  decode_ms, so one call serves both agreement and timing — the separate referenceDecode per
  candidate is gone, halving the padded cost.
- **Sweeps on Dispatchers.Default** (`4a8a2b7`) — the root cause of the recurring "stuck after a
  candidate times out" was that sweeps ran on Dispatchers.Main.immediate, and every
  withContext(Default) round-trip back to Main could lose its resumption under sustained CPU load.
  Moving to Default makes the round-trips no-ops and Thread.sleep-based polling cannot be defeated.
  This was THE fix that made auto-configure reliable end-to-end on the phone.
- **Skip previously timed-out candidates in-loop** (`5d1f64d`) — candidates the profile already
  recorded as timed out are skipped instantly inside the loop (not pruned from the list — the note
  carries their result forward so the skip persists). The masks and 8-threads hang in the engine on
  the S25 Ultra across both pins and have never once completed; after one discovery run they cost
  zero seconds on every subsequent pass.
- **Backend sweep watchdog** (`adfc845`) — the LFM2.5 auto-configure "doesn't work at all" was a
  wedged backend measurement with no timeout; backends now run on watched threads with a 5-min
  deadline, restart on hang, and record a failure so the pass moves on.
- **Pin bump to a94d563e** (`0483437`) — 31 upstream commits; the mask/8-threads arm64 hang
  persists at the new pin (it's a ggml threadpool bug on the 8 Elite's mixed-core topology with
  the Q8_0 model, not a llama.cpp version issue). The emulator's clean pass was misleading — it
  has 2 visible cores so never generated mask or 8-thread candidates. The new pin does add the
  Lightning Indexer fused ops (needed for Nemotron-style models), FA V-cache vectorize, and the
  LFM2 tool-call fix.
- **Thermal pause + indicator** (`b2433eb`, `ffcd4dd`) — auto-configure parks when the device
  reports thermal status above "none" (Samsung's "light" already throttles hard), shows the live
  status with a Continue-anyway button, and resumes automatically. The reference decode is also
  the throttle detector: Android's thermal API tracks skin temp not CPU banding, so a reference
  that takes minutes means the CPU is throttled regardless of what PowerManager says.
- **Thinking-mode reference bug** (`b2433eb`) — all reference loads (backend, dimension, batch)
  now pass `enableThinking = profile.thinkingEnabled`. Previously the reference ran without the
  thinking preamble while candidates ran with it → every candidate failed agreement on the first
  token on thinking-enabled profiles.
- **Probe-first hang detection, thread-watched timeouts** (`9758d73`) — every tuning candidate
  now runs a cheap probe first on a raw thread watched by a wall-clock deadline. Also fixed:
  `InferenceProcessService.onDestroy` no longer blocks on a hung executor.
- **Winner mark includes the CPU reference** (`9758d73`) — the ✓ covers the CPU reference row
  when it's the fastest agreeing run.

- **Milestone 19 — device-adaptive tuning, KleidiAI CPU kernels, and measurement fingerprints**
  (committed `8811fd1`, pushed). Full design in `docs/DEVICE_ADAPTATION.md`, research
  in `docs/PERFORMANCE_OPTIONS.md`. The runtime settings the pin exposes but Bram never used
  (threads, cpu mask, poll, load mode, `GGML_HEXAGON_*` flags) became profile fields, JNI
  threadpool plumbing, and teacher-forced tuning dimensions: every candidate must reproduce the
  CPU reference before its speed counts, winners are written as dated notes, and auto-configure
  now runs backend → decode dimensions → batch in one visible pass. Measured on the S25 Ultra:
  threads 6, aggressive poll, mmap, and hexagon defaults won their sweeps; the device also
  rejected the reference's `-no-mmap` and `--cpu-mask` advice by measurement.
  **KleidiAI** (dotprod/i8mm microkernels, built as an OBJECT library at
  `-march=armv8.6-a+dotprod+i8mm` with runtime HWCAP dispatch) took the LFM2.5 CPU prompt path
  from ~16 to **~86 tok/s** — enough that the CPU now beats the Hexagon NPU on that model (NPU
  0.54x against the new reference), and auto-configure re-routed the profile to CPU. The
  **measurement fingerprint** (device + app build + engine build + CPU features) stamps every
  result, the card warns when a profile was measured elsewhere, and sweeps refuse to run while
  the device throttles or is low on memory. On-device testing also fixed: a process-restart race
  for hex-flag changes (service self-kill + binder death-wait), a missing per-candidate timeout
  (a hung HTP kernel now unwedges the process and is recorded), the async `reloadProfiles` race
  that let tuning phases overwrite each other's writes, and a stuck-state after auto-configure
  that blocked all later tunes.
- **Visual tuning results** (committed `738aa88`, pushed) — measurements carry absolute
  prompt/decode tok/s and every sweep records per-candidate results; winner selection ranks by
  absolute throughput so the chart and the choice always agree. A follow-up pass (`b7c3deb`)
  condensed the profile card and the auto-configure overlay to compact run rows — name and
  tok/s per run, winner marked, no bars — with only Done dismissing the overlay, and the card
  keeping just the winning config's tok/s. The fingerprint is also computed at startup (it used
  to exist only inside sweeps, so every measured profile falsely warned on cold start).
- **Import compatibility and on-device quant conversion** (committed `738aa88`, pushed) —
  `GgufMetadataReader` reads the bounded tensor table (per-tensor quant counts), the card shows
  a Compatibility chip row (CPU/GPU/NPU share of a file's weight tensors), and a profile whose
  file the NPU cannot fully take offers Convert to Q4_0/Q8_0 via `llama_model_quantize` in the
  isolated process, then registers the result like an import and auto-configures it. Validated
  on the emulator end to end (a Q4_0 → Q8_0 conversion produced a correct 763.78 MiB file and a
  second catalog record); the "stalled" auto-configure after conversion turned out to be a
  candidate hang that the timeout + unwedge handled as designed, with the overlay showing the
  timed-out run in red. Phone path still unvalidated (phone disconnected).
- **LiteRT-LM unblocked and proven on-device** — `LiteRtEngineManager.generate` now uses the AAR's
  callback `sendMessageAsync(contents, MessageCallback)` wrapped in Bram's own `callbackFlow`, the
  acknowledged upstream workaround for the 0.15.0 `SendChannel.close$default` completion crash
  (google-ai-edge/litert-lm#2812); the channel `close()` now compiles against Bram's coroutines
  (1.10.2). `LiteRtLmOnDeviceTest` runs a real SmolLM2-135M turn to completion on the S25 Ultra
  and PASSES (it skips on x86 — the AAR ships arm64-v8a natives only; the model is pushed to the
  test package's external files dir).
- **Phone validation of the visual results and Phase 3** — on the S25 Ultra: the condensed
  run-row overlay rendered live with real tok/s (CPU 404/52, OpenCL 129/44, NPU 148/27, Vulkan
  FAIL; CPU won), the Compatibility chips (NPU 52%) and Convert buttons worked, and a Q4_0 →
  Q8_0 conversion produced a file whose SHA matched the emulator's byte-for-byte. The converted
  Q8_0 model intermittently hangs during teacher-forced replay under non-default configs at this
  pin (both arm64 and x86_64) — the timeout/unwedge/failure-note machinery handled every
  instance; documented as a known issue. Also fixed: `ModelProfileStore` persisted with
  `apply()` (async), so a profile created by a conversion could vanish if the process died
  right after — now `commit()`, like the catalog.
- **`milestone-8b-profile-first` (#17)** — profile-first UI, merged.
- **`milestone-8c-opencl` (#18)** — OpenCL for Adreno, validated: 96% (23/24) teacher-forced
  agreement, 1.11x vs CPU on Qwen3.5-Q4_0 on the S25 Ultra. The load abort was `ggml_backend_sched_new`
  requiring the CPU backend last in the device list.
- **`agent-tools` (#19)** — web_search/web_fetch behind the approval gate, transcript summaries.
- **`m8d-autoconfigure-ui` (#20)** — auto-configure: measures every backend against the CPU
  reference, records results on the profile, picks the fastest that agreed, and shows the run live
  in an in-tree overlay that blurs the app behind it. Also fixes a silent CPU fallback (backend
  detection now queries the runtime synchronously before choosing) and darkens/opens up the glass.
- **Milestone 18** — the status service holds for the loaded model's lifetime (start on load, stop
  on unload or inference-process death) with a phase notification, and an opt-in completion alert
  posts a summary of the reply when a turn finishes backgrounded. The alert shows the reply text
  rather than an inline Reply action, which testing showed to be pointless for a message you cannot
  see yet. Also: the build-sync now mirrors deletions (`--delete`) while excluding the WSL-only
  OpenCL stub.
- **Memory auto-injection** (`f21680e`) — the highest-importance standing memories now ride the
  system prompt every turn (char-budgeted, like skills), so the agent has durable context without
  calling `memory_search`. Working summaries stay out of it.
- **Compose UI smoke tests** (`8d0429d`) — `ChatScreenSmokeTest` covers launch chrome, composer
  input, and drawer/panel navigation against the real `MainActivity`, with test-tag hooks. Also
  fixes the `BootReceiver` broadcast ANR (the re-arm ran `runBlocking` on the main thread; now
  `goAsync()` plus a background thread) and pins espresso 3.7.0 for the API 36 emulator.
- **In-app model downloader removed** — browsing Hugging Face and downloading from inside the app
  added complexity for no real win over the browser + SAF import path. Models come in from the
  filesystem only; the catalog, downloader, staging directory, and dialog are gone.
- **Agent robustness pass** — four fixes the audit turned up. (1) Short messages like "hi" or "ok"
  no longer crash the turn: the orchestrator's auto-recall is now best-effort, and the SQLite store
  short-circuits an empty FTS query instead of throwing. (2) `schedule_notification` alarms persist
  to a store and the boot receiver re-arms them — they survive a reboot, fire past-due ones on
  wake, and no longer collide on identical title+body. (3) An unattended (backgrounded or
  scheduled) run fast-denies a tool that needs approval instead of holding the gate open for ten
  minutes per call. (4) `web_fetch` caps the read and refuses non-text content types, so a huge or
  binary response cannot OOM the tool.
- **Approvals reach the shade** — a tool call that needs an answer while nobody is at the card
  (app backgrounded, or a scheduled task) now posts a high-importance notification with Allow /
  Deny actions instead of the fast-deny above: the run parks on the request and the actions
  resolve the same pending card the app would. Without notification permission the old fast-deny
  still applies, so an invisible ask never holds the run. The per-conversation tool-approval mode
  (bypass / auto / manual) was only reachable through the top pill's routing screen; it now has
  its own Conversation destination in the drawer, next to System.
- **Embedding recall, end to end** — the memory store carries a vector index (`memory_vectors`,
  DB v2) with cosine ranking, fused into the keyword (FTS) results via reciprocal-rank fusion
  behind a pluggable `Embedder` interface (`core/domain`). The native llama.cpp embedder is wired:
  a second, independent model+context in the `:inference` process runs mean-pooled embeddings
  through JNI/AIDL, L2-normalized, resident for the process life. The user picks an embedding
  GGUF on the Memories screen; once set, every stored memory is embedded at write time and every
  query at recall time, so a query like "appearance settings" finds "dark mode". With no embedding
  model designated the store skips the IPC entirely and recall stays keyword-only.

## What works (on main)

**Compose UI smoke tests.** The shell (launch chrome, composer input, drawer
and panel navigation) is covered by `ChatScreenSmokeTest` against the real
`MainActivity`, runnable on the emulator or phone — see Testing. The stable
hooks are test tags, so the assertions survive wording changes.

**Local CPU inference.** Import a GGUF through the document picker, verify it
with SHA-256, load it in the isolated `:inference` process, and chat with
streaming, cancellation, unload, and recovery from a killed inference process.
Validated on the S25 Ultra.

**Accelerators.** Hexagon NPU passes on the S25 Ultra at 23/24 (95.8%)
teacher-forced agreement, ~1.7x CPU. OpenCL (Adreno 830) passes at 23/24
(96%), 1.11x CPU on Qwen3.5-Q4_0. Vulkan on the same device fails: 75%
agreement with one offloaded layer, collapsing to all-zero logits past about
seven, with no error reported. Vulkan compiles and is offered, but is not
validated. Auto-configure measures whichever backends the build and device
offer and records the results on the profile; NPU routing was re-verified
after the backend changes (all four htp skels packaged and loaded).

**Conversations.** Persisted as one JSON file each with a rebuildable index.
Multiple threads, New and History, titles from the first message, restored on
launch. Survives `force-stop`.

**Agentic transcript.** Messages carry ordered activity entries — reasoning and
tool invocations — rendered as collapsed lines that expand on tap and persisted
with the conversation.

**Background runs.** Agent work belongs to an application scope and holds a
foreground service, so a run continues and writes its reply when the user leaves
the app.

**Chat quality of life.** Retry, edit-and-resend, copy, Markdown rendering, and
auto-scroll that follows a streaming reply.

**Tools.** The approval gate asks before a side-effecting tool runs. The
built-in surface (wired in `BramApplication`) covers device status, scratch
notes, web search/fetch, files, clipboard, notifications, scheduled reminders,
app/URI launching, contacts, calendar, Termux commands, and memory search; MCP
servers contribute more at runtime.

## What is not implemented

- **Automation and scheduling gaps.** The task queue, cron automations, and
  per-task UI are shipped (M13/M16), and a `BootReceiver` re-arms automations,
  scheduled tasks, and agent-set reminder notifications after a reboot or app
  update, but cron is the only schedule kind, and there are no network/charging
  constraints or per-run budgets.
- **Memory gaps.** Working-summary compaction and `SEMANTIC_FACT` /
  `USER_INSTRUCTION` extraction (run after each completed turn) are wired and
  FTS-retrieved, a Settings panel lists recent memories and lets the user forget
  wrong ones, and the highest-importance memories are auto-injected into the
  system prompt each turn (char-budgeted) so the agent has standing context
  without calling `memory_search`. Recall now blends keyword (FTS) and semantic
  (embedding) ranking via reciprocal-rank fusion: a resident llama.cpp embedder
  in the `:inference` process L2-normalizes mean-pooled vectors, chosen by the
  user on the Memories screen; with none designated, recall falls back to FTS.
  Still missing: per-conversation working summaries are separate from the
  cross-conversation store. (Episodes are capped to the newest 20 per
  conversation, so a long-running thread cannot let them dominate.)
- **Skills gaps.** The SKILL.md lifecycle is shipped (M16), and the agent can now author skill
  drafts through a `propose_skill` tool — they land with no active version (kept out of the
  system prompt) until the user activates them from Settings. Active skills are cosine-ranked
  against the turn's query so the prompt's character budget trims the least relevant; and a
  drafted skill whose description matches the task is surfaced to the model as a one-line nudge
  (at most once per conversation, deduped), so the user learns a relevant draft exists without the
  draft ever being followed before activation. Both need an embedding model designated to rank.
- **LiteRT.** The `:runtime:litertlm` module is wired end-to-end (import, store,
  routing, UI card) but is blocked from shipping by an upstream AAR crash — see
  Known issues. It is not a usable runtime yet.
- **Remote endpoints.** Optional and non-streaming.

## Known issues

**Qwen3.5's chat template does not render.** It iterates `messages[::-1]`, which
llama.cpp's Jinja engine yields nothing for, so the template raises and Bram
falls back to a built-in one. Turning reasoning off is the practical fix and is
the default.

**Tools need the model's own template to render (designed, not yet shipped).**
Tool definitions go through `common_chat_templates_apply`. When a model's
Jinja template raises (Qwen3.5 iterates `messages[::-1]`, which minja yields
nothing for), `apply_chat_template` in `runtime/llamacpp/src/main/cpp/bram_llama_jni.cpp`
catches it and retries with `inputs.use_jinja = false` (lines ~457-466). That
routes to llama.cpp's built-in templates, and the generic built-in does not
format tools — so the model never sees what it can call. Qwen3.5 is in that
state and cannot call a tool.

A naive preamble ("here are your tools") is not enough on its own: Bram's
recovery only catches one shape — `name(key='value')` (LFM2.5 style) in
`runtime/llamacpp/.../inference/BareToolCall.kt` — it does not recover the
Qwen/Hermes `<tool_call>{"name":…,"arguments":…}</tool_call>` JSON convention.
So the fix needs a tool-capable built-in template *together with* its matching
parser, ideally matched to the model's family. The native `common_chat_parse`
already understands family-specific markers once the format is pinned.

Plan, when a fallback model is available to validate against:
1. On the `use_jinja = false` retry in `apply_chat_template`, pin
   `inputs.chat_format` to a tool-capable built-in chosen by family heuristics
   on the model metadata (Qwen3.x → the Qwen tool format; Hermes/ChatML →
   Hermes; Llama3 → Llama3), instead of letting it resolve to the generic.
2. Surface the chosen format in `bridge.chatFormat()` (already reported in the
   start event) so the Kotlin side can pick the right reasoning tags and the
   recovery path can relax only for the format actually in use.
3. Only then consider broadening `BareToolCall` — its strictness is deliberate
   (a model quoting JSON in prose must not become a call).

This is parked because it is model-family-dependent and cannot be validated
without a real fallback-mode model (Qwen3.5) doing tool calls on a device; the
emulator has no such model and the suite cannot exercise template/parser
tuning. Picking it up means loading one and iterating the format choice
against real output before committing.

**LFM2.5-2.6B is too small for reliable tool use.** It retries failing calls,
invents tools the gate then rejects, and exhausts the tool-turn budget. The tool
infrastructure is ready; real agent behavior needs a larger tool-capable model.

**`web_fetch` redirect handling hardened (verified on device).**
Redirects used to bounce to the hop cap and fail opaquely on loop-prone sites.
The redirect policy now keeps a host-scoped cookie jar (replaying `Set-Cookie`
across hops), detects a cycle on the second hit instead of bouncing to the
cap, and refuses an https→http downgrade; the policy is pure over a
single-request seam and unit-tested in `WebToolTest`. Verified live on a phone:
ordinary pages (`example.com`) fetch unchanged, and a genuine loop now fails
fast with a clear "Redirect cycle…" message the model can pivot away from.

**One site is still unfetchable, and it is not a bug:** `developer.android.com`
silently tries to OAuth-sign-in on every page load (`auto_signin=True`,
`prompt=none` → `accounts.google.com/o/oauth2/v2/auth`), which fails with
`interaction_required` for an unauthenticated client and redirects home
forever. No header or cookie a simple `HttpURLConnection` can carry will
satisfy it — it needs a logged-in browser session. The hardened loop handling
turns this into a clean cycle error (the model falls back to `web_search`
snippets) rather than the old opaque hang. Don't treat a fetch of that domain
as a regression.

**The UI cannot be read by automation while a turn is running.** `uiautomator
dump` needs an idle window and the send button animates during generation, so the
tree is not dumpable mid-turn. Screenshots still work; the approval card's accept
branch is still unverified for this reason.

**The build can silently lose Hexagon.** CMake caches `GGML_HEXAGON=OFF`, so a
build without `HEXAGON_SDK_ROOT` poisons later builds. Clear
`runtime/llamacpp/.cxx` to recover, and check the packaged `.so` with
`strings | grep ggml-hex` rather than trusting the build log. The same cache trap
applies to `GGML_OPENCL`.

**Reasoning is expensive.** A reasoning model asked to say hello can spend
paragraphs deliberating. Reasoning is off by default, per model.

**Vulkan's failure is in a shared operation, not a layer.** Bisection on the
Adreno 830 shows even one offloaded layer disagreeing. Nothing has been attempted
to fix it; the backend is offered and reported as unvalidated.

**litertlm turns crash on completion.** `com.google.ai.edge.litertlm:litertlm-android`
0.15.0 (the latest release) loads and generates on-device — the native libs init
and the CPU engine reaches `onDone` — but its compiled `Conversation.sendMessageAsync`
bytecode calls `SendChannel.close$default`, a static no shipped kotlinx-coroutines
provides (verified absent in 1.7.3 / 1.8.1 / 1.9.0 / 1.10.2), so every turn throws
`NoSuchMethodError` as it finishes and the process dies. The GPU path fails earlier
on a generic (non-device-matched) package with `embedding_lookup != nullptr`. The
crash is in the AAR's own teardown, reachable through `LiteRtEngineManager.generate`,
so the app is affected, not just tests. Reproduced by
`LiteRtLmOnDeviceTest` (`@Ignore`d, with the full detail in its kdoc). Revisit when a
corrected AAR ships; until then litertlm is inert code.

## Build

See [Building Bram](BUILDING.md) for the full toolchain. The parts that are easy
to get wrong:

- **CMake 3.30.5**, not the NDK default, because the Vulkan backend needs
  FetchContent's `find_package` redirect.
- **WSL is the recommended local environment.** Incremental native rebuilds take
  seconds there against ~12 minutes in CI, and its Linux host compiler avoids the
  MSVC requirement Windows hosts hit building `vulkan-shaders-gen`.
- **Hexagon is opt-in** through `HEXAGON_SDK_ROOT`. The SDK is proprietary and
  absent on CI, but it IS installed on this machine now: extracted to
  `/home/s14/hexagon/6.6.0.0` in WSL (via the Docker image in BUILDING.md) and
  `C:\Users\S14\hexagon\6.6.0.0` on Windows; `bram.hexagonSdkRoot` in
  `~/.gradle/gradle.properties` on both hosts. Note `qaic` is a Linux binary —
  the NPU build runs in WSL, not on Windows.
- **WSL is the working build environment on this machine.** The tree lives at
  `/home/s14/bram-build` (synced from the Windows checkout), with
  `bram.hexagonSdkRoot`, `bram.opencl=true`, and `bram.nativeStaging=/home/s14/bramcxx`
  in `~/.gradle/gradle.properties`, and `sdk.dir` in `local.properties`. A cold
  native build is ~5 minutes; incremental Kotlin builds are seconds.
- **Windows native builds** work too but need `vcvars64.bat` on PATH (for
  `vulkan-shaders-gen`) and a short staging path
  (`bram.nativeStaging=C:/Users/S14/bramcxx` in `~/.gradle/gradle.properties`)
  to stay under MSVC's 250-character object-path limit.
- **Debug signing across hosts.** Gradle signs with the host's debug keystore, so
  APKs from CI, Windows, and WSL differ and cannot install over each other
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Point builds at one shared keystore with
  `BRAM_DEBUG_KEYSTORE` (passwords default to `android` / `androiddebugkey`).
  The WSL build currently copies the Windows `debug.keystore` into
  `~/.android/debug.keystore` — the `BRAM_DEBUG_KEYSTORE` knob is the clean fix.
- Keep `ninja` on `PATH`; several native sub-builds inherit the generator without
  the make program.

## Testing

CI runs JVM tests, Android lint, and a full native build with CPU and Vulkan. It
cannot build Hexagon or OpenCL, and cannot run any accelerator correctness test,
which needs real hardware.

Unit tests cover accelerator agreement scoring, offload bisection, Markdown
rendering, the streaming reasoning split, the web tool HTML parsing, the
activity summary, and the memory/skill prompt assembly. The accelerator tests
exist because two earlier acceptance criteria produced confidently wrong
verdicts on device; the cases that misled us are pinned.

For anything touching inference or the UI, run it on the emulator or phone. Three
separate defects in this codebase compiled cleanly, passed CI, and were only
visible when the app actually ran.

### Compose UI tests (`ChatScreenSmokeTest`)

The shell has a smoke suite: launch chrome, composer text input, and
drawer/panel navigation, all run against the real `MainActivity` with no model
state. Stable hooks are `testTag`s (`menu-button`, `new-chat`, `model-pill`,
`composer-field`, `send-button`) rather than text, so the assertions survive
wording changes. Run on a connected device or emulator:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.kurue.bram.app.test/androidx.test.runner.AndroidJUnitRunner
```

Two setup notes. First, espresso is pinned to `3.7.0`
(`app/build.gradle.kts`): AOSP API 36 removed the hidden
`InputManager.getInstance()` the bundled strategy reflected into, so older
espresso fails every injection on the API 36 emulator image (it still worked
on the Samsung build, which ships a patched framework). Second, the first
instrumentation after an install or boot used to be killed by the system:
`BootReceiver` ran its re-arm with `runBlocking` on the main thread, and a
cold process could stall past the broadcast ANR timeout. It now uses
`goAsync()` plus a background thread, keeping the broadcast alive until the
re-arm finishes without blocking the main thread.

To exercise the smoke tests on a fresh process, force-stop both packages
before instrumenting; that is also how to reproduce the ANR the fix
addresses.

## Working agreements

- Verify accelerators against CPU output rather than benchmarking them. A
  speed-only comparison reported a broken Vulkan backend as working.
- Compare with teacher forcing, not free-running generation. One differing token
  otherwise sends the rest of the reply somewhere unrelated.
- Do not report an accelerator as validated because it compiled and produced
  output.
- Squash-merge one commit per milestone.

## Next

0. **Phone session (highest priority)** — reconnect wireless adb, install the latest build,
   then run the three validations in "Session continuation" above: the split-model load, the
   Nemotron MTP assert capture (gate removed temporarily), and an auto-configure with the new
   KV/FA dimensions. These decide the MTP path (upstream patch vs pin bump) and confirm the
   multi-part feature.
1. **MTP speculative decoding** — the framework is built + gated. Unblocking needs either a
   working Nemotron-H-MoE MTP builder upstream (verified still asserting "single MTP block" on
   master as of this session) or a small patch if the captured assert is the output-head one.
   The phone's Nemotron is the test model; no small GGUF ships nextn heads (search documented).
2. **DFlash** — blocked on the same upstream work + a small published draft pair (drafts are
   trained artifacts, Qwen3/DeepSeek backbones only, all large). Not actionable until one
   exists.
3. **Multi-part GGUF** — code complete; the on-device load validation is the remaining step
   (pending item 0). If the loader rejects a real split set, the split-file generator in
   `C:\Users\S14\AppData\Local\Temp\opencode\split_gguf.py` is the reference for the format.
4. **Phase 4 continues** — the runtime-neutral tuning seam (reuse the probe/measure/winner
   machinery for LiteRT-LM and future runtimes). Known issues to revisit: the converted Q8_0
   model's intermittent teacher-forced hangs (mask/8-threads persist across pins on arm64; the
   pruning/skip logic makes them cost nothing after one discovery), KleidiAI SVE kernels, quant
   conversion off the service's single executor, and the deferred power hints.
5. A larger tool-capable model for agentic use (LFM2.5-2.6B cannot chain tools reliably);
   add one through the import picker.
6. Vulkan correctness — low priority; OpenCL is the validated GPU path.

Deferred from Milestone 7 (closed): batch tuning (`n_ubatch` pinned at 128,
should follow measured prompt throughput) and surfacing the KV-reuse count in
the app UI (`cachedPromptTokens` is reported with the load response but not
shown). Both shipped after the milestone closed: batch is a per-profile setting
with a teacher-forced "Tune batch" measurement (winner saved with a dated
note; measured 512/256 on the NPU profile), and the composer metrics line
shows "KV reuse: N of M tok". The reuse count is honest — zero on the hybrid
LFM2 model, which refuses partial cache trims; a Qwen2.5-0.5B import
demonstrated the gain on the phone (565 of 585 tokens reused, prompt at
2489 tok/s vs 104 cold).

Reasoning-folding for LFM2.5 is unit-tested only; confirm on device with a
reasoning turn when convenient.
