# Session handoff

Last updated: 2026-08-07

## Current source of truth

| Item | Value |
|---|---|
| Repository | Private `KuRue/bram` |
| main | [`27fd7e3`](https://github.com/KuRue/bram/commit/27fd7e3) — through tool permissions and a callable tool (#16) |
| Target phone | Samsung `SM-S938U1` (Snapdragon 8 Elite, HTP v79), 10.9 GB app-visible RAM |
| Test emulator | AVD `Pixel_9a`, x86_64, 6 GB RAM / 16 GB storage |
| Reference models | `LFM2.5-2.6B-Q4_0.gguf` (phone, tool-capable), `Qwen3.5-0.8B-Q4_0.gguf` (emulator) |

main is healthy and CI-green. Everything below that is not yet on main is on a
feature branch, verified but unmerged.

## In flight (feature branches, ready to merge)

- **`milestone-8b-profile-first`** — profile UI polish: a real "New profile"
  entry (a model picker) separate from "Import model", a top-k sampler slider,
  and six dead `ModelsScreen` callbacks removed. Compile + unit verified.
- **`milestone-8c-opencl`** — the OpenCL backend. The earlier "cause unknown"
  load abort is fixed: `ggml_backend_sched_new` requires the CPU backend to be
  last, and Bram's accelerator-only device list violated that, so the CPU
  device is now appended after the matched accelerator. Validated on the S25
  Ultra at 96% (23/24) agreement, 1.11x vs CPU on Qwen3.5-Q4_0. OpenCL is opt-in
  (`BRAM_OPENCL=true`), needs the uncommitted `runtime/llamacpp/src/main/cpp/
  opencl-stub/libOpenCL.so` vendor stub (CI cannot build it), and `GGML_OPENCL`
  is cached in `.cxx` so toggling it means deleting `.cxx`. The in-tree commit
  message still says "cause unknown" and is outdated.
- **`agent-tools`** — transcript and the agent surface. A multi-step turn
  collapses to one expandable summary line ("Thought 8s · 3 tools"); new
  `web_search` (DuckDuckGo, no key) and `web_fetch` (page to text) tools behind
  the approval gate, scoped to the query or URL; `web_fetch` follows redirects
  by hand with a cap and sends browser-like headers with gzip decoding; LFM2.5
  reasoning now folds (it reported an unusable format, so the streamer falls
  back to `<think>`/`</think>`). 60+ unit tests; the web tools were exercised on
  the phone. Reasoning-folding is unit-tested only, not yet driven on device.

## What works (on main)

**Local CPU inference.** Import a GGUF through the document picker, verify it
with SHA-256, load it in the isolated `:inference` process, and chat with
streaming, cancellation, unload, and recovery from a killed inference process.
Validated on the S25 Ultra.

**Accelerators.** Hexagon NPU passes on the S25 Ultra at 23/24 (95.8%)
teacher-forced agreement, ~1.7x CPU. Vulkan on the same device fails: 75%
agreement with one offloaded layer, collapsing to all-zero logits past about
seven, with no error reported. Vulkan compiles and is offered, but is not
validated.

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
- **OpenCL and LiteRT.** OpenCL is on `milestone-8c-opencl`, validated; LiteRT is
  not started.
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
- **Hexagon is opt-in** through `HEXAGON_SDK_ROOT`, since the SDK is proprietary
  and absent on CI. The SDK is not installed on this machine; extract it via the
  Docker image in BUILDING.md before any NPU build.
- **OpenCL is opt-in** through `BRAM_OPENCL=true`, additionally needs the
  uncommitted `opencl-stub/libOpenCL.so`, and toggling it means clearing `.cxx`.
- **Emulator builds are opt-in** through `BRAM_EMULATOR_ABI=true`, which adds
  `x86_64`. Bram is otherwise ARM64-only.
- **Debug signing across hosts.** Gradle signs with the host's debug keystore, so
  APKs from CI, Windows, and WSL differ and cannot install over each other
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Point builds at one shared keystore with
  `BRAM_DEBUG_KEYSTORE` (passwords default to `android` / `androiddebugkey`).
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

1. Merge the three ready branches when happy — `milestone-8b-profile-first`,
   `milestone-8c-opencl`, `agent-tools`. Each is self-contained and verified.
2. Verify the NPU still routes after backend changes. Needs an arm64 build with
   `HEXAGON_SDK_ROOT`; Hexagon is validated at 95.8% / ~1.7x on main today.
3. Vulkan correctness — fails in a shared operation on Adreno 830. Still open.
4. A larger tool-capable model for agentic use. The web tools and approval gate
   work; LFM2.5-2.6B cannot chain tools reliably.

Reasoning-folding for LFM2.5 (`agent-tools`) is unit-tested only; confirm on
device with a reasoning turn when convenient.
