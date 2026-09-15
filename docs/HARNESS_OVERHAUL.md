# Harness overhaul plan

Status: planned, 2026-09-15. An analysis of the agent harness, tools, skills, remote endpoints, and
test infrastructure, with a staged overhaul (M0–M6) and the emulator as the test vehicle.

Provenance: full read of the working tree on this date — including the uncommitted remote-endpoint
work (`git status` on `feat/expert-streaming`) — with a verification pass over every headline claim.
`file:line` references are to that tree. Where a claim is from the focused source analysis rather
than a personal re-read, the cited line is still the authority; re-check before acting on it alone.

## Why now

The harness has been limited by its test models, not just its code: `docs/HANDOFF.md` records that
LFM2.5-2.6B "is too small for reliable tool use" and Qwen3.5 cannot call tools at all (its template
fails under minja and the built-in fallback carries no tools). Every hard tool-loop bug so far was
found by borrowing a friend's model, a laptop server, or luck.

The remote-endpoint work changes that ceiling. Once an OpenAI-compatible endpoint can be configured
(`RemoteEndpoint`, `OpenAiCompatibleRuntime`, `RemoteModelCatalog`), a capable model over the
network — or over `10.0.2.2` to a host llama.cpp/Ollama/LM Studio server on the emulator — makes
real harness testing possible with zero model-size excuses. With a capable model in the loop, the
harness's own structural gaps become the bottleneck; three of them (tool history, result budgets,
approval semantics) can make an otherwise good model look broken.

One hygiene item first: that endpoint work is uncommitted on a branch owned by another workstream
(`feat/expert-streaming`; `docs/HANDOFF.md` warns about coordination). It is also the test vehicle
for everything below, so it lands first (M0).

## The harness today

```
Chat / Task (MainViewModel)
  → build AgentRunRequest (history from ConversationStore)
  → route the turn (RuleBasedModelRouter + RoutingPool + privacy class)
  → DefaultAgentOrchestrator.run:
      1. select tools once per run (ToolSelection: core set + cosine ranking, 6K-char budget)
      2. per turn: ContextWindowManager.plan (identity prompt + profile/skills + memories +
         a contiguous recent suffix; omitted messages → one WORKING_SUMMARY compaction)
      3. runtime.generate (llama.cpp local | OpenAI-compatible remote | LiteRT)
      4. tool calls → ToolApprovalGate (AUTO/MANUAL/BYPASS; scope-keyed grants;
         recovered-call / untrusted-context fencing) → handler.execute → TOOL message → next turn
         (max `maxToolTurns`, default 6)
  → persist the assistant message and display-only activity; run journal; memory extraction
```

Worth keeping, and the reason the overhaul is targeted rather than a rewrite:

- Process-isolated native inference (`:inference`) and a clean AIDL seam.
- The approval gate's recovery fences: `recovered` calls never match stored grants, never persist,
  and still ask under BYPASS once untrusted content is in the conversation
  (`app/.../ToolApproval.kt:217-224`, `core/agent/.../DefaultAgentOrchestrator.kt:240-245, 376-388`).
- Context-as-budget with compaction instead of dropping (`ContextWindowManager.kt:93-143`).
- Layered prompts: identity → profile/skills → memories, none able to displace the harness prompt
  (`ContextWindowManager.kt:61-66`).
- Event-driven transcript, run journal, memory store, and the extension seams (`ToolSelector`,
  `ToolRegistry`, runtime adapters, routing pool) that make each area below independently fixable.

## Findings

### H — Harness core

**H1. Tool history does not survive a turn (highest impact).** `ConversationStore` persists only
`id/role/content/createdAt/activity` (`platform/android/.../ConversationStore.kt:220-246`) — the
`toolCalls` and `toolCallId` fields are dropped, and TOOL messages never enter the store at all.
The next turn's `AgentRunRequest.messages` therefore contain no assistant tool calls and no tool
results; the model cannot see what it already ran, so follow-ups repeat work or invent it. Within a
single run the messages are correct (`DefaultAgentOrchestrator.kt:229-247`); the loss is at
persistence. The data survives only as display `activity` entries (`argumentsJson`, `result`), which
are exactly what a replay could rebuild from.

**H2. Tool results are unbounded context bombs.** `web_fetch` returns up to 200,000 chars
(`WebTool.kt:89`), MCP responses up to 4 MB before rendering (`McpClient.kt:258, 263-278`), and
`termux_exec` inserts `stdout`/`stderr` verbatim into the result JSON (`TermuxTool.kt:260-261`) —
`MAX_STREAM_CHARS` (50 KB, `TermuxTool.kt:400`) only drives the `*_truncated` flags, it never cuts
anything. `read_skill` returns the full body, up to the 100,000-char instruction cap
(`Skill.kt:119-122`; `SkillTools.kt:107-112`). The only backstop is `ContextWindowManager`'s
last-resort middle truncation (`ContextWindowManager.kt:105-115`), and the recorded Paris run
already aborted on `6226 + 2048 > 8192` with a skill body + 17 tool schemas in flight
(`docs/HANDOFF.md:54-60`). Every result needs a stated budget before it enters the transcript.

**H3. Tool-call IDs collide.** Local and LiteRT paths mint `call_$index` per generation
(`LlamaCppServiceClient.kt:276`, `LiteRtEngineManager.kt:160`); recovered calls use
`recovered_<name>` (`BareToolCall.kt:43`). After H1 is fixed, replayed histories will collide on
`call_id` with the OpenAI Responses API's requirement that `function_call_output` match a unique
`function_call` (`OpenAiCompatibleRuntime.kt:238, 265-268`).

**H4. No schema validation, no per-tool timeout, no parallelism.** Handlers ad-hoc-parse arguments;
a schema violation surfaces as a handler-specific error at best. A handler without its own deadline
stalls the turn (`DefaultAgentOrchestrator.kt:361` suspends on `handler.execute`). Multiple calls in
one reply run strictly sequentially even when all are read-only (`DefaultAgentOrchestrator.kt:230-247`).

**H5. Approval paper cuts.** `ALLOW_FOR_RUN` has no producer — no UI action, no notification action
(`AgentContracts.kt:142`; `BramApp.kt:3862-3866`; `AgentTaskService.kt:265-266`) — so it is dead
semantics. The notification path publishes before the pending request is visible (`ToolApproval.kt:246-252`),
so a fast action can resolve nothing and the call then waits the full 10-minute timeout. An Android
runtime-permission refusal is reported to the model as "The user declined this tool call"
(`RuntimePermissions.kt:164-165` → `DefaultAgentOrchestrator.kt:333-337`), conflating OS refusal
with user refusal. The shade cannot grant `ALLOW_ALWAYS`. The card shows only the tool name and one
argument truncated to 40 chars (`BramApp.kt:3820-3825, 3882`).

**H6. `stripBareCalls` over-reaches.** The visible-reply cleanup removes any `word(...)` shape
(`MainViewModel.kt:4723-4724`), which can delete legitimate prose such as `f(x)`.

### T — Tools

**T1. Selection degrades to "offer everything".** Any embedder failure or absence returns the full
registry (`ToolSelection.kt:50,58`) — the state that caused the recorded context blowup. There is no
similarity floor, the budget is a fixed 6,000 chars at any context size (`ToolSelection.kt:120`),
core tools are exempt from it, and selection freezes for the whole run
(`ToolSelection.kt:42-71, 107-114, 120-123`; `DefaultAgentOrchestrator.kt:87-95`).

**T2. Capability flags are decorative.** `RemoteEndpoint.supportsToolCalling` is persisted and read
but hardcoded `true` on save (`MainViewModel.kt:3389`), and `ModelCapability.TOOL_CALLING` is never
consulted before offering tools — a non-tool endpoint still receives tool definitions.

**T3. MCP sharp edges.** No `tools/list` pagination (`McpClient.kt:66-73`), no read-only
annotations (every MCP tool is side-effecting and always gated, `McpToolHandler.kt:34-39`), a fresh
handshake per call (`McpToolHandler.kt:19-20`), SSE takes the last frame without matching request
id (`McpClient.kt:172-184`), and the generated `mcp_<slug>_<name>` can exceed provider function-name
limits (`McpToolHandler.kt:28, 67-70`).

**T4. Known pending items.** The description diet (trim the offered tool descriptions) is still
open from the tools session (`docs/HANDOFF.md:33-35`), and the Android tool surface has no
screenshot/accessibility, no SAF access outside `files/agent-files`, and no per-automation
pre-authorized tool sets.

### S — Skills

**S1. No capability front matter.** `tools:`/permissions/author were designed — `SkillManifest` and
`SkillRepository` still exist as dead contracts (`core/domain/.../AgentContracts.kt:252-269`) — but
`SkillDocument` parses only name/version/description/instructions and silently drops unknown keys
(`Skill.kt:97-130`). A skill cannot guarantee its tool survives `RankingToolSelector`, and
activation cannot scope grants. This is already the top item on the skills session's own remaining
list (`docs/HANDOFF.md:35-37`).

**S2. Rollback can activate an unreviewed draft.** `versions.firstOrNull { it.version != activeVersion }`
(`Skill.kt:239`) picks the newest non-active version. After import 1.0.0 → draft 1.1.0 → activate →
draft 1.2.0, rollback makes 1.2.0 active while leaving it listed as the draft — a version the user
never approved, violating the "drafts are never followed before activation" invariant. No test
covers the multi-draft case.

**S3. No disable, no provenance.** The only way to stop a skill is destructive `remove` or a blind
rollback (`Skill.kt:41-56, 237-251`); `SkillLifecycle.DISABLED` is dead code
(`AgentContracts.kt:248`). No version records an author, so agent drafts are indistinguishable from
user imports, and an "Always" grant on `propose_skill` is a blanket, unscoped allowance
(`ProposeSkillTool.kt:25-26`). Drafts proposed during task runs refresh nothing and notify nobody
(`MainViewModel.kt:746-762` vs `4193-4200`).

**S4. Context and discoverability.** `read_skill` is one unbounded read (S/H2); `list_skills` is not
a core tool although both the prompt and `read_skill`'s error message route the model through it
(`ToolSelection.kt:107-114`; `SkillTools.kt:99`); the 0.60 draft-nudge and 0.70 skill-vs-tool
thresholds are uncalibrated beyond a log line (`SkillSelection.kt:161, 169`).

### E — Endpoints and routing

**E1. Non-streaming with no read timeout.** `stream=false` on both wire kinds
(`OpenAiCompatibleRuntime.kt:156, 206`) with `readTimeout = 0` (`OpenAiCompatibleRuntime.kt:76-118`):
a slow model freezes the UI until the whole body arrives.

**E2. Reasoning output is discarded.** Request-side `reasoning_effort` is wired
(`OpenAiCompatibleRuntime.kt:158, 207-209`), but neither `reasoning_content` nor Responses
`reasoning` items are parsed — the contract test proves a reasoning item is skipped
(`OpenAiCompatibleRuntimeTest.kt:192`).

**E3. Flat error taxonomy.** `EndpointConfigurationException` is defined and never thrown
(`OpenAiCompatibleRuntime.kt:419`), so every failure is `recoverable = true` and silently falls back.
No retry/backoff for 429/5xx.

**E4. Routing doesn't know reachability, and selection doesn't force.** Remote candidates are always
`available = true` (`app/.../Routing.kt:40-47`); `availability()` is never consulted when building
candidates. Picking an endpoint in the model pill does not force it — routing mode, pool assignment,
or a "demanding" prompt decides (`MainViewModel.kt:4480-4556`).

**E5. OpenCode special-casing is scattered** across five sites (`RemoteModelCatalog.kt:24,78`,
`OpenAiCompatibleRuntime.kt:92`, `MainViewModel.kt:3431`, `BramApp.kt:1495, 4056`), and
`customHeaders` (which may carry secrets) is stored in plain SharedPreferences — only
`Authorization` is redacted (`SecureEndpointStore.kt:117`; `MainViewModel.kt:4609-4617`).

### X — Test infrastructure

**X1. The smoke suite is broken by the uncommitted work.** `ChatScreenSmokeTest.kt:62` still asserts
"Don't ask" while the working tree renamed the BYPASS label to "Auto-approve" (`BramApp.kt:2895`).

**X2. No deterministic harness tests.** Every tool-loop test needs a model (or a stub runtime), there
is no scripted OpenAI-compatible server, and no on-device test exercises the remote path end to end.

**X3. Emulator housekeeping.** The local `emulator-testing` branch is 127 commits stale and its one
commit is already on the mainline as `498ca0d` (verified with `git cherry`) — it can be deleted.
`BRAM_EMULATOR_ABI` is absent from `docs/BUILDING.md`, and the QEMU auto-configure hang caveat lives
only in `docs/HANDOFF.md:108-110` / `docs/DEVICE_ADAPTATION.md:429-436`.

## The overhaul

Ordered by dependency; M0/M1 are enablers, M2 is the first true milestone. Effort is rough.

### M0 — Land the endpoint work (0.5–1 day)

Extract the uncommitted endpoint/UI work off `feat/expert-streaming` into its own commit(s) on top
of `main` (or a `feat/endpoints` branch), so the overhaul starts from a clean tree and the work is
not lost to a concurrent session. Fix `ChatScreenSmokeTest` in the same change. Add the missing unit
tests for the new request features: custom headers with `{session_id}`, `x-opencode-session`, body
options merge/override, `reasoning_effort` per kind, malformed body options; catalog `/models`
fallback and error text.

Exit: clean working tree; `./gradlew` unit suites green; the endpoint flow (add → discover models →
chat) works on the emulator.

Result (2026-09-15): landed on `feat/endpoints` (three commits: termux preflight, endpoints + UI,
this plan). Unit suites green — `:runtime:openai:test` with eight new contract tests,
`:app:testDebugUnitTest` re-run, androidTest compiles with the smoke-test label fixed. Live on the
emulator: **add endpoint and model discovery verified** (UI form → mock catalog → chips → context
auto-fill). The chat leg was not reached: the emulator itself became unstable during the attempt
(see the findings below) — that leg is now M1's first exit test.

### M1 — Emulator harness test bed (1–2 days)

1. A scripted OpenAI-compatible mock server (host-side, small Python) with named scenarios: happy
   tool call, parallel calls, malformed arguments, HTTP 500, slow response, oversized result,
   multi-turn chain, `status: failed`.
2. A runbook in `docs/BUILDING.md`: `BRAM_EMULATOR_ABI=true`, `10.0.2.2` vs `adb reverse`, host
   bind/firewall notes, instrumentation commands, the QEMU auto-configure caveat.
3. One instrumentation smoke that adds the mock endpoint, lists models, and runs a single tool turn
   (assert the approval card and the tool result reach the transcript).

Exit: on the emulator, a scripted tool loop runs deterministically with no model file; the smoke
test passes in one `am instrument` invocation.

Emulator findings from M0 (2026-09-15) — prerequisites for a trustworthy test bed:

1. **`restoreLastModel()` auto-loads the last-used profile at every launch**
   (`MainViewModel.kt:4366`). On the QEMU emulator that load crashed the `:inference` process
   (contained; the UI survives and shows the error card), and a system_server ANR followed;
   `uiautomator dump` then wedged until reboot. Guard or clear the last-used profile for emulator
   runs before trusting any test that starts the app.
2. **The local embedder makes selection cost minutes per turn.** With bge designated, skill
   ranking and tool selection embed the query plus every offered tool (~20 s per embed under
   QEMU); a turn sat at "Choosing a profile…" for many minutes. Unsetting the embedding model
   makes both selectors take their documented instant fallback. Overhaul item (M4): selection
   must be deadline-bounded and must never block a turn like this.
3. **Host plumbing.** `adb reverse` is bound to the adb server instance, and this machine's PATH
   has a PhoenixSuit adb (v31) watchdog that keeps re-binding 5037. Use a private server
   (`ANDROID_ADB_SERVER_PORT=5038`, then `adb connect 127.0.0.1:5555`) and re-run `reverse` after
   any server change. The scripted mock server used during M0 lived in a temp directory; it
   belongs in the repo as part of M1.
4. **AVD health is a dependency.** `Pixel_9a` crashed twice during cold boot after the ANR. M1
   should own a known-good AVD (snapshot, or a wipe-and-provision script) instead of inheriting
   whatever state the last session left.

### M2 — Harness correctness core (3–5 days)

- Persist structured tool history: extend the conversation message JSON with `toolCalls`/`toolCallId`,
  store TOOL results, and replay them into `AgentRunRequest.messages` (the existing display
  `activity` is exactly the fallback data to rebuild from). The context manager must treat
  call/result pairs atomically when trimming.
- Unique, stable tool-call IDs across turns and replays.
- A per-result budget: a stated cap (e.g. 32–64 KB) applied where results enter the transcript, with
  explicit truncation markers preserved in the data; chunked `read_skill`.
- Argument schema validation and a per-tool timeout; parallel execution for read-only calls.

Exit: on the scripted server, a three-turn conversation sees its own earlier tool calls and results
and does not repeat them; no single tool result can push an 8K-context model over budget.

### M3 — Gate polish (1–2 days)

Wire `ALLOW_FOR_RUN` to a real action or delete it; fix the notification publish race; add
Once/Always/Deny actions to the shade; separate `os_permission_denied` from user refusal in the
envelope; show full arguments (with the sanitized target) on the approval card; fix the bare-call
strip regex to only remove whole-reply recovered calls.

Exit: every decision path in the approval matrix has a test; no path can strand a run on the
10-minute timeout it cannot escape.

### M4 — Tools v2 (2–4 days)

Honor capability flags (`supportsToolCalling` per endpoint, `TOOL_CALLING` before offering tools);
selection with a similarity floor and a context-proportional budget; a `tool_search` meta-tool for
registries larger than the budget; the description diet; MCP pagination, session reuse, and
read-only annotation support; decide on screenshot/accessibility and SAF tools.

Exit: a 4K-context run and a 32K-context run offer appropriately-sized tool sets; a non-tool
endpoint is never sent tools; MCP servers with >100 tools work.

### M5 — Skills v2 (2–3 days)

Front matter v2: `tools:`, `permissions:`, `author:`, and a monotonic version check; `disable` /
quarantine lifecycle; fix the rollback bug; author provenance and an activation policy (agent drafts
stay drafts until user activation; review shows a diff); `read_skill` chunking with a budget;
`list_skills` becomes a core tool; a schema version and corruption quarantine for `skills.json`.

Exit: an active skill's declared tools survive selection for its run; rollback can only restore a
user-approved version; a rollback of the S2 shape is impossible.

### M6 — Endpoints v2 (2–4 days)

SSE streaming for both wire kinds; map reasoning output to the Thinking activity; an error taxonomy
with retry/backoff (and a real `EndpointConfigurationException` for unrecoverable config);
reachability in routing candidates; a "use this endpoint" control; consolidate OpenCode support
behind a provider profile; encrypt or explicitly restrict secret-bearing custom headers.

Exit: a remote turn streams tokens and shows reasoning; an unreachable endpoint is not chosen; the
OpenCode behavior lives in one place.

## Emulator test strategy

- **Deterministic (M1, the backbone):** the scripted mock server plus adb scenario runs. Every
  harness exit criterion above is written against the mock first; it is the only way to test
  approval, timeout, truncation, and error paths without model variance.
- **Qualitative:** host llama.cpp / Ollama / LM Studio with a tool-capable 4–8B model, exposed as
  `http://10.0.2.2:<port>/v1` with "Allow insecure HTTP", forced via Routing mode = Remote only (or
  `adb reverse` + `127.0.0.1`). This replaces "the model is too small" as the explanation for a
  failed tool run.
- **Regression:** `BRAM_EMULATOR_ABI=true` keeps the local runtime in the APK so smoke and
  instrumentation runs keep covering both paths.

## Immediate next steps

1. M0 — land the endpoint work and fix the smoke test (this file is part of that change).
2. M1 — stand up the emulator + mock server and prove the remote tool loop end to end.
3. M2 — tool-history persistence is the first overhaul milestone; it is a prerequisite for
   believable multi-turn agent tests.
