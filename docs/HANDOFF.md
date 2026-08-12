# Session handoff

Last updated: 2026-08-08

## Current source of truth

| Item | Value |
|---|---|
| Repository | Private `KuRue/bram` |
| main | [`8d0429d`](https://github.com/KuRue/bram/commit/8d0429d) — UI smoke tests land; in-app model downloader removed |
| Target phone | Samsung `SM-S938U1` (Snapdragon 8 Elite, HTP v79), 10.9 GB app-visible RAM |
| Test emulator | AVD `Pixel_9a`, x86_64, 6 GB RAM / 16 GB storage |
| Reference models | `LFM2.5-2.6B-Q4_0.gguf` (phone, tool-capable), `Qwen3.5-0.8B-Q4_0.gguf` (emulator) |

main is healthy and CI-green. The auto-configure UI work (profile card, glass tuning, the
auto-configure overlay) is merged as #20. The local Windows checkout is on `main` at #20; the WSL
build tree is a synced copy of the same working tree.

## Landed since the previous refresh

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

1. A larger tool-capable model for agentic use. The web tools and approval gate
   work; LFM2.5-2.6B cannot chain tools reliably. Add one through the Models
   screen's import picker once it is on the device.
2. Vulkan correctness — fails in a shared operation on Adreno 830; OpenCL is
   the validated GPU path, so chasing Vulkan is low priority unless OpenCL's
   1.11x needs replacing.

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
