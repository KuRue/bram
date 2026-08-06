# Session handoff

Last updated: 2026-08-05

## Current source of truth

| Item | Value |
|---|---|
| Repository | Private `KuRue/bram` |
| Branch | `main` at [`dbc8271`](https://github.com/KuRue/bram/commit/dbc82716906da45d31513204223c5e81f41a5baa) |
| Open pull requests | None |
| In flight | `milestone-9-glass-ui`, not yet opened as a PR |
| Target phone | Samsung `SM-S938U1` (Snapdragon 8 Elite, HTP v79), 10.9 GB app-visible RAM |
| Test emulator | AVD `Pixel_9a`, x86_64, resized to 16 GB storage and 6 GB RAM |
| Reference models | `LFM2.5-2.6B-Q4_0.gguf` (phone), `Qwen3.5-0.8B-Q4_0.gguf` (emulator) |

Everything below has been exercised on real hardware or the emulator, not only compiled.

## What works

**Local CPU inference.** Import a GGUF through the document picker, verify it with SHA-256, load it
in the isolated `:inference` process, and chat with streaming, cancellation, unload, and recovery
from a killed inference process. Validated on the S25 Ultra.

**Accelerators.** The Hexagon NPU passes correctness validation on the S25 Ultra at 23/24 (95.8%)
teacher-forced agreement with CPU. Measured speed against the CPU reference has ranged from about
1.5x to 1.9x across runs on the same phone and model; the 1.9x reading is the most recent. Vulkan on
the same device fails:
75% agreement with a single offloaded layer, collapsing to all-zero logits past about seven, with
no error reported by the driver. Vulkan compiles and is offered, but is not validated.

**Conversations.** Persisted as one JSON file each with a rebuildable index. Multiple threads, New
and History, titles derived from the first message, restored on launch. Survives `force-stop`.

**Agentic transcript.** Messages carry ordered activity entries — reasoning and tool invocations —
rendered as collapsed single lines that expand on tap, and persisted with the conversation.

**Background runs.** Agent work belongs to an application scope and holds a foreground service, so
a run continues and writes its reply when the user leaves the app.

**Chat quality of life.** Retry, edit-and-resend, copy, Markdown rendering, and auto-scroll that
follows a streaming reply.


## In flight on `milestone-9-glass-ui`

Not on `main` yet, and not opened as a pull request. Verified on the S25 Ultra:

- A translucent interface built on the Haze library for real backdrop blur, a bubble top bar with a
  burger menu and status pill, and a dark field with a fine dot lattice behind it.
- The last loaded model is reopened on launch. Auto-load had never worked: the startup path built
  the catalog itself and never called the restore, so every launch after the first landed in a chat
  whose send button silently did nothing.
- Reasoning is split from the answer on every token rather than once the reply finishes, so a closed
  `<think>` block folds into its collapsed row immediately instead of sitting in the transcript as
  raw markup.
- An accelerator run restores whatever model was loaded when it started. It used to leave the
  runtime unloaded, stranding the chat in the same silent dead end as the auto-load bug.
- A GGUF's `general.name` is ignored when it looks like a commit hash, which is what LFM2.5 ships.

## What is not implemented

- **Model download.** Getting a GGUF in still needs a browser and the file picker. Deliberately
  deferred.
- **Task queue and scheduling.** One run is tracked at a time. There is no queue, no scheduled work,
  and no per-task UI. `AgentTaskService` is the foundation for it, not the finished thing.
- **Tools.** Only the read-only `device_status` tool exists. The transcript can display tool
  activity, but there is little for it to display.
- **Memory, skills, automations.** Interfaces only.
- **OpenCL and LiteRT.** Not implemented.
- **Remote endpoints.** Optional and non-streaming.

## Known issues

**Qwen3.5's chat template does not render.** It iterates `messages[::-1]`, which llama.cpp's Jinja
engine (minja) yields nothing for, so the template raises `No user query found in messages`. Bram
falls back to llama.cpp's built-in templates. Two consequences: the model emits its own turn header
and empty `<think>` markers, which Bram strips after parsing; and its reasoning arrives as unmarked
prose, so it cannot be folded into a collapsed entry. Turning reasoning off is the practical fix
and is the default.

**Reasoning tags come from the runtime, but unmarked prose still cannot be folded.** llama.cpp
reports the loaded format's markers, and Qwen3.5 reports `<think>` with two closing tags,
`</think>` and `<tool_call>`. Its own template does not render, though, so Bram falls back to a
built-in one and the model reasons in unmarked prose — nothing marks it, so nothing can collapse it.

**The tool path runs end to end on the emulator, through a fallback.** LFM2.5 writes a bare
`[write_note(name='q', body='z')]` without the marker its format requires; the parser and a forced
retry both decline it; a fenced fallback recovers it; and the approval card appears. Left
unanswered it was refused after two minutes and nothing ran. A recovered call always asks — it
never matches a remembered allowance — because it is text read as an intent rather than the format
saying so.

**The UI cannot be read by automation while a turn is running.** `uiautomator dump` needs an idle
window and the send button animates continuously during generation, so the accessibility tree is
never dumpable mid-turn. Screenshots still work. This is why the approval card's accept branch is
still unverified: the card only exists mid-turn.

**Tools need the model's own template to render.** Tool definitions are passed to
`common_chat_templates_apply`, which produces the grammar that constrains a tool call. When a
template fails to render, Bram falls back to a built-in one, and that path has no tool support: the
tools are accepted and silently ignored. Qwen3.5 is in exactly that state, so it cannot call a tool
however it is asked.

**Reasoning is expensive.** A reasoning model asked to say hello can spend paragraphs deliberating.
Reasoning is off by default, per model.

**Vulkan's failure is in a shared operation, not a layer.** Bisection on the Adreno 830 shows even
a single offloaded layer disagreeing, so the fault is in something every layer uses. Nothing has
been attempted to fix it; the backend is offered and reported as unvalidated.

## Build

See [Building Bram](BUILDING.md) for the full toolchain. The parts that are easy to get wrong:

- **CMake 3.30.5**, not the NDK default, because the Vulkan backend needs FetchContent's
  `find_package` redirect.
- **WSL is the recommended local environment.** Incremental native rebuilds take about 8 seconds
  there against roughly 12 minutes in CI, and its Linux host compiler avoids the MSVC requirement
  Windows hosts hit when building `vulkan-shaders-gen`.
- **Hexagon is opt-in** through `HEXAGON_SDK_ROOT`, since the SDK is proprietary and absent on CI.
- **Emulator builds are opt-in** through `BRAM_EMULATOR_ABI=true`, which adds `x86_64`. Bram is
  otherwise ARM64-only and its native library cannot load on a stock emulator image.
- Keep `ninja` on `PATH`; several native sub-builds inherit the generator without the make program.

## Testing

CI runs JVM tests, Android lint, and a full native build with CPU and Vulkan. It cannot build
Hexagon, which needs the proprietary SDK, and it cannot run any accelerator correctness test, which
needs real hardware.

Unit tests cover accelerator agreement scoring, offload bisection, and Markdown rendering. The
accelerator tests exist because two earlier acceptance criteria produced confidently wrong verdicts
on device; the cases that misled us are pinned.

For anything touching inference or the UI, run it on the emulator. Three separate defects in this
codebase compiled cleanly, passed CI, and were only visible when the app actually ran.

## Working agreements

- Verify accelerators against CPU output rather than benchmarking them. A speed-only comparison
  reported a broken Vulkan backend as working.
- Compare with teacher forcing, not free-running generation. One differing token otherwise sends
  the rest of the reply somewhere unrelated, and a small numerical difference becomes
  indistinguishable from a broken kernel.
- Do not report an accelerator as validated because it compiled and produced output.
- Squash-merge one commit per milestone.

## Next

Milestone 7 is in progress: KV reuse across turns is committed but not yet verified on hardware.
Then Milestone 8, model profiles, and the agent milestones 9 to 16, which start with turning the
tool approval gate into something that actually asks.

Hexagon is validated but nothing routes chat to it by default; Vulkan needs its shared broken
operation identified before it is worth offering. Model download is explicitly not wanted.
