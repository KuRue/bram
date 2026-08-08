# Session handoff

Last updated: 2026-08-07

## Current source of truth

| Item | Value |
|---|---|
| Repository | Private `KuRue/bram` |
| main | [`664716f`](https://github.com/KuRue/bram/commit/664716f) — through auto-configure (#20) |
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

- **Model download.** Getting a GGUF in still needs a browser and the file
  picker. Deliberately deferred.
- **Task queue and scheduling.** One run is tracked at a time. There is no
  queue, no scheduled work, and no per-task UI. `AgentTaskService` is the
  foundation, not the finished thing.
- **Tools beyond `device_status` and `write_note`.** `web_search`/`web_fetch`
  are on `agent-tools`, not main.
- **Memory, skills, automations.** Interfaces only.
- **LiteRT.** Not started.
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

1. Milestone 18 — live status notification, done and verified on the S25 Ultra.
   The status service runs per model-load (start on load, stop on unload or
   inference-process death), its notification reports model, backend, and
   phase from the turn events the transcript already consumes, and the
   opt-in completion alert (Settings > Notifications) posts a summary of the
   reply when a turn finishes backgrounded. Design note: an inline Reply
   action on the alert was dropped in testing — a reply to a message you
   cannot see yet is pointless — so the alert shows the reply text instead.
   The build-sync now mirrors deletions (--delete) while excluding the
   WSL-only OpenCL stub.
2. A larger tool-capable model for agentic use. The web tools and approval gate
   work; LFM2.5-2.6B cannot chain tools reliably.
3. Vulkan correctness — fails in a shared operation on Adreno 830; OpenCL is
   the validated GPU path, so chasing Vulkan is low priority unless OpenCL's
   1.11x needs replacing.
4. In-app model download — the biggest new-user gap left (deferred since M1).

Deferred from Milestone 7 (closed): batch tuning (`n_ubatch` pinned at 128,
should follow measured prompt throughput) and surfacing the KV-reuse count in
the app UI (`cachedPromptTokens` is reported with the load response but not
shown). A pure-attention model such as Qwen3.5 should finally demonstrate the
KV-reuse gain on the emulator.

Reasoning-folding for LFM2.5 is unit-tested only; confirm on device with a
reasoning turn when convenient.
