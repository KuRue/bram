# Device adaptation plan — on-device tuning, compatibility, and coverage across hardware

Status: planned. Research date: 2026-08-12. Companion to [PERFORMANCE_OPTIONS.md](PERFORMANCE_OPTIONS.md).
Target device classes: any ARM64 Android phone/tablet Bram can be installed on — Snapdragon
(Adreno + Hexagon), Dimensity (Mali/Immortalis + APU), Tensor (Mali-G + TPU), Exynos (Mali +
NPU), low-RAM devices, and the x86_64 emulator.

## Goal

Bram should get the best result it can on *whatever* phone it is on, without anyone shipping
a per-device table. That means three things, all measured on the device itself:

1. **Automatic tuning** — every runtime knob that is safe to vary is a candidate sweep with a
   correctness gate, so the device converges on its own best config instead of inheriting one.
2. **Compatibility intelligence** — a model is inspected at import and Bram tells the truth
   about how this model runs on *this* device (which backends can offload it, which quant types
   hold it back, whether its template supports tools, whether KV reuse will work), then offers
   fixes that are themselves validated (e.g. converting a quant).
3. **Runtime coverage** — when llama.cpp cannot exploit the hardware (non-Qualcomm NPUs,
   weak GPU drivers), Bram has other runtimes to fall into (LiteRT-LM today, ExecuTorch lanes
   later), and the tuning machinery is runtime-neutral so every runtime gets the same treatment.

## Principles (inherited, not new)

- Correctness gates speed, never the reverse. Every dimension candidate must reproduce the CPU
  reference before its speed counts (the house rule that caught the broken Vulkan backend).
- The CPU reference stays pinned (fixed threadpool, fixed settings) so every comparison is
  apples-to-apples; tuning applies to chat loads, never to the yardstick.
- Everything is probed, not inferred from the model number — and results are keyed by the
  device/driver/build fingerprint so stale numbers are never trusted on another device.
- A failed measurement is a recorded fact, not an error; no passing candidate means keep the
  safe default and say so.
- Local by policy: tuning happens on-device; any telemetry is opt-in and anonymous.

## What already exists (the foundation)

| Piece | Where | What it gives us |
|---|---|---|
| Teacher-forced harness | `MainViewModel.kt` `measureBackend` / `runBatchTune` | CPU reference decode → candidate replay → agreement gate → wall-time ranking → note on the profile |
| Auto-configure | `MainViewModel.kt` `autoConfigure` | Measures every registered backend, picks fastest that agreed, tunes batch, visible overlay + foreground service |
| Per-profile settings | `ModelProfile` (core:domain) | backend, context, flash attention, KV type, batch/ubatch, sampler, notes + timestamps |
| Load identity cache | `InferenceProcessService.kt` | Every load keyed on model/context/batch/ubatch/attention/KV/backend/reasoning — a settings change can never reuse a stale context |
| GGUF metadata reader | `runtime/llamacpp/GgufMetadataReader.kt` | architecture, quant family, context length, chat template presence, layer count at import, bounded |
| Device profiler | platform:android | RAM, storage, CPU count, Vulkan-feature, thermal state |
| Backend list | JNI `probe`/`devices` | Which accelerators actually registered on this build+device |
| Import flow | `MainViewModel.kt` (~line 926) | Metadata → baseline profile → auto-configure on first import |

## Device classes and expected tuning outcomes

| Device class | CPU | GPU | NPU | Expected outcome after Phase 1–4 |
|---|---|---|---|---|
| Snapdragon 8 Elite (S25 Ultra) | Oryon, KleidiAI | Adreno — OpenCL validated, Vulkan fails gate | Hexagon HTP v79 — validated | Full sweep: threads/mask/poll, batch, fa/kv, hex env vars; ~10–30% decode over today |
| Other Snapdragon (8 Gen 1–3, 7-series) | KleidiAI | Adreno OpenCL (if driver + stub) / Vulkan measured | Hexagon v68–v75 | Hex dims gated by registered backend; fewer HTP kernel wins on older skels |
| Dimensity (Mali-G/Immortalis) | KleidiAI | Vulkan only — agreement gate decides; upstream perf work open (#18493) | no llama.cpp NPU | CPU tuned + Vulkan measured; NPU via LiteRT-LM/ExecuTorch lane if models exist |
| Google Tensor (Pixel) | KleidiAI | Vulkan/Mali measured | TPU via LiteRT-LM | LiteRT-LM runtime is the NPU path; llama.cpp CPU/Vulkan as fallback |
| Exynos | KleidiAI | Vulkan measured | no llama.cpp NPU (ExecuTorch Samsung backend in development) | Same as Dimensity |
| Low-RAM / old | KleidiAI baseline | none | none | Thread/mask/context dims shrink; storage-assisted + Q8_0 KV emphasized |
| Emulator (x86_64) | baseline | none | none | CPU-only dims; reference model stays Qwen3.5-0.8B |

The point of the matrix is that no row needs a hardcoded answer: the gates and candidates fall
out of device facts. This table is what we *expect*; the device decides.

## Phase 1 — the tuning framework (core)

Turn every safe knob into a load-identity field with a candidate list, a teacher-forced gate,
and a profile note. This generalizes `runBatchTune` into `tuneDimension(profile, dimension)`.

Dimensions (all verified reachable at the pin):

| Dimension | Plumbing | Candidates (from device facts) | Affects |
|---|---|---|---|
| threads | existing load field → `ggml_threadpool_params.n_threads` | `{n, n-2, max(2, n/2)}` from core count | decode |
| cpu mask + strict | new fields → `cpumask[]` + `strict_cpu` | cluster sets from sysfs freq groups; empty mask = default (safe) | decode, thermal |
| poll | new field → `ggml_threadpool_params.poll` | `{0, 100}` (and the batch-pool variant) | decode latency |
| thread priority | new field → `ggml_threadpool_params.prio` | `{NORMAL, HIGH}` | decode latency |
| load mode | new field → `llama_model_params.load_mode` | `{MMAP, NO_MMAP}` (AUTO exists upstream post-pin) | HTP repack, page cache |
| batch / ubatch | existing fields | existing 4 candidates; widen on HTP (1024/128 per Snapdragon reference) | prompt |
| flash attention | existing field | `{AUTO, ON, OFF}` | both |
| KV type | existing field | `{F16, Q8_0}` | memory vs decode |
| Hexagon env knobs | `setenv()` in inference process per load | on/off per knob, only when hexagon registered | both |

Implementation shape:

- core:domain `ModelProfile`: new fields (`threads=0` device default, `cpuMask=""`,
  `cpuStrict=false`, `poll=-1`, `threadPriority=Normal`, `loadMode=Auto`, `hexFlags=...`)
  with `sanitized()` bounds, plus a `tuning: List<DimensionTuneNote>` replacing the ad-hoc
  `batchTuneNote` pattern (kept for compatibility).
- runtime:llamacpp JNI `load()`: build the threadpool pair from the request and attach via
  `llama_attach_threadpool`; apply load mode; `setenv` the hex knobs before backend init on
  Hexagon builds. Extend `referenceDecode` to also report decode-phase timing (promptMillis
  exists; decode tok/s needed for decode-shaped dims).
- app: `tuneDimension` generalization of `runBatchTune`; auto-configure orchestrates
  dimensions in dependency order (backend → decode dims → prompt dims → memory dims), each
  gated by agreement; per-dimension "Tune" buttons on the profile card, notes rendered beside
  the choice like auto-configure results today.
- Load identity: add all new fields so a change reloads instead of reusing a context.

Budget: full pass ≤ ~15 min on the provisioning path (visible progress, foreground service);
lazy per-dimension refinement afterwards; candidates shrink when thermal is hot or RAM is low
(existing profiler data feeds the budget).

## Phase 2 — device intelligence

Feed the framework better facts, and keep its results honest over time.

- **Topology helper** (platform:android): read `/sys/devices/system/cpu/cpu*/cpufreq/
  cpuinfo_max_freq` (and `cpu_capacity`) to group cores into clusters; fallback to "single
  group" when unreadable (empty mask = today's behavior). Threads-per-cluster sanity bounds.
- **ISA report**: surface `llama_print_system_info` (DOTPROD/I8MM/SVE/SME flags) into the
  profile card and the device fingerprint, so a KleidiAI-capable build's kernels are visible
  and HWCAP-gated (KleidiAI runtime detection merged upstream #26076).
- **Re-tune triggers**: the fingerprint-keyed tuning cache re-validates when any component
  changes — app build, GGUF, driver/OS build, device. Profile restored from backup onto a new
  phone re-tunes instead of trusting stale numbers.
- **Power and thermal integration**: gate sweeps on `ThermalManager`/existing thermal state;
  when the UI is in a battery-saving routing mode, prefer the efficiency winner (Phase 5);
  consider `PowerManager` dynamic performance hints during a generation so the CPU stays
  boosted for the turn, revoked after. (Android 11+; the inference process holds the service.)

## Phase 3 — model compatibility and import intelligence

A model is not just weights; its quant mix, architecture, and template decide what this device
can do with it. Bram already reads the first two at import; extend and act on them.

- **Parser extension** (`GgufMetadataReader`): read the tensor table types (bounded — only
  the `name`+`type` per tensor, never tensor bytes) and report quant-type counts, e.g.
  `{q4_0: 129, q8_0: 54, f32: 133}`. This is the exact data the backend support matrix needs.
- **Backend × quant compatibility matrix** (core:domain, pure): which backend offloads which
  ggml types (Hexagon: Q4_0/Q4_1/Q8_0/MXFP4, no Q4_K today; Vulkan/OpenCL: K-quants offload;
  CPU: everything). Import report says, honestly: "This Q4_K_M file can only fully offload to
  the GPU; the NPU would run it as ~60% layers, the rest on CPU" — before anyone loads it.
- **On-device quant conversion**: `llama_model_quantize` is exported by the pinned libllama —
  Bram can offer "Convert to Q4_0 for this device's NPU" (or Q8_0 for quality), producing a
  second GGUF next to the original, on the foreground-service path with disk-space checks and
  progress, then auto-configure the converted file. This directly serves "model compatibility"
  on constrained devices: one import, every backend it can run well.
- **Architecture-aware hints**: hybrid architectures (LFM2.5) cannot KV-trim → KV reuse off
  and say why; GDN (Qwen3.5) is fast on Hexagon (new HTP kernel) → recommend NPU; pure
  attention → KV reuse works, cheaper context stays live. All from metadata already read.
- **Template/tool compatibility**: the known minja/Qwen3.5 template failure is detectable at
  import (template string inspection + the existing fallback logic) — surface "tools disabled
  for this model on this build" instead of discovering it at the first tool call.

## Phase 4 — multi-vendor runtime coverage

- **Unblock LiteRT-LM** (medium effort, high value for Tensor/other devices): the known AAR
  crash (litertlm-android 0.14.0 compiled against coroutines 1.11.0 while its POM declares
  1.9.0) has a documented workaround — use the callback-based `sendMessageAsync(contents,
  callback)` and wrap it in the app's own `callbackFlow`, so the `close()` call compiles
  against the app's coroutines. This activates the already-wired module; Google's auto-
  backend selection (NPU/GPU/CPU) makes it the Tensor NPU path.
- **Runtime-neutral tuning seam**: `measureBackend`/`tuneDimension` currently speak
  `llamaCppClient`. Define a runtime-neutral "candidate probe" interface (load, deterministic
  decode, tokens) so LiteRT-LM (and later ExecuTorch) candidates are tuned and gated the same
  way. LiteRT's deterministic temp-0 decode is the teacher-forcing equivalent.
- **Vulkan: measured, not assumed, per vendor**: ggml-vulkan on Mali works but is
  under-optimized upstream (#18493, #15800 open; crashes fixed). The agreement gate already
  decides; make sure provisioning measures Vulkan wherever it exists and records the verdict
  in the profile card. No per-device hardcoding.
- **ExecuTorch lanes (watch, long-horizon)**: the MLSys 2026 paper measured ExecuTorch QNN
  full-graph HTP delegation as the strongest NPU path on this exact phone; MediaTek and
  Samsung Exynos backends are under active development upstream. Export pipeline (per-model
  `.pte` + calibration) makes this an ecosystem move — keep it a documented option, not a
  commitment. Watch the litertlm AAR releases meanwhile.

## Phase 5 — efficiency and battery modes

- Per-dimension sweeps record wall time; add an **efficiency score** as a coarse proxy:
  tok/s per watt estimated from thermal rise + elapsed time + charge state (no privileged
  power API — state the proxy's limits honestly).
- **Profile modes**: a per-profile "priority" (latency / balanced / battery) that picks the
  tuned winner with the matching objective — prime-cores-only mask for latency, perf-cluster
  mask for battery, etc. Routing already weighs battery via `RoutingEstimates`; feed it real
  tuned numbers instead of estimates.
- KV-type auto choice: `Q8_0` when the tuned context needs memory headroom, `F16` otherwise —
  a measured dimension rather than a fixed rule.

## Phase 6 — cross-device learning (optional, opt-in)

- A local, shareable **device report** (profile card export: fingerprint, per-backend
  measurements, per-dimension winners, ISA/thermal state) for bug reports and community
  comparisons — no network needed.
- Optional anonymous **tuning telemetry** (opt-in, one setting, no content ever) so default
  candidate sets and KleidiAI/HTP expectations can become data-driven per SoC family instead
  of assumed. This is the honest way to "support a wide variety of hardware" without
  hardcoding it: measure many devices, then ship better priors — never per-device truths.

## UI plan

The UI already contains every pattern the tuning framework needs — the batch tuner is the
model for a dimension, and the auto-configure overlay is the model for a run. The plan extends
those patterns rather than inventing new ones.

### Profile card — Tuning section

Today the card's advanced panel has, per load-time knob: a `FilterChip` row of manual presets
(e.g. Flash attention, Attention memory, Batch) and — only for batch — a "Tune batch" button,
a live status line, and a `batchTuneNote` sentence. The pattern to generalize:

- **Every tunable dimension gets the batch treatment**: a chip row where the first chip is
  `Default` (the "device default" semantics — `threads=0`, empty mask, `poll=-1`), then manual
  presets, then a **Tune** button, a live status line while measuring, and a note line when
  done (`batchTuneNote` style: "Tuned threads 6 on Hexagon: 14.2 decode tok/s, matches the CPU
  reference 23/24. Tried 4, 6, 8.").
- **A tuned dimension reads as a result, not a dial**: once measured, the card shows a compact
  summary row in the collapsed state — `MeasurementPills` already render "NPU 1.4x" pills from
  `profile.measurements`; add a parallel **tuning summary strip** ("threads 6 · mask perf ·
  poll 100 · ubatch 1024 · kv F16") so a person opening the card sees the shape of the config
  without opening Advanced. Same pill language, same glanceability.
- **Revert is one tap**: the `Default` chip restores the device default; "Auto-configure"
  remains the whole-card re-measure. Nothing a sweep does is ever hidden.
- **Locking while busy** stays as-is (`enabled = !busy`); a dimension whose backend is absent
  (e.g. hexagon env knobs on a non-Qualcomm build) simply renders no chips — same as backend
  chips today only listing registered backends.

### Auto-configure overlay

`AutoConfigureOverlay` already steps through "measuring backend → batch" with a live status and
fill-in results. Extend the step list to the dimension order (backend → threads → mask → poll →
batch → attention/KV), with per-step status like "Measuring mask perf+prime on Hexagon…" and
per-dimension winners filling in as they land. The final summary lists every dimension's winner
and its note; the existing blur and dismiss behavior is unchanged. The overlay is also where a
sequential-refinement run reports which candidates were skipped (thermal, low memory, absent
backend) — a skipped dimension is a recorded line, not a silent omission.

### Import compatibility report (Phase 3)

On import, after the metadata pass and before/alongside the baseline profile, show a
**compatibility verdict sheet**: per-backend offload estimate from the quant mix ("This
Q4_K_M file can fully offload to the GPU; the NPU would run ~60% of layers, the rest on CPU"),
template/tool warnings (the known minja failures), and architecture hints ("Hybrid — KV reuse
isn't available; GDN — Hexagon-accelerated"). Where a conversion applies (Q4_K → Q4_0/Q8_0 for
the NPU), a **"Convert for this device"** button runs on the foreground-service path with
progress and a disk-space check, then auto-configures the converted file. The verdict stays
available later as a "Compatibility" line on the profile card — a sentence, not a modal.

### Settings and diagnostics

- **Tuning gates** (Settings): measure when battery is not low / thermal is not hot (existing
  profiler state), and "Measure on import" default-on toggle.
- **Diagnostics**: the existing diagnostics card gains the device fingerprint (build, driver,
  ISA flags from `llama_print_system_info`, topology groups) and per-dimension winners — this
  doubles as the shareable device report (Phase 6) with an Export action.
- **Telemetry opt-in** (Phase 6): a single switch, clearly local-only-until-you-opt-in,
  next to the device report.

### Interaction principles

- A sweep never blocks chat: tuning runs on the provisioning path (foreground service +
  overlay); per-dimension buttons are the lazy refinement path; first chat after import is not
  gated on a full sweep (the existing import flow already defers auto-configure behind an
  action).
- Every measured choice is a sentence a person can read ("why" is the note), every manual
  override is one tap from a measured one, and the Default chip is always present.
- Consistency with the house style: pills for results, sentences for notes, chips for presets,
  expanders to keep the card short until someone wants a dial.



- Validation: CPU reference pinned; every candidate agreement-gated before speed counts.
- Load identity: every new field joins the key; stale-context reuse impossible.
- Failure ladder: candidate fails to load / crashes the inference process → recorded, skipped;
  nothing passes → keep safe default, note says so.
- Storage: tuning results persist fingerprint-keyed in the existing JSON-store style (like
  profiles/automations), capped in size, with the same atomic-write discipline.
- UI: see the UI plan section above.

## Risks and open questions

- **Mali Vulkan**: correctness gate decides, but "slow but correct" beats "CPU" only when it
  actually wins — the harness measures that; upstream is improving it.
- **litertlm AAR**: workaround is documented but unverified in-tree; upstream may fix the POM
  (or bump coroutines) at any time — re-check before investing heavily.
- **`llama_model_quantize` on device**: needs ~2x disk and a bounded memory pass; verify
  progress/cancellation hooks and time on the phone before shipping the conversion feature.
- **Performance hints**: OEM-dependent behavior; treat as best-effort with a probe and a
  fallback, like every other dimension.
- **Tuning time**: full auto-configure must stay bounded; if a device is slow, candidates
  shrink and lazy refinement picks up the rest — never block first chat on a full sweep
  (import already defers auto-configure behind an explicit action).
- **KleidiAI in the APK**: adds a fetched dependency to the build (CI/clean-room note), and
  runtime detection (HWCAP) decides kernels — safe on old devices by construction.

## Suggested sequencing

1. Phase 1 (tuning framework) — the lever with the largest measured headroom (HTP config
   alone is ~10–30% decode on the S25 Ultra, and it generalizes to every device).
2. Phase 2 (device intelligence) — topology helper, fingerprint cache, re-tune triggers.
3. Phase 3 (import compatibility + quant conversion) — highest user-visible value for
   "works with a wide variety of hardware".
4. Phase 4 (LiteRT-LM unblock + runtime-neutral seam) — second NPU lane, Tensor coverage.
5. Phase 5 (efficiency modes) — needs Phase 1's tuned numbers to rank against.
6. Phase 6 (telemetry) — only after everything else is measured and opt-in.

## Phase 1 implementation notes

Phase 1 is implemented and measured (see results below). The notes record the final design
decisions rather than open questions:

- **Profile migration is additive**: `ModelProfileStore` persists hand-rolled org.json with
  `optX` defaults and a `sanitized()` pass in `save()` — the new fields (threads, mask, poll,
  priority, load mode, hex flags) migrate cleanly; old profiles load untouched. Sanitization
  lives in a pure, JVM-testable `sanitizedRuntime()` on the model.
- **Load identity is an equality chain** in `InferenceProcessService.loadModel` — extended with
  every new field (including `threads`, which was previously not part of the identity, so a
  thread change now reloads rather than reusing a context). Extracting a pure `LoadIdentity`
  matcher for unit tests is the remaining test-debt item.
- **Hex flags are process-level**: `ggml-hexagon` reads `GGML_HEXAGON_*` once, in
  `ggml_hexagon_init` at backend registration (verified in the pinned source). The restart
  protocol is therefore: the service compares the request against `processHexEnv`, unloads,
  returns `restartRequired`, and schedules its own death (`Process.killProcess` 500 ms after
  the response); the client unlinks, unlinks the death watch, and waits on the binder death
  (8 s cap) before binding fresh and retrying exactly once. A naive unbind+rebind races the
  process teardown and reuses the dying process — the self-kill is what makes it deterministic.
- **A hung candidate is a recorded failure**: each candidate measurement runs under a
  5-minute `withTimeout`; on timeout the client also restarts the inference process, because a
  hung native call wedges the service's single executor thread and only a fresh process can
  serve the next candidate. Timeouts land in the note as "(timed out)", and a sweep that fails
  outright writes a "tuning failed (reason); left at the device default" note — a failed
  measurement is a fact on the card, never a silence.
- **Threadpool lifecycle in JNI**: the `ggml_threadpool` pair is created per load from the
  request (mask array bounded by `GGML_MAX_N_THREADS`), attached via
  `llama_attach_threadpool` to the chat context only (reference contexts keep the default
  pool — the yardstick is never tuned), freed on unload/reload. The embedder context is
  untouched.
- **`threads=0` semantics**: the service resolves 0 to `availableProcessors()`, so "Default"
  is exactly the current build's behavior.
- **Auto-configure order**: backend measurement → decode dimensions (threads → mask → poll →
  load mode → hexagon flags when on the NPU) → batch, so the batch is measured under the
  configuration the decode dimensions chose. Batch candidates widened to six, the wide end
  covering the Hexagon reference's ubatch 1024.
- **AIDL carries JSON**: the load request already travels as one JSON object, so new fields
  needed no interface change.

## Phase 1 measured results — S25 Ultra (HTP v79), 2026-08-12

LFM2.5-2.6B-Q4_0 on the Hexagon profile. Every candidate in every dimension passed the
teacher-forced agreement gate against the CPU reference; speed ranked the passers, and the
winners were written to the profile with dated notes.

| Dimension | Tried | Winner | Against the upstream Snapdragon reference |
|---|---|---|---|
| Threads | 8, 6, 4 | **6** | confirms `-t 6` |
| CPU mask | all cores, prime-only (0x3), all-strict (0xff) | **all cores** | rejects `--cpu-mask 0xfc`-style pinning here |
| Poll | none, aggressive | **aggressive (100)** | confirms `--poll` |
| Load mode | mmap, no-mmap | **mmap** | rejects the reference's `-no-mmap` |
| Hexagon flags | defaults, HMX+host buffers | **defaults** | HMX matched the reference but lost on timing |

Three of five reference knobs confirmed, three rejected by measurement — the device decided,
which is the point of the harness. Note the HMX candidate required the process-restart path;
it measured cleanly with the deterministic self-kill restart.

Open items from the hardening pass (see the evaluation before it): the identity-matcher test
extraction, one full end-to-end auto-configure run on the phone, and the KleidiAI CPU slice
with reference re-validation.

## Phase 1 validation notes (2026-08-12, after the fixes)

- The load identity now lives in a pure `LoadIdentity` (runtime:llamacpp), parsed and
  normalized once per request (`from(request)`) and compared whole — the reuse rule is
  unit-tested (6 cases), including the mask canonicalization. The service holds a single
  `loaded: LoadIdentity?` instead of seventeen parallel fields.
- `canonicalCpuMask` is strict: a partially-garbage mask ("not-a-mask") is rejected whole
  rather than filtered into a wrong one ("aa").
- The full auto-configure run (backends → five decode dimensions → six-candidate batch)
  completes on the phone in ~5 minutes and persists atomically. It exposed one real race:
  `reloadProfiles` is async, so each tuning phase was reading a stale profile list and
  overwriting the previous phase's writes (fresh tuning notes landed on stale measurements).
  The tuning flows now await `syncProfiles` directly.
- End-to-end run results: NPU 1.29x (95% agreement) chosen over OpenCL 1.21x; batch
  1024/512 at 27 prompt tok/s; threads 6, all-cores mask, aggressive poll, mmap, hexagon
  defaults.

## KleidiAI CPU slice — shipped and measured (2026-08-12)

The pin was bumped to `132753bf` (2026-08-12) to pick up the KleidiAI runtime feature
detection (#26076): at the previous pin, `ggml_cpu_has_dotprod()` was compile-time-gated and
always returned 0 in a baseline build, so kernels never dispatched ("CPU features mask 0").
Bram compiles the KleidiAI dotprod/i8mm kernels plus the dispatching trait as an OBJECT
library with `-march=armv8.6-a+dotprod+i8mm`, linked into `ggml-cpu` (per-source compile
options don't survive into a target defined in another directory — the object library's do).
The trait selects kernels at runtime via HWCAP, so older devices stay on baseline paths.

Measured on the S25 Ultra (LFM2.5-2.6B-Q4_0): CPU prompt speed ~16 → **~86 tok/s** and
decode roughly doubled. The NPU dropped to **0.54x** against the new CPU reference (was 1.3x
against the old one), and auto-configure correctly re-routed the profile to CPU — the whole
"agreement gates correctness, speed chooses the winner" system re-validated itself under the
new reference and flipped the recommendation honestly. (The note text for "all agreed, none
beat the CPU" was fixed as part of this, and stale hexagon flags are cleared when the chosen
backend is not the NPU.)

Leftover from the slice, worth revisiting later: the SVE KleidiAI kernels (8 Elite has
128-bit SVE) were not added — same mechanism, more `-march` surface; and the full multi-ISA
`GGML_CPU_ALL_VARIANTS` build remains the upstream-sanctioned long-term shape if packaging
many backend `.so` files ever becomes acceptable.

## Phase 2 — fingerprint-keyed measurements and sweep gates (shipped 2026-08-12)

- `MeasurementFingerprint` (core:domain, pure, tested): device hash (the profiler's short
  hash) + app build + llama.cpp commit + CPU feature flags parsed from the engine's system
  info. A change in any part invalidates a recorded measurement.
- `ModelProfile.measuredFingerprint` records where `measurements`/notes were taken; every
  result-writing path (auto-configure, every tune, failure notes) stamps it. The profile
  card shows an explicit "measured on a different device or build — re-measure" line when
  it differs, so a restored backup, OS update, app rebuild, or pin bump is never silently
  trusted.
- Sweep gates: auto-configure and every tune refuse (with a readable reason) when the
  device is thermally throttling (beyond "light") or has less than 1.5 GB free RAM — a
  measurement taken while throttling or swapping is a lie with a timestamp.
- Verified on the S25 Ultra: a tune stamps `d=…|app=…|engine=132753bf…|cpu=…` into the
  profile. Deferred: power hints during generation, and surfacing the full ISA/HWCAP truth
  (the engine's system-info flags are compile-time probes; the KleidiAI trait does its own
  getauxval detection).

## Phase 3 — import compatibility and on-device quant conversion (implemented, emulator-validated 2026-08-13)

- `GgufMetadataReader` now walks the bounded tensor table and returns per-tensor quant counts
  (`{q4_0:129, q4_1:3, f32:133, ...}`); persisted on `LocalModelRecord` (additive JSON).
- `QuantCompatibility` (pure, tested): per-backend share of offloadable weight tensors. The
  Hexagon set is q4_0/q4_1/q8_0/mxfp4/iq4_nl at the pin; the GPU set excludes mxfp4/nvfp4/tq.
- The profile card shows a Compatibility chip row (CPU/GPU/NPU share) and, when the NPU can't
  take the file fully, offers **Convert to Q4_0 / Q8_0**. Conversion runs `llama_model_quantize`
  in the isolated process (output/embedding tensors to Q8_0 so the result is fully
  NPU-offloadable), then registers the output like an import (SHA-256, metadata, default
  profile) and auto-configures it.
- Validated on the emulator: import recorded counts, the card rendered "CPU 100% · GPU 100% ·
  NPU 52%" with the convert buttons, and Q4_0 → Q8_0 conversion produced a correct 763.78 MiB
  file (8.52 BPW) with a second catalog record and profile.
- Resolved (2026-08-13, emulator): the "stalled" auto-configure after conversion was neither a
  stall nor a kick-off bug — the converted Q8_0 model hung once during a teacher-forced replay,
  the 5-minute candidate timeout fired, the process unwedged via the restart path, and the sweep
  continued to completion (the earlier "empty profile" was checked mid-run). The overlay shows
  the timed-out candidate in red, as designed. One polish followed: with no accelerators on the
  device, the CPU reference bar now gets its own tok/s from a direct reference decode instead of
  showing 0/0. Remaining note: conversion still blocks the service's single executor (chat
  waits), and the phone path is unvalidated.

## Sources

- PERFORMANCE_OPTIONS.md (pin analysis, Hexagon reference config, KleidiAI, LiteRT-LM
  issue #2812 workaround, MLSys 2026 ExecuTorch paper).
- llama.cpp at pin `132753bf` (bumped 2026-08-12 for KleidiAI runtime feature detection
  #26076): `llama.h` (`llama_model_quantize`, `llama_attach_threadpool`),
  `ggml.h` (`ggml_threadpool_params` cpumask/strict/poll/prio), `common/arg.cpp` (poll args).
- GitHub issues: #18493 (Mali G720 Vulkan tuning, open), #15800 (embedded-GPU mul_mat
  variant, open), #23057 (Mali-G720 crash, fixed), #17593 (mobile ARM64 optimizations).
- Upstream: KleidiAI runtime detection #26076 (2026-08-12); load-mode auto #26081.
