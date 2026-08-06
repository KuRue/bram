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
- Model import verification. Built-in downloads and resumable transfers remain deferred.
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

Deferred to a later milestone: OpenCL, LiteRT, automatic plan selection and probation runs,
storage-assisted mode, and a multi-vendor device matrix. KV and batch tuning became Milestone 7.

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

## Milestone 7 — runtime performance (in progress)

Promotes the deferred "KV/batch tuning" line above to a milestone of its own, because measurement
showed it is the largest user-visible cost left. Every turn built a fresh `llama_context` and
re-decoded the whole prompt, so at the measured 16.2 tok/s prompt speed a conversation grown to
2,000 tokens spent about two minutes before its first token, worsening with every turn.

- **KV reuse across turns.** Keep the context alive between turns, keep the longest common token
  prefix, and decode only what is new. Implemented and correct, but it buys nothing on the current
  reference model: `LFM2.5-2.6B` is a hybrid convolution/attention architecture, and llama.cpp
  refuses to partially erase such a sequence because the state is not kept per token — Mamba and
  RWKV behave the same way. Measured on the phone as `prompt 909 tokens, matched 816, reused 0`:
  the prefix matching works, the trim is declined, and the cache is correctly discarded rather
  than trusted. Needs a pure-attention model to demonstrate the gain, and the app should say which
  of the two a loaded model is rather than leaving it to a log line.
- **KV cache quantization.** `type_k`/`type_v` at `q8_0` roughly halves KV memory. The payoff is
  context length within a phone's RAM rather than speed.
- **FlashAttention.** Per-backend rather than global: supported on CPU, and to be confirmed on the
  Hexagon HTP path before being enabled there. Also a practical prerequisite for quantized KV.
- **Batch tuning.** `n_ubatch` is pinned at 128; both it and `n_batch` should follow measured
  prompt throughput instead of a fixed guess.

Exit criterion: each optimization reproduces the CPU reference under teacher forcing before it is
reported as working. A prefix-matching bug produces plausible wrong output rather than a crash,
which is the failure mode that already made a broken Vulkan backend look healthy.

## Milestone 8 — model profiles (not started)

Replaces the model card with saved configurations. Per-model preferences already exist as fields on
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

## Milestone 9 — permissions and the tool contract (not started)

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

## Milestone 10 — local tool calling (not started)

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
fires when it does not and stayed silent on a further run. So an eager, attached, 2363-byte grammar
still permits the reply LFM2.5 gives. Forcing the tool choice does not make this model emit the
marker its own format requires.

That closes the REQUIRED route for this model. What remains is a model that emits the marker, or
accepting bare calls as a fallback parse. If the fallback is taken it should be fenced: only when
tools were offered this turn, only when the name matches a registered tool, only at the start or end
of a reply rather than mid-prose, and never eligible for an "always allow" match, so a recovered
call always asks. A model echoing tool output containing a call-shaped string is the case those
fences exist for.

The alternative remains accepting bare calls as a fallback parse, which trades correctness for
compatibility — a model echoing tool output containing a call-shaped string would then trigger one —
and should be a decision taken deliberately rather than by default.

## Milestone 11 — Android tool surface (not started)

The tools worth having on a phone are the platform's own, not a filesystem.

- Files, HTTP fetch, and clipboard.
- Intents, so Bram can hand work to whatever app already does it.
- Calendar, contacts, and notifications behind runtime permissions.
- Alarms and scheduling, which is what makes unattended work possible at all.

## Milestone 12 — Termux integration (not started)

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

This is the widest capability Bram will have: arbitrary command execution as the user. It lands
after Milestone 9 and not before.

## Milestone 13 — sessions and the task queue (not started)

- Named sessions that outlive a turn, with scrollback the agent can page through rather than
  re-read whole.
- A real queue with scheduled execution and result notifications. `AgentTaskService` tracks one run
  and is the foundation for this, not the finished thing.
- Per-task UI: what is running, what it has done, and how to stop it.

## Milestone 14 — context compaction and memory (not started)

- Summarise what falls out of the context window instead of dropping it. The budgeter currently
  records what it omitted but does nothing with it.
- Room-backed run journal, memory provenance, and FTS retrieval.
- Optional embeddings and a vector index, selected per device.

Depends on Milestone 7: compaction costs a model call, and on a phone that is the same
prompt-reprocessing cost that KV reuse exists to remove.

## Milestone 15 — remote MCP (not started)

- Streamable HTTP transport only, with `Authorization` headers and server identity pinned per
  configured server.
- Documented as a limitation with its reason, since most published MCP servers are stdio and will
  never run here.
- Tool descriptions from a server are untrusted input, and are subject to Milestone 9 like any
  other tool.

## Milestone 16 — skills and automations (not started)

- Versioned skill packages with validation, drafts, activation, and rollback.
- Automations built on the scheduling from Milestone 10 and the queue from Milestone 12.

## Milestone 17 — curated runtimes and routing (not started)

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
- Tool permission tests that assert a side effect cannot be reached without a recorded decision.
- Termux integration tests for the three ordinary failure modes: not installed, external apps not
  allowed, and permission refused.
