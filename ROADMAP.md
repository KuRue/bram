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
- Model import verification.
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

Deferred to a later milestone: LiteRT, probation runs, storage-assisted mode, and a multi-vendor
device matrix. KV and batch tuning became Milestone 7. OpenCL landed as Milestone 8c, and automatic
plan selection as Milestone 8d.

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

## Milestone 7 — runtime performance (complete)

Promotes the deferred "KV/batch tuning" line above to a milestone of its own, because measurement
showed it is the largest user-visible cost left. Every turn built a fresh `llama_context` and
re-decoded the whole prompt, so at the measured 16.2 tok/s prompt speed a conversation grown to
2,000 tokens spent about two minutes before its first token, worsening with every turn.

- **FlashAttention and KV cache quantization as profile settings.** `flashAttn` (Auto/On/Off)
  and `kvCacheType` (F16/Q8_0) are per-profile settings, threaded from the profile card through
  the load request to `llama_context_params` (`flash_attn_type`, `type_k`, `type_v`). A change
  to either unloads the running model, and the inference service's load-identity cache treats
  them as part of the identity, so a stale context is never reused for a different attention or
  KV configuration. A quantized V cache requires flash attention, so the profile UI couples the
  two. Auto-configure, validate, and bisect all run under the profile's settings, since scoring
  an accelerator under different ones would validate a mix the profile can never reproduce.
  Measured on the S25 Ultra with `LFM2.5-2.6B-Q4_0`: flash attention runs on the Hexagon HTP
  path (Auto and On both work, ~34-35 tok/s prompt, ~13 tok/s generation), and Q8_0 halves the
  KV cache (512 to 272 MiB at 32K context) at the cost of generation speed on the NPU (dequant
  overhead). The existing Auto/F16 defaults remain the best all-round choice.
- **KV reuse across turns.** Keep the context alive between turns, keep the longest common token
  prefix, and decode only what is new. Implemented and correct, but it buys nothing on the current
  reference model: `LFM2.5-2.6B` is a hybrid convolution/attention architecture, and llama.cpp
  refuses to partially erase such a sequence because the state is not kept per token — Mamba and
  RWKV behave the same way. Measured on the phone as `prompt 1021 tokens, matched 960, reused 0`:
  the prefix matching works, the trim is declined, and the cache is correctly discarded rather
  than trusted. A pure-attention model such as Qwen3.5 is needed to demonstrate the gain.
- **Batch tuning.** `n_batch` and `n_ubatch` are per-profile settings (0 meaning llama.cpp's
  defaults of 512/128, which is what Bram always ran), threaded from the profile card through
  the load request to `llama_context_params` and into the service's load-identity cache, so
  changing the batch reloads the model instead of silently reusing a context built for another
  configuration. "Tune batch" measures the profile's own backend under teacher forcing: each
  candidate must meet the same 90% agreement bar as the accelerator comparison before its speed
  counts, scored on the greedy reference decode's prompt phase (now timed in the JNI layer). The
  winner is written to the profile with a dated note. Measured on the S25 Ultra with
  `LFM2.5-2.6B-Q4_0` on the Hexagon HTP: all four candidates (256/128, 512/128, 512/256, 1024/128)
  reproduced the reference at 95.8%, and 512/256 won prompt speed (35 tok/s).
- **Reuse count in the UI.** The composer's metrics line now appends "KV reuse: N of M tok" from
  the `cachedPromptTokens` the JNI layer reports with the load response, so whether a loaded
  model can reuse its KV cache is read from the screen rather than a log line. It reports what
  was actually reused — honestly zero on the hybrid LFM2 model, most of the context on a
  pure-attention one.

Exit criterion: each optimization reproduces the CPU reference under teacher forcing before it is
reported as working. A prefix-matching bug produces plausible wrong output rather than a crash,
which is the failure mode that already made a broken Vulkan backend look healthy. Met: the new
settings ship defaulted to the validated Auto/F16 configuration, which was the teacher-forced
baseline before this milestone.

## Milestone 8 — model profiles (complete)

Replaced the model card with saved configurations. Per-model preferences already exist as fields on
the GGUF record; this makes them a named record instead, many profiles to one file.

- A profile owns a name, a GGUF reference, sampler settings, context size, backend, reasoning
  on/off, and a system prompt.
- Sampler settings become per-profile rather than fixed. Only temperature currently crosses the
  process boundary; `top_k` is pinned at 40 and `top_p` at 0.95 in the JNI layer.
- The status pill selects a profile rather than a model.
- Carry the loaded format's reasoning tags across the process boundary while that boundary is open.
  `common_chat_params` already computes `supports_thinking`, `thinking_start_tag`, and
  `thinking_end_tags`, and the JNI layer already captures it for the end-of-turn parse. The
  streaming split in the app hardcodes `<think>`/`</think>` instead, which covers the Qwen and
  DeepSeek families and no others — llama.cpp also emits `[THINK]`,
  `<|channel|>analysis<|message|>`, and `<mm:think>`, and some formats close with more than one tag.
  Whether the block starts open is inferred from the model's reasoning setting for the same reason,
  when the generation prompt says so outright.

Ordering note: Milestone 7 lands first so profiles have the KV and attention settings to expose,
rather than needing a second pass to add them.

## Milestone 8b — profile-first, and provisioned for the user (complete)

Profiles exist and drive loading, but the screen is still a list of files with profile controls
nested inside each one. The asked-for shape is the other way round: a list of profiles, each naming
the GGUF it runs, because the profile is what a person picks and the file is an attribute of it.

Making it profile-first is the small half. The larger half is that creating one should not require
knowing anything:

- **Name it and pick a GGUF.** That is the whole of what the user supplies.
- **Defaults come from the file.** Context sized from the trained maximum and what the device can
  actually hold rather than a fixed 8K; reasoning off unless the template supports it; sampling from
  the format's own conventions where it has them.
- **On save, measure it.** Run the existing teacher-forced comparison against every backend the
  build and device offer, and record agreement and speed for each.
- **Choose the fastest backend that agrees with CPU.** Correctness gates speed, never the reverse —
  the whole reason that harness exists is that a broken Vulkan backend once reported success while
  returning garbage, and a "fastest" choice made without it would have picked exactly that.
- **Say why.** "Hexagon: 96% agreement, 1.9x CPU" beside the choice, with the date it was measured.
  A silent automatic decision the user cannot inspect is worse than a manual one.
- **Let it be redone and overridden.** Thermal state, free RAM, and a new build all change the
  answer, so the measurement is a fact with an expiry rather than a property of the file.

Two constraints this has to respect. A comparison takes on the order of a minute per backend, so
provisioning belongs on the foreground-service path with visible progress, not behind a modal wait —
which ties it to the task queue in Milestone 13. And it must degrade quietly: no accelerator present
means CPU, every accelerator failing validation means CPU, and neither is an error.

Result: complete. Profile-first landed as #17; the provisioning half (measure every backend, choose
the fastest that agrees with CPU, say why, allow it to be redone) landed as Milestone 8d (#20), which
records each measurement on the profile so the card shows the full shape of the device rather than a
sentence about the winner.

## Milestone 8c — OpenCL for Adreno (complete)

The backend builds for ARM64 and the driver comes up on the S25 Ultra:

    ggml_opencl: selected platform: 'QUALCOMM Snapdragon(TM)'
    ggml_opencl: device: 'QUALCOMM Adreno(TM) 830 (OpenCL 3.0 Adreno(TM) 830)'

Two things it needed. The Khronos ICD loader finds nothing here — it enumerates drivers through an
ICD registry and there is no `/vendor/Khronos/OpenCL/vendors` to enumerate — so the library links
the vendor's own `libOpenCL.so`, which the platform lists publicly, declared with
`uses-native-library` as the Hexagon driver is. And that copy must be excluded from packaging:
shipping it put a second one in the APK, Android preferred it, and it failed on `libcutils.so`,
taking the whole native library down.

**Loading a model then aborts** in `ggml_backend_dev_type` under `load_tensors`, whatever backend is
requested — CPU included. So registering the OpenCL backend breaks loading rather than only
offloading to it. The cause turned out to be the device list handed to `ggml_backend_sched_new`,
which requires the CPU backend to be last: Bram's accelerator-only list violated that, and appending
the CPU device after the matched accelerator fixed it. Validated on the S25 Ultra at 96% (23/24)
teacher-forced agreement, 1.11x vs CPU on Qwen3.5-Q4_0 (#18). OpenCL is opt-in
(`BRAM_OPENCL=true`) and needs the uncommitted `runtime/llamacpp/src/main/cpp/
opencl-stub/libOpenCL.so` vendor stub, so CI cannot build this backend.

Two cautions for whoever picks this up. `GGML_OPENCL` is FORCE-set into the CMake cache, so turning
`BRAM_OPENCL` off does not remove it — the staging directory has to be deleted, exactly as the
Hexagon regression required. And the linker needs a copy of the vendor `libOpenCL.so` pulled from a
device, which is not committed: it is Qualcomm's binary and device-specific, so CI cannot build this
backend at all.

## Milestone 8d — auto-configure (complete)

Auto-configure measures every accelerator the build and device offer against the CPU reference,
records each score on the profile, and picks the fastest backend that agreed (#20). The card lists
the results as measured — "NPU 1.4x" beside "Vulkan FAIL" — rather than a sentence about the winner,
so what was rejected and why stays visible.

- A failed backend is a recorded result, not an error; agreement gates everything, speed only ranks
  the backends that passed.
- Backend detection now asks the runtime synchronously before choosing. The inference process
  restarts across loads, and a backend that registered late was missing from the start-up list, so a
  profile configured for it silently loaded on the CPU.
- The run is watchable: a step counter, a live status per candidate, and results that fill in as
  each measurement lands, in an overlay that blurs the app behind it. The overlay is drawn in the
  app tree rather than a Dialog window, for the reason the panels and drawer already learned: a
  separate window has nothing of the app behind it to sample and cannot be frosted.

### Why it is worth trying

Vulkan on the Adreno 830 fails validation in an operation every layer uses, and nothing has been
done about it. ggml also has an OpenCL backend tuned specifically for Adreno, which is the path
Qualcomm and llama.cpp generally point to on Snapdragon, and it is plausibly why the Vulkan bug has
gone unchased upstream. It is listed as deferred above; on this hardware it is probably the better
GPU bet, and the harness needed to prove or reject it already exists.

## The agent milestones

Milestones 9 to 17 turn Bram from a chat app with one tool into an agent harness. They are ordered
by dependency rather than by appeal. The permission model comes first because its current stub is
what limits Bram to a single read-only tool: anything with an effect is denied, so every tool added
before it is a tool that cannot run.

Two platform facts shape all of it, and are recorded in
[the architecture notes](ARCHITECTURE.md#running-code-on-android):

- Bram cannot execute binaries it downloads. Android blocks `execve()` on files in an app's data
  directory for apps targeting API 29 and above, which Bram does.
- Therefore Bram cannot host stdio MCP servers, since those are spawned as child processes, and it
  cannot ship its own shell without a Termux-sized userland.

## Milestone 9 — permissions and the tool contract (complete)

The seam exists: `ToolApprovalGate` sits in the tool loop, tools already carry `readOnly` and
`requiredPermissions`, and a denial already comes back as a `permission_denied` tool result rather
than an exception. What is missing is a decision. The only implementation, `ReadOnlyApprovalGate`,
allows read-only tools with no required permissions and denies everything else, which is why Bram
has exactly one tool worth calling.

- A gate that asks the user, rather than one that answers on their behalf.
- Calls that need approval appear in the transcript with a preview of what will happen, and block
  until answered.
- Decisions are remembered per scope — this tool, this session, this target — rather than globally
  or once per call.
- Timeout is an ordinary tool result like denial, so an unattended run ends in a recorded refusal
  instead of hanging.

Exit criterion: a tool cannot reach a side effect without a recorded decision, and a denied call
leaves the run able to continue.

## Milestone 10 — local tool calling (complete)

Found while trying to exercise the approval gate on a device: **the local runtime never sends
tools**. `GenerationRequest.tools` is populated and reaches `LlamaCppRuntime`, which passes the
request to `LlamaCppServiceClient`, which builds the JSON without them. The JNI `formatChat` takes
roles and contents only. So a local model is never told a tool exists and cannot call one, whatever
its ability — the remote OpenAI-compatible path is the only one that offers tools at all.

Everything agentic depends on this. The permission gate cannot be reached on device, and Milestones
11 and 12 build on tool calls that a local model currently cannot make.

- Pass tools through `common_chat_templates_apply`, which llama.cpp already accepts them for, and
  carry the grammar it returns into generation so the reply is constrained to the format.
- Parse tool calls out of the finished reply. `common_chat_parse` already returns them and Bram
  already calls it for reasoning; the calls are discarded.
- Report honestly when a loaded model's template has no tool support, rather than offering tools
  that will never be called. `chatFormat` reports `supportsTools`; nothing consumes it yet.

Measured on the emulator twice. With `Qwen3.5-0.8B` the tools reach the template (`tools=2`) but come
back `supportsTools:false` with no tool definitions in the prompt. Tool support rides on the model's
own Jinja template, and Qwen3.5's does not render under minja — Bram falls back to a built-in
template, and the built-in path has no notion of tools. So local tool calling works only for models
whose own template renders, which is a narrower claim than "local tool calling works" and needs
saying in the UI rather than discovered.

With `LFM2.5-2.6B`, whose template does render, the model was told about the tool and asked for it:
it replied `<think>[write_note(name='shopping', body='milk')]` — the right tool with the right
arguments. The call is still not executed, because it arrives as text rather than as a parsed tool
call. Both questions have since been answered by measurement.

**The grammar was being applied wrongly.** LFM2.5 returns `grammar=956 lazy=1`: a *lazy* grammar,
which only engages once a trigger appears, and Bram was installing it with the eager constructor and
no triggers. It now uses `llama_sampler_init_grammar_lazy_patterns` with the triggers the template
supplies. That fixed the reasoning split — content and reasoning now separate correctly instead of
the reply arriving as one `<think>`-prefixed blob.

**The parse still yields nothing, and the reason is the model.** LFM2.5's format expects
`<|tool_call_start|>` before the call, and `common_chat_parse` requires that literal. The model
writes a bare `[write_note(name='x', body='y')]` — the right tool and arguments, without the marker.
A lazy grammar constrains what follows a trigger; it cannot make a model emit the trigger. So the
remaining gap is a 2.6B model not following its own format's convention, not a wiring fault.

A required-tool retry is now implemented: when the first reply names a tool but the parser accepts
nothing, the turn is asked again with `COMMON_CHAT_TOOL_CHOICE_REQUIRED`, which makes the grammar
eager so the call is constrained as it is written and comes back through the real parser. The
pattern match only decides whether to ask again — it never produces a call, so a false positive
costs one generation rather than an unintended action.

**It fires, and it does not help this model.** Measured on the emulator: the first pass reports
`tools=3 grammar=956 lazy=1`, the retry is entered (`calls=0 wantsRetry=true`), and the retry
template reports `require=1 grammar=2363 lazy=0` — eager, as intended. The model still replies with
a bare `[write_note(name='x', body='y')]`, the parser still accepts nothing, and no note is written.

The grammar is not the problem either: it compiles and is attached, verified by a warning that now
fires when it does not and stayed silent on a further run.

**The parse was.** Running the same model with the same tool through llama.cpp's own `llama-server`
returned `finish_reason: tool_calls` with correct arguments in under two seconds, which ruled the
model out and pointed back at Bram. `common_chat_parser_params(const common_chat_params &)` copies
only the format and the generation prompt — not the `parser` the template built. Bram was parsing
every reply with an empty parser, so a marked tool call was unrecognisable. Loading it with
`common_peg_arena::load` is the fix, and the tool loop now runs end to end on the emulator.

The conclusion recorded here before — that a small model was failing to follow its own format — was
wrong. The model had been producing what its format asks for; Bram could not read it.

**The other half was that Bram was deleting the marker before parsing.** `llama_token_to_piece` was
called with `special = false`, which renders a special token as an empty string. The model had been
emitting `<|tool_call_start|>` all along and Bram was erasing it, then parsing what was left and
finding a bare call. Generation now keeps a second copy of the reply with special tokens intact —
the transcript still gets the display form, since nobody wants to read a marker — and the parser
reads that.

With both fixes the whole loop runs on the emulator: the model calls `write_note`, the call is
parsed, the approval card appears with the arguments shown, `Allow once` executes it, and
`files/notes/marker` contains `found`. Milestone 9's gate is verified end to end, including the
accept branch.

The forced retry and the bare-call fallback stay. Removing them was tried again with both fixes in
place and the reply came back as text, so the marker is emitted some turns and not others — the one
successful marked call was a sample, not proof of reliability. At temperature 0.7 that is what a
sampled model does, and a lazy grammar only engages once the marker appears. The workarounds are
what make the path dependable rather than lucky.

Verified on the S25 Ultra as well as the emulator: the card appears at 9.8 tok/s, `Allow once`
executes, and `files/notes/phone` contains `works`.

The earlier note about the parser fix being insufficient is kept below for the record.

The parser fix alone was not sufficient. Removing the retry and the bare-call fallback and
running a clean turn put the reply back to text: `[write_note(name='clean', body='works')]`, no call,
no approval. So a difference between Bram and `llama-server` remains, and the workarounds stay until
it is found. The useful thing is that there is now a working reference to diff against, running the
same model from the same pinned revision. The next things to compare are the sampler chain — the
server builds it through `common_sampler_init`, which carries `grammar_triggers` and
`preserved_tokens`, while Bram assembles it by hand — and the tool definitions themselves, since
Bram hand-parses them into `common_chat_tool` rather than going through the OpenAI-shaped conversion
the server uses.

That closed the REQUIRED route for this model, so the bare-call fallback was taken, fenced as
below. On the emulator the whole chain now runs: LFM2.5 writes a bare call, the parser declines, the
forced retry declines, the fallback recovers it, and the approval card appears in the transcript
naming the tool. Left unanswered for two minutes it was refused and the tool did not run, which is
the timeout fence behaving as designed. A granted approval writing the note has not been observed
yet — that is the one step left to confirm.

The fallback is a compatibility shim, not the intended path. What remains is a model that emits the
marker. If the fallback is taken it should be fenced: only when
tools were offered this turn, only when the name matches a registered tool, only at the start or end
of a reply rather than mid-prose, and never eligible for an "always allow" match, so a recovered
call always asks. A model echoing tool output containing a call-shaped string is the case those
fences exist for.

The alternative remains accepting bare calls as a fallback parse, which trades correctness for
compatibility — a model echoing tool output containing a call-shaped string would then trigger one —
and should be a decision taken deliberately rather than by default.

## Milestone 11 — Android tool surface (complete)

The tools worth having on a phone are the platform's own, not a filesystem.

- Files, HTTP fetch, and clipboard.
- Intents, so Bram can hand work to whatever app already does it.
- Calendar, contacts, and notifications behind runtime permissions.
- Alarms and scheduling, which is what makes unattended work possible at all.

Result: complete. The tool surface grew to fifteen handlers: `device_status`, `scratch_note`,
`web_search`, `web_fetch`, `files_list`, `file_read`, `clipboard_read`, `clipboard_set`,
`notify`, `schedule_notification` (with a broadcast receiver that turns the alarm into the
notification it was asked for), `open_uri`, `contacts_search`, `calendar_events`,
`termux_exec` (Milestone 12), and `memory_search` (Milestone 14). The runtime-permission seam
works end to end: a tool declares the Android permissions it needs, an approved call asks for a
missing one through the system dialog via the permission broker, and the answer resolves the
broker so the tool either runs or returns `permission_denied`. Calendar and contacts sit behind
`READ_CALENDAR`/`READ_CONTACTS`, and the reminder tool falls back from exact to inexact alarms
when the special app-op is not granted. Notifications and alarms added the `POST_NOTIFICATIONS`
and `SCHEDULE_EXACT_ALARM` declarations to the manifest.

## Milestone 12 — Termux integration (complete)

Gives the agent a real toolchain — compilers, package managers, git — without Bram shipping a
userland or fighting the execution restriction, because the restriction stays Termux's problem.

- Send commands through Termux's `RUN_COMMAND` intent to `com.termux/com.termux.app.RunCommandService`.
- Collect stdout, stderr, and exit code back through a `PendingIntent` result bundle. Separate
  streams are only available for background commands; a session transcript interleaves them.
- Results are truncated to about 100 KB of combined output, with the original lengths supplied
  alongside, so the agent must be able to tell a truncated result from a complete one.
- Give each command its own result directory, since concurrent commands otherwise collide.
- Degrade honestly when Termux is absent, when `allow-external-apps` is unset in
  `~/.termux/termux.properties`, or when the `com.termux.permission.RUN_COMMAND` permission is
  refused. All three are normal, and none should look like a crash.

Result: complete. `termux_exec` sends through the `RUN_COMMAND` intent, receives the result in a
per-command result directory (each call gets its own subdirectory so concurrent commands do not
collide), and reports stdout, stderr, the exit code, and the truncation state — the original
stream lengths plus a `truncated` flag, so a result cut to the 100 KB ceiling is distinguishable
from a complete one. The three ordinary failure modes are explicit tool results, not crashes:
Termux not installed, `allow-external-apps` not set, and `RUN_COMMAND` permission refused each
return a `permission_denied`-shaped error the model can read. Package visibility for `com.termux`
is declared in the manifest.

## Milestone 13 — sessions and the task queue (complete)

- Named sessions that outlive a turn, with scrollback the agent can page through rather than
  re-read whole.
- A real queue with scheduled execution and result notifications. `AgentTaskService` tracks one run
  and is the foundation for this, not the finished thing.
- Per-task UI: what is running, what it has done, and how to stop it.

Result: complete. A task is a named prompt that runs in its own conversation — the named session
its reply lands in and that can be reopened from the library. The queue (`AgentTaskRunner`) runs
one task at a time on the application scope, persists every state change to a single JSON file,
and wakes the app for a scheduled task through an alarm receiver; a task whose time comes while
no model is loaded is deferred rather than failed, and starts as soon as one loads. The Tasks
panel (drawer > Tasks) shows the queue with per-task state, schedule, activity log, result or
error, and Cancel/Run again/Delete; a finished task posts a result notification when the app is
backgrounded, and a deferred task posts that it is waiting for a model. Tasks run under AUTO
permission mode, so remembered allowances still apply and side-effecting calls without one are
refused after the approval timeout — an unattended run ends in a recorded refusal rather than a
hang. The scrollback-paging half of the first bullet is subsumed by Milestone 14's memory
retrieval: the agent now recalls what fell out of a long conversation as a summary instead of
paging through raw scrollback.

## Milestone 14 — context compaction and memory (complete)

- Summarise what falls out of the context window instead of dropping it. The budgeter currently
  records what it omitted but does nothing with it.
- Room-backed run journal, memory provenance, and FTS retrieval.
- Optional embeddings and a vector index, selected per device.

Result: complete (embeddings deferred, see below). When `ContextWindowManager.plan` omits
messages, the orchestrator now runs one bounded summarization generation — head and tail of the
omitted stretch, 384 output tokens, greedy — and writes the result as a `WORKING_SUMMARY` memory
record carrying the message ids it replaced (the provenance), then re-plans so the model sees the
summary in the same turn. A failed compaction never fails the run; it simply skips the summary.

Memory and the run journal moved out of RAM into a SQLite database with an FTS4 index, in
`platform:android`. Room was deliberately set aside: the schema is what Room would generate, but
pulling Room and KSP into the build for two tables adds a compiler-plugin dependency for nothing
needed yet. `PersistentMemoryStore` keeps `memory_records` (kind, text, importance, source, the
message ids a record came from) in sync with the FTS index through triggers, and serves both the
per-conversation retrieval the orchestrator already used and a new cross-conversation
`searchAll`. `SqliteRunJournal` records every run — chat turns and tasks alike — with its tool
turns, token totals, and how it ended; the Settings diagnostics card lists the most recent runs.
The agent gained a `memory_search` tool, read-only and permission-free, so a task can recall what
earlier conversations decided. Two JVM tests cover the happy path (omission → summary in memory
and in the next prompt, journal entry with token totals) and the failure path (a dead summarizer
does not end the run).

Deferred: on-device embeddings and a vector index. An embedding model is another multi-hundred
megabyte import that must be validated like any other model, the FTS retrieval already covers
keyword recall, and `searchAll` is the seam the vector index would replace — no code above it
needs to change when one lands.

## Milestone 15 — remote MCP (complete)

- Streamable HTTP transport only, with `Authorization` headers and server identity pinned per
  configured server.
- Documented as a limitation with its reason, since most published MCP servers are stdio and will
  never run here.
- Tool descriptions from a server are untrusted input, and are subject to Milestone 9 like any
  other tool.

Result: complete. A configured MCP server (`McpServer` in core:domain) is an HTTP(S) endpoint
speaking the streamable-HTTP transport with protocol version 2025-03-26; the Settings panel adds
one with a name, URL, optional bearer token, and an explicit opt-in for plain HTTP. The client
(`McpClient` in the app module, plain `HttpURLConnection`, no framework) does the handshake —
`initialize`, then the `initialized` notification, capturing the `Mcp-Session-Id` — lists tools,
and calls them. The identity is pinned: requests only ever go to the configured origin, redirects
to another host are refused rather than followed, and the Authorization header therefore never
leaks to a third party; responses are capped in size and accepted as JSON or SSE. A tool call
runs against a fresh session per invocation, so a killed process can never half-replay a stale
one.

The transport limitation is deliberate and documented at the model: stdio servers spawn a child
process, which the Android platform cannot let an app do, so they could never run here even if
they were wired up. What is on the wire is still a tool surface on a server the user chose, so
server-provided names, descriptions, and schemas are parsed defensively (hostile tool names are
dropped, descriptions and schemas capped) and each listed tool becomes an ordinary
`McpToolHandler` that always reaches the Milestone 9 approval gate, with the arguments it was
called about shown for approval — nothing is approved by trusting the server. Built-in tools win
name collisions. `McpServerStore` (platform:android) persists servers with the token in the
Android Keystore like endpoint API keys; `MutableToolRegistry` (core:agent) swaps a server's
tools in on refresh, out on failure or removal, with the per-server card showing the tool count
or why the last refresh failed. Nine JVM tests cover the handshake and session-id propagation,
bearer auth, SSE responses, server-side error marking, JSON-RPC and HTTP failures, refused
cross-host redirects, and rejected arguments.

## Milestone 16 — skills and automations (complete)

- Versioned skill packages with validation, drafts, activation, and rollback.
- Automations built on the scheduling from Milestone 10 and the queue from Milestone 12.

Skills result: complete. A skill is a versioned, user-authored procedure the agent follows when
its description matches the task: a SKILL.md-style document with `name`, `version`, and
`description` in a front-matter block and the instructions below, imported through the Settings
panel from the file picker. The whole state machine is pure and in core:domain — `SkillDocument`
parses and validates (name, three-part version, description, non-empty instructions, size caps;
a bad document is rejected with the reason), `SkillLibrary` stages it, and `PersistentSkillStore`
(platform:android) keeps one small JSON file with atomic writes, the version history capped at
five (a pile of drafts can never evict the active version). The lifecycle is exactly what the
milestone asks: a new skill imports active, a new version of a known skill lands as a draft,
activation promotes the draft, and rollback restores the version that was active before, keeping
both in the package's history. Drafts are never rendered into the prompt. Active skills are
appended to every run's profile instructions — chat and tasks alike, read fresh at run start —
under an ACTIVE SKILLS section that states plainly that a skill is authored text, not code, and
is treated like any other untrusted input: it can never do anything itself or override what the
user directly asks. Fourteen JVM tests cover parsing, the draft/activate/rollback lifecycle, the
history cap, and the prompt rendering.

Automations result: complete. An automation is a named cron schedule (five fields: minute hour
day-of-month month day-of-week) whose prompt is enqueued as a task when the time comes — so the
run goes through the task queue like any other task, is journaled, defers until a model loads
when none is loaded, and lands in a conversation that can be reopened from the library. `Cron`
(core:domain) parses the shapes a phone schedule needs — `*`, single values, ranges, steps, and
comma lists; day-of-month and day-of-week follow standard semantics (both restricted = either
matches) — and computes the next fire strictly after a given time. `PersistentAutomationStore`
(platform:android) keeps `automations.json` alongside tasks and skills. `AutomationRunner` (app)
gives every enabled automation a one-shot `setAndAllowWhileIdle` alarm for its next fire and
chains the next one when it fires; the existing `TaskAlarmReceiver` was extended with an
automation-id extra, so there is still exactly one manifest receiver for all of it. Alarms are
best-effort like scheduled tasks — Android clears them on reboot and app update, and a
`BootReceiver` re-arms automations and scheduled tasks the moment the device is back, so a fire
that passed while the phone was off happens at boot (an alarm set for the past fires
immediately) rather than at the next app start. The Settings panel lists each automation with its
schedule, enabled switch, last and next fire times, and the add form validates the expression
before saving. Fourteen JVM tests cover the cron parser and the next-fire math, including
weekdays-only, quarter-hour steps, Friday-the-13th semantics, February 31st never firing, and
the strict-after guarantee that prevents a double run.

## Milestone 17 — curated runtimes and routing (routing, privacy, fallback, and the Responses adapter complete)

- LiteRT-LM packages and device-specific compiled caches.
- ~~OpenAI Responses adapter where supported~~ (complete, below).
- ~~Capability/quality/latency/battery-aware routing~~ (complete, below).
- ~~Per-conversation privacy and remote-fallback policies~~ (complete, below).
- Optional speculative decoding and task-specific worker models.

Responses adapter result: complete. `OpenAiCompatibleRuntime` now speaks both wire schemas from one
implementation, chosen per endpoint by the API kind set in the provider form (Chat Completions for
the broadest compatibility — Ollama, LM Studio, vLLM, llama.cpp server — Responses API for hosts
that expose `/responses`). The Responses path is non-streaming 2025-03-26: system messages fold
into the response-level `instructions`, the conversation becomes `input` items (`message` for
user/assistant turns, `function_call` items after an assistant message that called tools,
`function_call_output` for the results), and tool definitions go in the same `tools`/`tool_choice`
shape the Chat path uses. The parser is tolerant on purpose, per the testing matrix: content
accepted as parts or a plain string, unknown output item types skipped, usage optional, and a
`failed` status or non-2xx HTTP turns the server's `error.message` into a recoverable failure the
routing fallback can act on. RESPONSES endpoints are no longer marked unavailable: they route,
load, and fall back exactly like Chat endpoints, and the status notification says which kind is in
use. Seven contract tests run against a stub HTTP server: the Chat wire shape unchanged, the
Responses request shape (instructions/input items/tools/auth), output text parts plus function
calls plus usage, missing and extra field tolerance, failed status, HTTP error mapping, and
availability for both kinds.

Routing result: complete. A `RuleBasedModelRouter` already existed in core:agent with the
`RoutingCandidate`/`RoutingRequest`/`RoutingDecision` vocabulary, but nothing called it — the app
picked one runtime per session and every turn ran on it. Now every turn is routed: each chat send
and each queued task builds a candidate for every usable runtime (the loaded GGUF, and each
configured remote provider) and asks the router which to use. The estimates that feed the score
are honest about what they know (`RoutingEstimates`): quality tracks model size, latency tracks
size and the active accelerator, battery tracks the accelerator, and a remote provider is assumed
frontier-class with a fixed network cost — relative order is what matters, and the milestone's
later curation can replace them with measured values. Candidates carry real state too: a local
model that is not resident is marked unavailable, and a RESPONSES-only endpoint cannot run yet and
is marked so. The routing mode is a global setting (Auto, Local only, Remote only) with hard-gate
semantics; Auto weighs quality, latency, and battery together. `RoutingDecision` now carries the
rest of the eligible candidates in score order as fallbacks, and the run loop uses them: a chat
turn or task that fails on its first pick — a dead remote, a crashed local — retries down the
fallback order instead of ending, with the status line saying which runtime took over. A failed
local attempt marks the model suspect even when the fallback succeeds.

Privacy result: complete. Each conversation now has a privacy class — Standard, Prefer local, or
Local only — persisted on the conversation file like the tool-permission mode, chosen in the
Session panel, and read fresh on every open. Local only is a hard gate: remote candidates are
rejected with "Content may not leave the device" whatever the routing mode says, and a local-only
conversation's failure never falls back to a remote provider. Prefer local raises the router's
local bias instead of banning remote, and is the class for "this is sensitive, but a trusted
remote may help". The app-wide defaults — routing mode and the privacy class new conversations
start with — live in `RoutingSettingsStore` under a Routing section in Settings; scheduled tasks
run under the default privacy class, which is the honest answer for a task's yet-to-exist
conversation. One consequence of routing: "selecting" a model still loads it, but it no longer
forces every turn onto it — a loaded GGUF competes on merit, which is the point.

Tests: six router tests (privacy gate, remote-only gate, prefer-quality, fallback ordering,
fallbacks never crossing a hard gate, local bias outranking a fast remote) and five estimate
tests (loaded-only availability, size ordering, accelerator effect on latency and battery,
RESPONSES endpoints unavailable, remote quality/battery assumptions).

## Milestone 18 — a live status notification for the loaded model (complete)

A loaded model today is invisible once the app is backgrounded: the foreground service runs only
for the length of a turn, so the only way to know a reply has finished is to come back and look.
This makes the model's lifetime the unit instead of the turn's.

- The foreground service moves from per-turn to per-load. It starts when a model loads and stops
  when the last one unloads, so the process and its KV cache stay warm between turns — the way a
  server holds a model, not the way a chat app runs one request — and a turn completes whether or
  not the app is foregrounded.
- The notification is status at a glance: the loaded model and backend, and the current phase
  (idle, preparing context, thinking, calling a tool, generating), updated from the same events
  the transcript already consumes. A long run can be watched without the app open.
- A completion alert is opt-in. A separate, dismissible notification that a turn finished, gated
  by a setting, because a chime on every reply is noise for a quick chat and the whole point for a
  slow one left to run.
- `AgentTaskService` is the foundation, not the finished thing: it already holds a foreground
  service for one run; this widens its lifetime and surfaces its state.

The cost is the always-present notification Android requires of a foreground service, accepted in
return for the model staying warm and visible. This is the visible half of the background-run line
that Milestone 13 is the queue half of, and the completion alert is the same result notification
M13 names, surfaced through the system rather than the app.

Implemented: the status service now tracks the loaded model for its whole lifetime — started on
load, stopped on unload or inference-process death — and its notification reports the model,
backend, and a `ModelPhase` (idle/preparing/generating/thinking/tool) driven from the turn events
the transcript already consumes. A remote turn holds the service only for its own duration. The
completion alert is a setting (Settings > Notifications) that requests `POST_NOTIFICATIONS` when
switched on, posts when a turn finishes while the app is backgrounded, and shows a summary of the
reply (expandable) so the shade reads it before the app is opened.

Verified on the S25 Ultra: the service holds for the loaded model's lifetime with its status
notification, the permission flow and alert gating work, and a turn started foregrounded completes
after the app is backgrounded with the summary alert posting. Design note: an early build offered
an inline Reply action on the alert, but a reply to a message you cannot see yet is pointless, so
the alert now carries the reply's text instead and tapping it opens Bram.

Exit criterion: a model loaded with the app backgrounded reports its phase in a persistent
notification, a turn finishes while the app is backgrounded, and — when the setting is on — posts
a completion notification summarizing the reply that the user can act on without opening Bram.

## Testing matrix

- Pure JVM tests for context selection, routing, planning, and tool loops.
- Contract fixtures for OpenAI-compatible servers with missing/extra fields.
- Android tests for Keystore migration, process death, WorkManager, and storage permissions.
- Native correctness tests before performance tests for every backend/operator path.
- Soak tests under low memory, low battery, thermal throttling, app backgrounding, and cancellation.
- Tool permission tests that assert a side effect cannot be reached without a recorded decision.
- Termux integration tests for the three ordinary failure modes: not installed, external apps not
  allowed, and permission refused.
