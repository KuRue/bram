# Product and execution plan

Status: proposed implementation plan, 2026-10-17. Based on the owner's requested changes and an
inspection of the current code. This document proposes behavior; it does not claim these features
have shipped. The accompanying copy changes only remove promotional wording from documentation
and screenshot framing. No application behavior changes in this patch.

## Direction

Make the common paths shorter: open Home, resume work, add a model, configure it, inspect results.
Keep Bram's graphite/copper and warm-paper themes, staggered dot field, and launcher mark.
Use factual headings, compact rows, and details on demand. Keep measurements and failure reasons
available without turning every card into a diagnostics report.

Navigation uses a **sidebar**, with a modal drawer on phones and a persistent sidebar when width
allows. There is no bottom navigation bar. The top brand control combines the launcher’s dotted
lowercase b mark with “ram”; selecting it returns to Home. Home remains the default launch screen.

Below the brand: New chat, chat search, and recent chats/agent conversations. Below the chat section:
**Profiles**, **Settings**, and **Tools**, with icons and labels. Profiles opens the compact model
profile list, with files and server connections inside it. Settings expands to General, System,
Hardware recipes, and Appearance; Tools expands to Permissions/tools, Agents, Automations, Skills,
and MCP servers. Automations keeps its dedicated screen and run history, reached through Tools.
These expandable groups expose secondary destinations without a long set of permanent buttons.

On phones the menu button opens the drawer; selecting a destination, Back, or tapping the scrim
closes it. Preserve chat search/scroll and expanded groups while navigating. On larger screens,
keep the sidebar visible with a collapse control. Mark the active destination, expose expanded
state and accessible names, restore focus after dismissal, and keep touch targets at least 48dp.
The brand is a normal accessible Home action, not an unlabeled decorative logo. Deep links still
open the requested conversation/run and Back returns through the actual navigation history.

A chat is a conversation, an agent is a reusable instruction/tool/model configuration, a run is
an execution, and an automation is a trigger that starts a run. A hardware profile configures
inference; it is not an agent. Keep these names distinct in UI and storage.

The [interactive layout sketch](prototypes/product-layout.html) illustrates the proposed hierarchy,
compact rows, and existing color palettes. It uses example activity data, not statistics from the
owner’s phone. Open the file in a browser to explore; GitHub displays its source.

## What is already present

| Area | Current implementation | Implication |
| --- | --- | --- |
| Navigation | `BramApp.kt` presents panels over the chat, with no Home destination | Introduce real screen destinations and explicit back/deep-link behavior |
| Profiles/import | `ModelsScreen`, `AddProfileScreen`, `ProfileCard` in `BramApp.kt`; first-run overlay in `OnboardingScreen.kt` | Split touched screens into their own files; reuse import and advice logic |
| Benchmark | `BenchPlan.standard()` measures pp512, tg128, and decode at context depth; separate sustained and LiteRT paths | Keep repeatable diagnostic tests, but stop making them the only configuration experience |
| Auto-configure | Fixed per-dimension candidate lists and sweeps in `MainViewModel.kt` and `TuningModel.kt` | Move orchestration into a resumable, budgeted engine; make candidate selection adaptive |
| Reliability | Padded CPU reference shares the 30-second slow-reference threshold; slow retries are unbounded | Fix termination before changing search strategy; slowness alone is not evidence of throttling |
| Vulkan | `autoConfigure()` explicitly filters it out, with a comment about unreliable validation | Explain policy exclusion; an empty Backends section is not a measurement result |
| Usage | `RunJournalEntry` and `SqliteRunJournal` already persist run tokens, times, and tool rounds | Extend this journal and its query API; do not create a second competing usage ledger |
| Recent chats | `ConversationStore.list()` sorts an index by message update time | Reuse it, but distinguish new activity from opening, renaming, or settings edits |
| Automations | `Automation`, `Cron`, `PersistentAutomationStore`, `AutomationRunner`, `AgentTaskRunner` | Build a first-class management surface over existing machinery, then strengthen execution records |
| Subagents | No parent/child run contract or in-app delegation tool | Add explicit run relationships, resource scheduling, and restricted context handoff |
| Recipes | Fingerprints, profiles, and tuning results exist, but no repository format or source manager | Reuse those identities; add a declarative distribution layer |
| Hexagon | Native build is SDK-dependent; `bram.requireHexagon` exists, hosted release workflow does not use it | Make inclusion a release requirement and verify the final APK |

Source locations above are repository-relative implementation references. External research is linked
at the point where it affects a recommendation. Research was checked on 2026-10-17; upstream head
behavior must not be mistaken for behavior in Bram's pinned engine revision.

## 1. Language and visual density

### Copy and images

Use labels such as **Local model conversation**, **Device scan**, **Configuration measurements**,
**Tool approval request**, and **Prompt and decode throughput**. Remove slogans from both banner
variants, screenshot captions, README headings, and overview copy. Keep real captures, crops,
colors, and measured values; do not alter a reply or hide an error to imply a successful feature.
Historical engineering records retain their technical meaning, including unsuccessful experiments.
New feature descriptions state what exists, prerequisites, and current limits. This is a standing
copy rule for the app, documentation, and future screenshots.

### Shared UI rules

- One main action per screen or row. Tap a profile to open details; use a load/unload icon and an
  overflow menu for secondary actions. Rename, duplicate, export, delete, and advanced diagnostics
  do not need full-width buttons on every card.
- A compact profile row shows name, backend/context, loaded state, and a small measured-performance
  summary when available. Put validation failures or stale measurements in a short status chip;
  explain them in the detail view. Do not hide a real failure behind a success-looking badge.
- Icons replace repeated action text, not meaning: persistent destinations retain labels;
  destructive/unfamiliar actions retain confirmation or explanatory text. Provide content
  descriptions, selected/disabled states, and keyboard/TalkBack access.
- Keep at least 48dp touch targets even with 20–24dp glyphs; test large text and narrow screens.
  This follows [Compose accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
- Extract `HomeScreen`, `ModelsScreen`, `ProfileDetailScreen`, `AddModelScreen`,
  `ConfigurationScreen`, and `AutomationsScreen` as each is changed. Avoid a separate whole-app
  refactor before delivering visible improvements.

### Profiles, introduction, and Add model

Profiles has a search/filter bar, compact profile rows, and one Add action. A model detail groups
its profiles and file metadata; a profile detail groups **Overview / Performance / Settings**.
Overview shows current configuration, evidence date, and load action. Performance holds benchmark
history, charts, and configuration runs. Settings contains advanced knobs and raw diagnostics.

First launch goes to Home with a compact setup card, rather than a dense blocking tour. Offer
**Add local model** and **Connect server**, plus Skip. Device advice is one short recommendation
with format, approximate size range, and available storage; expand for CPU/RAM/backend details.
Lead with Connect server if local inference cannot run. Do not request permissions until a feature
needs them. Completing or skipping setup must survive restart without erasing existing state.

Add model starts with a file picker or server connection choice. After selecting a file, show its
name, size, compatibility summary, and one Import action. Advanced context/backend choices come
later. Import must not force a lengthy tuning pass: create a conservative usable profile first,
then offer configuration as a cancellable job. Model downloads are a later feature with its own
storage, licensing, checksum, and resumption work; do not hide them inside this redesign.

## 2. Configuration and benchmarks

### Separate the jobs

**Configure** chooses a usable profile for a stated workload within a time budget.
**Benchmark** reports repeatable performance for a selected profile.
**Validate backend** diagnoses output agreement and offload limits.
These share measurement primitives but have different outputs and stopping rules.

Keep pp/tg/depth tests as a reproducible benchmark suite and export their exact workloads. They
remain useful for comparisons; [llama-bench](https://github.com/ggml-org/llama.cpp/blob/master/tools/llama-bench/README.md)
also distinguishes prompt processing, generation, repetitions, and test parameters. The problem is
running every setting against every candidate as the default setup experience.

### Adaptive configuration algorithm

1. **Inventory and seed.** Read actual runtime capabilities, model metadata, CPU topology,
   memory budget, driver/build fingerprint, and matching recipes. Seed from the last locally
   validated profile, otherwise a compatible recipe, otherwise conservative CPU defaults.
2. **Calibrate.** Take a small CPU prompt/decode reference and a representative padded reference
   only when needed. Track load, prefill, and decode time separately. Scale deadlines from observed
   work; never use one short-prompt threshold to reject all long-context measurements.
3. **Cheap eligibility checks.** Remove unsupported settings and unsafe memory estimates before
   loading. Every backend gets a visible state: available, unavailable, excluded by policy,
   testing, failed validation, timed out, or measured. Never silently omit a candidate.
4. **Coarse backend search.** Test CPU and eligible accelerators at a few meaningful offload
   levels, using short bounded probes and an output-agreement gate. Recipes prioritize the order;
   they cannot grant a validation pass. Vulkan policy exclusions remain explicit until fresh tests
   justify changing them. Hexagon is tested when the hardware and model support it.
5. **Allocate more work to promising configurations.** Use staged elimination inspired by
   [successive halving](https://optuna.readthedocs.io/en/latest/reference/generated/optuna.pruners.SuccessiveHalvingPruner.html):
   cheap comparable trials first, then longer trials for candidates that remain competitive.
   Implement a small pure Kotlin planner; no Python/Optuna runtime dependency. A short probe cannot
   decide long-context KV behavior, so allocate effort within comparable workload groups.
6. **Refine coupled settings.** Search a bounded neighborhood around the leading backend/thread/
   batch combinations, then attention and KV choices at the target context. Recheck combinations;
   independently fastest settings do not necessarily compose. Invalid or numerically unstable
   candidates are excluded before speed ranking.
7. **Confirm and stop.** Re-test finalists against the incumbent under comparable conditions,
   then validate the final combination at the intended context. Stop on exhausted wall time,
   trial count, thermal budget, insufficient remaining time, or improvements smaller than observed
   noise. Keep the incumbent when results cannot distinguish a winner. Report incomplete work.

Start with a simple sequential racing algorithm. Bayesian optimization is a later option only if
measurement history shows it saves work; a large learned optimizer is unnecessary for a small,
conditional search space with expensive model reloads and variable thermals.

### Objectives, validity, and reuse

Ask for a workload (**Chat**, **Long context**, **Background tasks**) and expose a simple
**Balanced / Performance / Battery** preference. Rank by estimated end-to-end latency for the
chosen prompt/output sizes, subject to correctness and memory bounds. Include decode-at-depth,
TTFT where measurable, load cost, and sustained degradation. Do not pick a chat configuration just
because it has the highest prompt tok/s. Publish the workload and ranking rule with the result.
Battery preference uses energy measurements only when the device provides usable readings; say
when unavailable, and do not invent battery savings or a universal cross-phone score.

Warm-up before recorded timings; use repeated measurements, observed spread, and a final paired
incumbent/challenger check. Pause or invalidate comparisons across major thermal drift. Recheck
CPU reference periodically instead of assuming the phone's thermal label tells the whole story.
The agreement metric is a regression screen against a reference, not a complete model-quality
assessment; preserve agreed thresholds and record the fixture/context that passed.

Cache evidence by device/driver, engine and build, exact model content identity, configuration,
workload, and measurement-method version. Cache hits skip redundant work only within that identity.
A new driver, model quant, context regime, or engine revision invalidates the affected evidence.

### Execution and UI

Move the coordinator out of `MainViewModel` into a single-run `ConfigurationRunner`, with a pure
candidate planner in `core:domain` and measurement adapters in the runtime. Persist a job ID,
state, input identity, completed trials, current best, and time budget. Use monotonic clocks for
durations. Cancellation and hard deadlines must reach native inference; a stuck call needs the
existing process-restart path, not only coroutine cancellation. Persist Interrupted on process
death and offer Resume/Re-run; never treat a half-tested candidate as approved.

Initial tunable defaults: a 2-minute quick budget, a 10-minute extended budget, at most two
reference retries, and a bounded thermal wait charged to the same budget. These are design
starting points to calibrate on actual models, not performance promises. Decline to launch another
trial when its estimated cost exceeds the remaining budget. Every load/reference/probe uses the
earlier of its own deadline and the overall job deadline; give process cleanup a separate short
bounded grace period. Save partial evidence and a usable
incumbent on timeout/cancel. Apply a verified result at an idle boundary; do not silently swap a
profile during a conversation turn. An interrupted load restores a usable prior model or reports
that it must be reloaded.

The screen shows phase, elapsed/remaining budget, current test and why it was chosen, incumbent,
and Cancel. Show a range or elapsed time when completion cannot be predicted; an adaptive search
cannot honestly say "candidate 7 of 42" in advance. The final view compares before/after prompt,
decode-at-depth, memory, and agreement, with expandable trial history and failure reasons.
Pause/Resume is a later addition; the first version must have reliable Cancel and persisted results.

## 3. Home, usage, and recent work

Home is the normal launcher destination. Explicit notification/deep links open their chat or run;
Back returns to Home. New chat is a visible action. Do not warm-load a large model just to show
Home. Existing conversations remain intact when this becomes the default destination.

The top card uses the current month, with previous/next month controls and a short activity chart.
Show **Generated tokens**, **Completed runs**, and **Tool calls**. Details separate input/output,
local/remote, and chat/automation/subagent usage. This reflects the owner's requested monthly
summary without pretending to reproduce undocumented behavior in another app.

Extend `run_journal` rather than reconstructing cost from visible chat bubbles:

- Introduce stable run IDs across retries/fallback, `rootRunId`, `parentRunId`, agent/automation
  identity, origin, last-event time, and terminal states including cancelled/interrupted.
- Record per-attempt/per-model-round usage with stable event IDs. Upserts/replayed events must not
  double-count. Distinguish provider cumulative snapshots from deltas. Null is unknown, not zero;
  measured and estimated tokens stay distinguishable, including explicit measured zero.
- Aggregate measured usage from actual execution attempts, including failed/partial work when
  reported. Retries legitimately consume tokens; duplicated callbacks do not. Parent rollups
  must not count child tokens a second time. Exclude tuning/benchmark tokens from work totals.
- Completed runs counts successful top-level runs. Tool calls counts actual executions, not model
  requests, blocked calls, or tool-loop rounds. Show a separate breakdown for failures/cancelled
  work and children. Do not relabel existing `toolTurns` as tool calls.
- Store UTC timestamps, group calendar months/days in the selected device time zone, and make
  period boundaries deterministic. Older records may have incomplete usage: show "recorded since"
  and coverage, rather than manufactured historical totals. Define data retention and Clear
  activity separately from deleting conversations; deleting chat content must not unexpectedly
  resurrect it from the activity index.

Under the month card: running/needs-attention work, then **Recent**. Each row has title, type icon,
model or agent subtitle, status, and last substantive activity ("12 min ago", with exact time on
inspection). Combine root chats, saved agents with recent runs, and automation results, linking
straight to their content. Deduplicate the same chat/run, hide individual child runs until a parent
is expanded, and keep recent work usable with no network or model loaded. Opening a row does not
make it more recent; new messages, tools, and run transitions do. A new empty install shows setup
and New chat, without empty charts or fabricated examples.

## 4. Automations as a destination

Borrow the useful interaction model from
[Open WebUI Automations](https://docs.openwebui.com/features/chat-conversations/chat-features/automations/):
a named prompt, model choice, schedule, Run now, pause/resume, and execution history linking to
results. Bram already has much of the execution path; it needs management and reliable provenance.
Do not import Open WebUI's server-side Python plugin model into the Android app.

List rows show name, a human-readable schedule, next run, and last result. Details contain
instructions, profile/agent, tools/privacy, conditions, output destination, and history. Supply
Once/Daily/Weekly/Monthly presets and schedule previews; keep cron as Advanced. A typed schedule
representation compiles to the existing Cron path where possible; preserve existing cron
expressions in migration rather than replacing them with RRULE just because Open WebUI uses it.
Store an explicit time-zone policy (follow device or fixed zone), show upcoming occurrences, skip
nonexistent DST times, and run an ambiguous local-time occurrence only once. Preview these rules.

Add stable `automationId` and occurrence IDs to task/run history. Creation previews permissions;
Run now does not silently enable a paused schedule. Editing instructions or tools preserves
history and re-evaluates permissions. Keep paused schedules and separate definition deletion from
deleting past results. Import/export later creates paused drafts for review.

Android execution is best-effort. [WorkManager periodic requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
have a 15-minute minimum and can be delayed; [inexact alarms](https://developer.android.com/develop/background-work/services/alarms)
also do not guarantee a requested minute. A displayed 09:00 is the requested time, not an SLA.
Use alarm delivery as a due-time signal and persistent work for reconciliation; do not let both
paths create duplicate runs. Exact alarms are for justified time-critical reminders, not ordinary
AI digests. Check [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/changes)
and the actual service type before promising unattended long inference on API 35/36.

Before a scheduled run starts, check model availability, memory, battery/charging policy, network
and privacy, permitted background execution, and tools. If conditions fail, show a reason and
Waiting/Needs attention. Offer explicit permission to load a designated local profile while idle;
never evict an interactive conversation or silently route local-only content remotely. Notifications
can request user action when Android will not allow execution; a WorkManager job is not a way to
bypass those limits. An always-on companion/server runner is a later optional feature, not an
assumed dependency for v1.

Reliability requirements: unique occurrence keys, no overlapping occurrences of the same
schedule by default, bounded retries, and no automatic replay of uncertain side effects. At boot,
time-zone change, app update, and recovery, reconcile due work. Default misfire policy coalesces
missed occurrences into at most one eligible run; offer Skip missed, with an expiry window. Mark
when work is late, skipped, paused, blocked by permissions, or waiting for a model. A crash between
a side effect and its completion record is an uncertain outcome, not permission to repeat it.

## 5. Agents and delegation

Use a saved **AgentDefinition** for name, version, instructions, model/routing preference, tool
allowlist, selected skills, and memory scope. The built-in Bram agent is the default; a definition
is reusable and is not a hidden duplicate model profile. Users can run one directly or assign it
to an automation. Existing tasks migrate to the default agent without changing their prompts.

Both [Open WebUI subagents](https://docs.openwebui.com/features/chat-conversations/chat-features/subagents/)
and [Claude Code subagents](https://code.claude.com/docs/en/sub-agents) demonstrate isolated task
context and returning a bounded result. For Bram, start with a single delegation tool that creates
a child run, not several concurrently loaded local models.

A child receives a task, explicit context/attachment references, and an allowed agent/model. It gets
no implicit copy of every transcript, secret, attachment, or memory. Effective tools and privacy
are the intersection of parent policy, agent definition, and user grants; a child cannot broaden
them. Child output is untrusted tool-result content with size limits and source references.
Begin with research/review/extraction roles and read-only tools. Gate side-effecting roles
separately, including when the parent is unattended.

First delivery: parent waits, child runs, bounded summary returns; transcript shows a compact
"Research · running/completed" row linking to the child log. One nesting level, no child spawning
other children, initial maximum two children per root run, plus root-wide token/time/tool budgets.
Apply limits at the executor, not only in prompts. Unsupported tool-calling models show a clear
capability limitation rather than trying to parse arbitrary prose as a delegation request.

One local inference lease is shared by chat, tuning, benchmarks, automations, and children. The
parent must release/yield that lease before awaiting a child or the system deadlocks. Children
using the same loaded model run serially with separate context identity; remote children may run
with an explicit concurrency cap only when privacy permits. Do not reuse KV state across unrelated
child contexts. User chats have priority at safe turn boundaries; tuning stops or pauses before
another load begins. Reserve enough memory for reloads and keep the foreground status accurate.

Persist parent/child lifecycle, cancellation, and results before adding background children.
Cancelling a root cancels its queued/running descendants and invalidates late completions; cancelling
an unrelated queued task must not cancel another active job. Child failure returns a structured
failure to the parent rather than silently losing work. On recovery, reconnect completed results
or mark Interrupted; do not replay side-effecting child work. Later, handles/status/cancel and
background callbacks can reuse this machinery, with parent continuation serialized to prevent two
children from resuming the parent simultaneously.

## 6. Hardware recipes and additional repositories

Recommend a separate public repository named **`KuRue/bram-hardware-recipes`** (proposed; not
created by this plan). Ship a reviewed snapshot inside the app and register it as the default
source. First target is the owner's Galaxy S25 Ultra / Snapdragon 8 Elite. Include the actual
measured LFM2.5-2.6B Q4_0 CPU configuration and Hexagon compatibility/results where verified. Do not
label the NPU fastest merely because it is present; current evidence favors the newer CPU path for
some workloads. Broader hardware coverage grows through evidence, not phone-name guesses.

### Versioned format

An index identifies schema version, source identity, snapshot revision, and recipe hashes.
Each declarative recipe contains:

| Field group | Contents |
| --- | --- |
| Identity | Recipe ID/version, source, maintainer, evidence date |
| Match | Device manufacturer/model aliases, SoC, CPU features, RAM class, Android/driver identity |
| Runtime | Engine commit/range, Bram schema/settings version, compiled backend availability |
| Model | Exact content hash for evidence; architecture/quant/size/context constraints for candidate seeds |
| Workload | Prompt/decode lengths, context depth, preference, charging/thermal conditions |
| Proposal | Allowlisted backend, thread/topology, batch, attention, KV and memory settings |
| Evidence | Validation fixture/method, agreement, timings/spread, memory and energy if measured |
| Limits | Known incompatible settings, unsupported combinations, expiry/revalidation conditions |

Recipes suggest candidate settings. They are not native plugins, executable scripts, prompts,
model downloads, or changes to permissions. Strict schema and numeric bounds reject unknown or
unsafe fields. A recipe cannot disable the CPU agreement gate, lift memory limits, force remote
routing, or download shared libraries. Native backends stay in the signed APK.

### Matching, trust, and updates

Rank exact device + driver + engine + model + workload matches first. A SoC/model-family match is
only a search seed; it is not validated evidence for this phone. Even an exact published recipe
gets a short local validation before its settings become active. A failed check retains the local
known-good profile and records a device-local exclusion under the current fingerprint.

Users add a GitHub repository or HTTPS index in **Settings → Hardware → Recipe sources**. Show
maintainer/source, pinned revision, available matches, and proposed changes before applying.
Official snapshots have an app-pinned verification key and hashes; external sources require
explicit trust and a pinned revision/key, with key-change review. A hash alone checks content
integrity, not author identity. No merging arbitrary files from `main` directly into live settings.
Bound downloads, schema size, redirects, and cache growth. Distinguish source authenticity from
measurement quality; a signed recipe can still be slow or wrong.

Users control updates; prefer explicit refresh initially, with optional periodic checks later.
Keep previous snapshots, offline cache, and rollback. Match selection order is explicit user
choice, locally validated result, exact official match, then optional external seeds. A source
failure must not prevent loading a model. Removing a source removes its suggestions; existing
profiles retain their settings and provenance. Evidence upload is a separate opt-in, with a preview
that strips hardware serials, private prompts, filenames, and user content.

Public repo groundwork: JSON Schema, valid/invalid fixtures, contribution template, evidence
requirements, automated schema checks, and a license decision. Recommend a permissive license for
the schema/recipes, but the owner must choose it; no project license is assumed here. GitHub release
snapshots make the app's default data reproducible and reviewable. General model
recommendations are not substitutes for per-model benchmark evidence.

## 7. Engine packs: hardware-specific engines installed on demand

Decided 2026-10-17: Bram should run on many phones and offer every engine that helps them, without
shipping every vendor's code to every phone. The base APK keeps what works everywhere; everything
vendor-specific moves into **engine packs**, separate signed APKs that the setup guide offers on
first open when the phone can use them. This replaces "Hexagon in every release APK": Hexagon is
always *offered* to Snapdragon phones, inside the Qualcomm pack.

### Shape

An engine pack is an APK that carries native libraries and a manifest entry describing them. It has
no launcher icon and no code of its own. Bram finds installed packs, checks they are signed with
Bram's own key, and loads their libraries into its isolated `:inference` process, the way apps load
WebView's libraries from the WebView package.

Packs do **not** run their own inference process. A pack process could not open Bram's model files:
they live in Bram's private storage, and handing a descriptor across apps hits the same
scoped-storage re-open failure Milestone 1 recorded. Loading the pack's libraries into Bram's own
process keeps models where they are and keeps the existing AIDL contract unchanged.

| Package | Contents | Who gets it |
| --- | --- | --- |
| Base Bram | UI, agent, llama.cpp core and CPU backends (one library per CPU instruction-set level, chosen at runtime), Vulkan, LiteRT-LM CPU/GPU, Kotlin/JNI glue for every engine | Everyone |
| Qualcomm pack | ggml Hexagon backend and its DSP libraries, ggml OpenCL backend, Qualcomm runtime libraries for ExecuTorch/LiteRT NPU | Snapdragon phones |
| MediaTek pack | ExecuTorch/LiteRT NeuroPilot backends | Dimensity phones |
| Samsung pack | ExecuTorch Exynos backend | Exynos phones |
| MLC pack | MLC LLM GPU runtime | Adreno/Mali GPUs, optional |

The base APK alone always works offline on CPU. A pack never replaces the CPU path; it adds
backends that still have to pass Bram's CPU-agreement check before they are used.

### Mechanism (proven on the emulator, 2026-10-17)

`engines/probe` is a minimal pack, exercised by `EnginePackProbeOnDeviceTest`. On the Android 16
emulator:

- **Discovery.** A pack declares a marker service with the `io.github.kurue.bram.ENGINE_PACK`
  intent action and its metadata (engine id, libraries, contract version). Bram declares that
  action in `<queries>`. Package visibility only counts **exported** components, so the marker is
  exported and guarded by a signature-level permission; it is never started (the pack has no code).
- **Trust.** `checkSignatures(bram, pack)` must return `SIGNATURE_MATCH`. A pack signed by anyone
  else is ignored.
- **Loading.** `System.load` of a library in the pack's `nativeLibraryDir` succeeds in Bram's
  linker namespace (`…/engine.probe…/lib/x86_64/libbram_engine_probe.so … : ok`), and JNI binds to
  Bram's classes.

Still to prove, in order:

1. The same on the S25 Ultra (One UI may add policy on top of AOSP).
2. A real ggml backend from a pack: switch the native build to `GGML_BACKEND_DL`, so `ggml-base`
   and llama.cpp stay in the base APK and each backend is its own library. A pack's backend links
   against the base's `libggml-base.so`, so **a pack must come from the same llama.cpp pin as the
   Bram it serves**; the contract version in its metadata enforces that, and a mismatched pack is
   ignored with a message asking to update it. The same switch enables
   `GGML_CPU_ALL_VARIANTS` for phones older than ARMv8.2.
3. Hexagon from a pack: the DSP libraries are found through `ADSP_LIBRARY_PATH`, which must point
   at the pack's library directory. Verified only on the phone.
4. Non-ggml engines (ExecuTorch, MLC, LiteRT NPU): their Kotlin/JNI wrappers live in the base, the
   heavy native libraries in the pack, loaded the same way.

### Installing packs

- **First open:** the setup guide reads the chip and offers only the packs that apply, with their
  size ("This phone has a Snapdragon NPU. Add the Qualcomm engine, about 30 MB?"). Later, from
  Settings › Engines, which lists installed packs, their versions, and what they enabled.
- **GitHub releases:** pack APKs are release assets beside Bram, signed with the same key, with
  checksums. Bram downloads one and hands it to Android's installer, which asks the user to confirm
  (this needs `REQUEST_INSTALL_PACKAGES` and the "install unknown apps" permission for Bram).
- **Google Play:** each pack can be its own listing, or the same libraries can ship as Play Feature
  Delivery modules targeted by chip. Play forbids downloading code from anywhere else, so the
  in-app download path is for GitHub builds only.
- **Updates:** a pack updates independently, but must match Bram's contract version; Bram says
  which pack needs updating rather than loading an incompatible one.

### Qualcomm SDK terms

The Qualcomm pack carries files built with, and possibly copied from, Qualcomm's Hexagon SDK. The
[Snapdragon instructions at the pinned llama.cpp commit](https://github.com/ggml-org/llama.cpp/blob/a94d563ed801d1da1b8c2432946de07d0231bb3d/docs/backend/snapdragon/README.md)
document a toolchain container that includes the SDK. Before any pack is distributed, the owner
must review the SDK's license and redistribution terms and the notices the pack has to carry. Local
builds for testing on the owner's phone do not distribute anything. Release gates for the pack:
`bram.requireHexagon=true` fails a build without the SDK; the pack's APK is checked for the backend
and v79 DSP libraries; and the S25 Ultra confirms enumeration, load, CPU-reference agreement,
cancellation, and CPU fallback after a deliberate backend failure.

## 8. Delivery sequence and gates

| Slice | Deliverable | Depends on | Acceptance |
| --- | --- | --- | --- |
| 0 | Factual docs, banners, screenshot captions; this plan | None | Existing captures preserved, local links/SVG checked, no product claims added |
| 1 | Bound setup retries/cooldown/native deadlines; explain excluded backends | Existing sweep | Slow/padded references terminate, Cancel works, partial results survive, no false success |
| 2 | Engine packs: probe (done), `GGML_BACKEND_DL` native split with CPU variants, Qualcomm pack (Hexagon + OpenCL), install flow, then MediaTek/Samsung/MLC packs | Phone access; SDK terms review before distribution | Packs discovered only when Bram-signed; mismatched contract refused; backends from a pack pass CPU agreement on the S25 Ultra; base APK alone still works |
| 3 | Shared compact components; Profiles/Add model/intro redesign | Slice 1 for configuration entry point | Fewer visible actions, accessible icons, no mandatory tuning, large-font/device tests |
| 4 | Run journal extension, agent/task identity, inference resource arbiter | Existing journal/queue | Idempotent usage, cancellation ownership, safe schema migrations, no simultaneous local loads |
| 5 | Home destination and monthly/recent views | Slice 4 | Correct totals/unknowns, fast offline launch, notification routing, recent activity dedup |
| 6 | Adaptive configuration runner and Performance view | Slices 1, 3, 4 | Bounded runtime, comparable trials, final gate, clear evidence and partial completion |
| 7 | Recipe schema, bundled S25 Ultra seed, official public repo, source manager | Slice 6 contracts | Untrusted-data checks, fingerprint matching, local revalidation, offline/rollback tests |
| 8 | First-class Automations UI, scheduler/history reliability | Slices 3, 4; agent definition | Schedule previews, misfire/dedup/reboot tests, permission handling, linked results |
| 9 | Bounded foreground child agents, then optional background delegation | Slices 4, 8 execution contracts | No lease deadlock, policy inheritance, bounded context/cost, root cancellation/recovery |

Slices 2 and 3 can progress independently once reliability work is isolated; recipes should seed
the first adaptive implementation with a local fixture before the public source manager ships.
Do not make an entire redesign wait for the federation UI. Agent definitions can land with the
run contracts; automation management does not need to wait for full child-agent execution.

**First implementation batch:** slice 1, the engine-pack mechanism and Qualcomm pack, and compact Profiles/Add
model navigation. Then the run journal/arbiter and Home, followed by adaptive configuration and
recipes, then the automation/delegation expansion. Prefer small reviewable PRs with a migration
and verification story each. This is an order of work, not a calendar estimate without prototypes.

Required verification as slices land:

- Pure tests for budget termination, candidate pruning/dependencies, noisy/tied metrics, stale
  fingerprints, and cancellation. Compare the new planner against the existing sweep on the same
  models: trials/time spent, final quality gates, and measured workload performance.
- Journal/DB migration fixtures; multi-round/retry/fallback/child accounting; unknown/zero token
  cases; month/DST boundaries; restart and duplicate-delivery simulation.
- Scheduler tests for duplicate alarms, boot, missed time, paused/edited schedules, battery/network
  constraints, no loaded model, permission refusal, and uncertain side effects.
- Tool/child tests for context leakage, privacy escalation, recursive delegation, bounded outputs,
  cancellation propagation, crash recovery, and parent waiting while holding the inference lease.
- Recipe fixtures for malformed/oversized data, bad signatures/hashes, key rotation, stale model/
  driver identity, incompatible values, source outages, and rollback.
- Compose/device checks for compact profile readability, Add model, setup, Home empty/history/
  running states, icon semantics, 48dp targets, large fonts, dark/light themes, and Back/deep links.
- Standard unit/lint suites, focused device tests, and a full cold-AVD suite for app changes.
  Collect Hexagon, thermal/energy, local inference speed, and actual background scheduling checks
  into one S25 Ultra batch. Other GPU/SoC families remain explicitly unvalidated until tested.

## Decisions to revisit after prototypes

- Quick/extended time budgets and benchmark workloads need calibration on small and large models;
  do not turn the proposed two/ten-minute defaults into a claimed completion time.
- Exact phone identifiers, driver hashes, native artifact list, SDK redistribution terms, and
  container availability need verification before publishing the first default recipe/release.
- Choose a recipe-repository license before accepting public contributions. No new repository is
  created as part of this planning task.
- Decide whether a remote companion is valuable only after the on-phone scheduler's limitations
  are visible; do not design Android UI around an assumed always-on server.
