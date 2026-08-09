# Session handoff

Last updated: 2026-08-08

## Current source of truth

| Item | Value |
|---|---|
| Repository | Private `KuRue/bram` |
| main | [`48ff4f6`](https://github.com/KuRue/bram/commit/48ff4f6) — through Milestone 19 (in-app model downloads) |
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
- **Milestone 19 (in-app model downloads)** — "Download a model" on the Models screen: the
  HuggingFace tree API is browsed from the dialog (smallest-first, LFS-only, readable errors for
  missing/gated repos), the download streams to a `.gguf.part` staging file with live progress and
  an instant Cancel (cancellation severs the connection to wake a blocked read), verifies SHA-256,
  and imports the digest-named copy with its HF source URL recorded. Eleven new unit tests cover
  the catalog and downloader against stub HTTP servers. Verified on the S25 Ultra: Qwen2.5-0.5B
  Q2_K (396 MB) downloaded at ~1.3 MB/s, verified, imported, auto-profiled, and loaded; the second
  turn showed `KV reuse: 565 of 585 tok` and prompt processing at 2489 tok/s vs 104 tok/s cold —
  the first on-device demonstration of the Milestone 7 KV-reuse gain.

## What works (on main)

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

**Model downloads.** Import a GGUF straight from Hugging Face from the
Models screen: browse the repository's quantizations (smallest-first,
SHA-256-verified, readable errors for missing or gated repos), download with
live progress and cancellation, and the verified copy lands in the models
list with a profile ready to load.

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

**Tools.** The approval gate asks before a side-effecting tool runs; `write_note`
is the one tool a local model can call end to end. (More tools live on
`agent-tools`.)

## What is not implemented

- **Resumable or queued downloads.** One verified, cancellable transfer at a
  time; a dropped connection restarts the file rather than resuming it.
- **Tools beyond `device_status` and `write_note`.** `web_search`/`web_fetch`
  are on `agent-tools`, not main.
- **Automation and scheduling gaps.** The task queue, cron automations, and
  per-task UI are shipped (M13/M16), but automations do not survive a reboot
  (no `BOOT_COMPLETED` receiver), cron is the only schedule kind, and there are
  no network/charging constraints or per-run budgets.
- **Memory gaps.** Working-summary compaction and `SEMANTIC_FACT` /
  `USER_INSTRUCTION` extraction (run after each completed turn) are wired and
  FTS-retrieved, but `EPISODE` records are never produced, there is no UI for
  browsing or curating memories, no embeddings/vector index, and extracted
  facts are conversation-scoped (cross-conversation recall is via the
  `memory_search` tool only).
- **Skills gaps.** The SKILL.md lifecycle is shipped (M16); the remaining gap is
  agent-authored drafts (the `QUARANTINED` lifecycle path is unimplemented).
- **LiteRT.** The `:runtime:litertlm` module is wired end-to-end (import, store,
  routing, UI card) but is blocked from shipping by an upstream AAR crash — see
  Known issues. It is not a usable runtime yet.
- **Remote endpoints.** Optional and non-streaming.

## Known issues

**Qwen3.5's chat template does not render.** It iterates `messages[::-1]`, which
llama.cpp's Jinja engine yields nothing for, so the template raises and Bram
falls back to a built-in one. Turning reasoning off is the practical fix and is
the default.

**Tools need the model's own template to render.** Tool definitions go through
`common_chat_templates_apply`. When a template falls back, that path has no tool
support; Qwen3.5 is in that state and cannot call a tool.

**LFM2.5-2.6B is too small for reliable tool use.** It retries failing calls,
invents tools the gate then rejects, and exhausts the tool-turn budget. The tool
infrastructure is ready; real agent behavior needs a larger tool-capable model.

**`web_fetch` loops on bot-hostile sites.** developer.android.com redirect-loops
under plain HTTP regardless of headers; the model should use `web_search`
snippets or alternate URLs. Ordinary pages (example.com, Wikipedia) fetch fine.
(On `agent-tools`.)

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
rendering, the streaming reasoning split, the web tool HTML parsing, and the
activity summary. The accelerator tests exist because two earlier acceptance
criteria produced confidently wrong verdicts on device; the cases that misled us
are pinned.

For anything touching inference or the UI, run it on the emulator or phone. Three
separate defects in this codebase compiled cleanly, passed CI, and were only
visible when the app actually ran.

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
   work; LFM2.5-2.6B cannot chain tools reliably. Downloading one is now a
   dialog away.
2. Vulkan correctness — fails in a shared operation on Adreno 830; OpenCL is
   the validated GPU path, so chasing Vulkan is low priority unless OpenCL's
   1.11x needs replacing.
3. Resumable and queued downloads (multi-file repositories, Xet) — the
   download path is built; these extend it rather than rework it.

Deferred from Milestone 7 (closed): batch tuning (`n_ubatch` pinned at 128,
should follow measured prompt throughput) and surfacing the KV-reuse count in
the app UI (`cachedPromptTokens` is reported with the load response but not
shown). Both shipped after the milestone closed: batch is a per-profile setting
with a teacher-forced "Tune batch" measurement (winner saved with a dated
note; measured 512/256 on the NPU profile), and the composer metrics line
shows "KV reuse: N of M tok". The reuse count is honest — zero on the hybrid
LFM2 model, which refuses partial cache trims; Milestone 19's Qwen2.5-0.5B
download finally demonstrated the gain on the phone (565 of 585 tokens
reused, prompt at 2489 tok/s vs 104 cold).

Reasoning-folding for LFM2.5 is unit-tested only; confirm on device with a
reasoning turn when convenient.
