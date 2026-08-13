# Runtime performance options — research notes

Research date: 2026-08-12. Target: S25 Ultra (Snapdragon 8 Elite, Hexagon HTP v79, Adreno 830).
Bram's llama.cpp pin: `132753bf` (master, 2026-08-12) — picked up for the KleidiAI runtime
feature detection (#26076).

Everything below must still pass Bram's own teacher-forced validation before it counts. That is
not a formality: a kernel or config change can change CPU output slightly, which means every
recorded accelerator agreement score must be regenerated against the new CPU reference.

## 1. How Bram runs today

| Path | Status | Measured (S25 Ultra, LFM2.5-2.6B-Q4_0) |
|---|---|---|
| CPU, `ggml-cpu` | validated, default oracle | ~16 tok/s prompt (pre-tuning), ~9.8 tok/s decode |
| Hexagon NPU (`ggml-hexagon`, skels v73–v81) | validated 95.8% (23/24) | ~13 tok/s decode, 34–35 tok/s prompt, 1.5–1.9x CPU |
| OpenCL (Adreno) | validated 96% (23/24), `BRAM_OPENCL` opt-in | 1.11x vs CPU (Qwen3.5-Q4_0), needs vendor stub |
| Vulkan | compiles, **fails correctness** on Adreno 830 | 75% agreement at 1 layer, all-zero logits past 7 |
| LiteRT-LM | module wired, inert — upstream AAR crash | blocked |
| Embeddings | second llama.cpp context, CPU | resident, mean-pooled, L2-normalized |
| Remote | OpenAI-compatible Chat Completions + Responses | vLLM/Ollama/LM Studio compatible |

Current native settings shape (jni layer + `InferenceProcessService`):
- threads = all cores (8); **no cpu mask / affinity**
- `n_batch`/`n_ubatch` per-profile; NPU profile tuned to 512/256
- flash attention Auto, KV type F16 default (Q8_0 halves cache at a decode cost)
- `LLAMA_LOAD_MODE_MMAP` always; load identity includes every setting
- `GGML_LLAMAFILE=OFF`, `GGML_OPENMP=OFF`, KleidiAI not enabled

## 2. The pin is already the fast one — the config is not

The pinned revision (Aug 4, 2026) already contains the entire 2026 Hexagon kernel work, because
it all merged months earlier:

- op batching + buffer/cache management (#21705, Apr) — eliminates tensor duplication,
  on-the-fly mmap of weight/compute buffers, up to ~2x prefill on small models
- DMA regression fix + DMA cache (#21137, Mar) — +7–12% decode on Gen3/4/5
- GDN kernel for Gated Delta Net (**Qwen3.5 is GDN**) (#22837, May) — +50–70% pp, +35–62% tg
  on v75/v81 vs CPU fallback for that op
- VTCM layouts + improved MUL_MAT/MUL_MAT_ID/FLASH_ATTN_EXT pipelines (#25425, Jul) — ~2x
  op-level gains, better at 16–32K context
- L2 cache rework with dirty-bit lazy flushing (#25762, Jul) — modest but real, better in
  long contexts

So **updating the pin is not the lever** (only one ggml-hexagon commit landed after Aug 4: the
load-mode-auto default change, #26081). The big win is configuration: Bram runs the HTP with
none of the upstream Snapdragon reference settings. Qualcomm/llama.cpp's own `docs/backend/
snapdragon` and `scripts/snapdragon` use, for HTP:

```
-t 6 --cpu-mask 0xfc --cpu-strict 1 --ubatch-size 1024 -fa on -ngl 99
--poll 1000 -no-mmap --device HTP0
GGML_HEXAGON_USE_HMX=1 GGML_HEXAGON_NHVX=0 GGML_HEXAGON_HOSTBUF=1
GGML_HEXAGON_NDEV=1 GGML_HEXAGON_OPBATCH=0x1
```

vs Bram's `threads=8, ubatch=256, mmap, no poll setting, no env knobs`. Each difference maps
to a plausible gain on decode (threads on the 6 perf cores keep the prime core free for the
UI; ubatch 1024 fits HTP's preferred chunking; `--poll` changes the DSP wait path; non-mmap
load gives the backend a repack/scratch path closer to what QNN wants).

**Recommended action:** make these per-profile settings (threads/mask, ubatch, poll, hex env
vars, mmap toggle) and run the existing auto-configure/tune-batch harness against each
combination under teacher forcing. Expected: 10–30% NPU decode, larger at 16K+ contexts.

## 3. CPU: KleidiAI is the fastest available CPU path — shipped and measured

llama.cpp's Android documentation disables llamafile (`GGML_LLAMAFILE=OFF` — Bram's choice
matches upstream) but the modern CPU speed comes from Arm KleidiAI (`GGML_CPU_KLEIDIAI=ON`):
Asimd/dotprod/i8mm/SVE microkernels with runtime feature detection (upstream #26076, which the
pin was bumped to 2026-08-12 for). The Oryon cores on the 8 Elite have dotprod + i8mm + SVE
(no SME), so KleidiAI applies.

How Bram builds it (a plain arm64 Android build compiles no ISA extensions, and ggml's feature
probes are compile-time, so the integration is Bram's own): the KleidiAI dotprod/i8mm kernels
and the trait that dispatches to them are compiled as an OBJECT library with
`-march=armv8.6-a+dotprod+i8mm` and linked into `ggml-cpu`; the trait runtime-checks the CPU
(HWCAP via `getauxval`) before selecting a kernel, so old devices never execute the extended
instructions while the rest of ggml-cpu stays at the armv8-a baseline. See the KleidiAI block
in `runtime/llamacpp/src/main/cpp/CMakeLists.txt`.

Measured on the S25 Ultra (LFM2.5-2.6B-Q4_0, 2026-08-12): CPU prompt speed went from ~16 to
**~86 tok/s** (~5x) and decode roughly doubled — enough that the CPU now **beats the Hexagon
NPU** on this model (NPU measured 0.54x against the KleidiAI CPU reference, down from 1.3x
against the old one). Auto-configure re-routed the profile to CPU, exactly as the harness is
supposed to. KleidiAI changes CPU numerics, so every accelerator agreement score was
re-measured against the new reference (all still passing).

Since CPU is the correctness oracle AND the fallback AND the embedder, this raises every
floor: validation generation, CPU-only chat, embeddings, and the storage-assisted path.

Build form: `GGML_CPU_KLEIDIAI=ON` for arm64 only (fetches KleidiAI v1.24.0, MIT — CI/clean-
room note required, like OpenCL). Keep `GGML_LLAMAFILE=OFF`.

## 4. NPU beyond llama.cpp: what the MLSys 2026 paper says about this exact phone

A MLSys 2026 paper benchmarks ExecuTorch vs llama.cpp vs ONNX Runtime vs LiteRT vs CoreML on
**the same Samsung Galaxy S25 Ultra**:
- ExecuTorch's QNN delegate (full-graph HTP delegation, A8W8/A16W4, no CPU fallback) has
  **stronger prefill than Qualcomm's own QAIRT**, and QAIRT edges it on decode (per-channel
  vs per-group quantization).
- llama.cpp's Hexagon backend is explicitly called out as having **ops falling back to CPU,
  inflating latency vs full-graph QNN delegation** — although the paper's data predates much
  of the 2026 kernel work above.
- The ROUNDING call: "closing the full gap to Qualcomm's proprietary engine from llama.cpp is
  unlikely without upstream kernel work" (llama.cpp discussion #21702: ~12 tok/s llama.cpp vs
  ~26 tok/s Qualcomm HTP engine on Qwen3-4B Q4_0, before this year's kernel rounds).

Practical reading for Bram:
- The NPU ceiling with llama.cpp is real but Bram is nowhere near it on configuration alone.
- **ExecuTorch QNN is the strongest measured NPU path on this device** but it is a separate
  ecosystem: per-model export, static-quant calibration, `.pte` artifacts, per-SoC context
  binaries, no GGUF. Not a switch; only a long-horizon experiment if raw NPU throughput
  becomes the bottleneck after config work.
- Qualcomm's proprietary paths (QAIRT / AI Hub / Amuse) are apps or SDKs, not embeddable GGUF
  runtimes; they are the reference ceiling, not a component.

Model-side NPU notes: Hexagon supports Q4_0/Q8_0 well; Q4_K types are NOT offloadable to HTP
(currently fall back to CPU). A Q4_0/Q8_0 pure-attention model (Qwen3.5 1–4B, Gemma4 E2B)
both fits HTP and unlocks KV reuse across turns (the hybrid LFM2.5 cannot partially trim).

## 5. GPU: Vulkan retest is cheap; OpenCL stays

- Vulkan on Adreno 830 has been failing validation since Milestone 2. It's still broken
  upstream as far as any fix landing, but the 2026 Vulkan backend did gain real work (e.g.
  TQ2_0 support, i8mm paths). Re-running Bram's existing teacher-forced comparison costs
  ~1 minute — worth doing once after the next pin bump, low probability but zero cost.
- OpenCL (validated, ~1.11x) is the dependable GPU path; MLC-LLM's Android support is
  OpenCL-only too, plus per-model TVM compilation and an Adreno weight-layout landmine
  (`q4f16_1` → 20–50 s system freeze on prefill; `_0` layouts fine), so MLC offers no
  advantage over ggml here and no GGUF workflow. Verdict: skip.
- GPU is not where the easy wins are on this hardware; CPU+KleidiAI and NPU config are.

## 6. Alternative engines — verdicts

| Engine | Verdict for Bram |
|---|---|
| **llama.cpp master (current pin)** | Keep. It is the fastest moving of all GGUF engines, has the only GGUF-native NPU backend, and Bram re-pins weekly within ~8 days. |
| **llamafile kernels** | Already OFF — matches upstream Android recommendation. Don't turn on; KleidiAI is the modern path. |
| **LiteRT-LM (Google)** | The strategic Google runtime; **Bram's module is blocked by a known upstream AAR bug** (0.14.0 compiled against coroutines 1.11.0 while its POM declares 1.9.0 → NoSuchMethodError on every finished turn). The bug has a documented workaround: use the callback-based `sendMessageAsync(contents, callback)` instead of the Flow API and wrap it in your own callbackFlow. **This is an actionable medium-effort fix** that could unblock the existing module (v0.11+ has MTP/speculative decode for Gemma4, NPU auto-backend selection). Note: LiteRT-LM runs converted `.tflite` artifacts, not GGUF — there is a converter, but this is a parallel lane, not a drop-in. |
| **MediaPipe LLM Inference** | Maintenance-only (Google says migrate to LiteRT-LM). Dead end. |
| **ExecuTorch + QNN** | Strongest measured HTP results on this exact phone (see §4). Requires a full export/calibration pipeline per model+device; `.pte` artifacts; no KV-reuse-style orchestration parity. Watch-level only. |
| **vLLM** | No on-device/Android support — server-only (and Bram already talks to any OpenAI-compatible host, so a vLLM server "just works" as a remote runtime today; nothing to change). |
| **MLC-LLM** | Skip (see §5). |

## 7. Decode-speed levers at the model layer

- **Speculative decoding with a small draft model** — llama.cpp supports draft models in the
  pinned revision; needs a same-family tiny GGUF (≈5–10% size). Could add ~1.3–1.5x decode on
  CPU paths; HTP interaction (draft on CPU, target on HTP) is plausible but unproven here.
  Medium effort: the embedding second-context machinery already proves Bram can hold two
  models in the inference process.
- **MTP (multi-token prediction)** — llama.cpp MTP support exists (Qwen3.6 GGUFs, ~1.8–1.9x
  decode on CUDA; hexagon devs are already tuning against MTP drafters). Only for models that
  ship MTP heads; not available for LFM2.5/Qwen3.5 to date. Future-proofing note.
- **Model choice** — a pure-attention model unlocks KV reuse (prompt ≈ 2500 tok/s vs 100
  cold on Qwen2.5-0.5B already demonstrated) and Qwen3.5-family models get the new GDN HTP
  kernel. The Q4_0/Q8_0 quantization keeps HTP offload full.
- **KV cache** — F16 default is right; Q8_0 halves cache for long contexts when decode speed
  can give it up. Already profile-tunable; nothing new.

## 8. Priorities

1. **HTP reference config as profile settings** (threads/mask, ubatch 1024, poll, hex env,
   mmap) — measure each under teacher forcing. Highest ROI, days of work, reuses the harness.
2. **KleidiAI on** for CPU profiles — regenerating the CPU reference invalidates all recorded
   agreement scores; run auto-configure once after landing.
3. **Re-validate Vulkan on the new pin** (one comparison, ~free). Likely still fails.
4. **Unblock LiteRT-LM via the callback workaround** if a second NPU/GPU lane (XNNPACK,
   Google's converted models) is wanted.
5. **Speculative decoding experiment** once a draft-compatible model set exists on the phone.
6. **ExecuTorch QNN / MTP** — long-horizon experiments only; both are ecosystem moves, not
   config changes.

## 8b. On-device tuning that adapts to the device

Yes — the harness Bram already has is exactly the right shape for this, and the remaining
knobs are all reachable through the pinned llama.cpp API. The design is "a tuning dimension is
a load-identity field with a candidate list, a teacher-forced gate, and a note written on the
profile" — the batch tuner already *is* one; the others are the same pattern with different
candidates.

### What already exists

- `ModelProfile` carries backend, context, flash-attention mode, KV type, batch/ubatch,
  `measurements[]`, `autoConfiguredNote/At`, `batchTuneNote/At` — persisted per model.
- The teacher-forced harness (`measureBackend` / `runBatchTune` in `MainViewModel.kt`):
  fixed CPU reference decode → each candidate replayed via `teacherForced` → agreement must
  pass `AcceleratorAgreement.isUsable` → wall time ranks the passers → winner + note written.
- The load-identity cache (`InferenceProcessService`) keys every load on model/context/
  batch/ubatch/attention/KV/backend/reasoning, so a settings change can never silently reuse
  a stale context.
- Auto-configure runs the full flow on the provisioning path with a foreground service and
  a visible overlay, then a batch tune for the chosen backend.

### Knobs that are reachable today (verified against the pin)

| Knob | Plumbing | Where it lives |
|---|---|---|
| threads | existing load field | `ggml_threadpool_params.n_threads` |
| CPU affinity mask | new load field | `ggml_threadpool_params.cpumask[]` + `strict_cpu` |
| poll level | new load field | `ggml_threadpool_params.poll` (0–100) |
| load mode (mmap/no-mmap/auto) | new load field | `llama_model_params.load_mode` |
| flash attention, KV type, batch | already fields | existing |
| Hexagon env knobs (`GGML_HEXAGON_USE_HMX`, `NHVX`, `HOSTBUF`, `NDEV`, `OPBATCH`) | `setenv()` in the inference process before backend init, per load | env vars, no-ops on non-Hexagon builds |

The JNI layer creates its own `ggml_threadpool` pair from the load request and attaches it
with `llama_attach_threadpool(ctx, tp, tp_batch)`; an all-zero `cpumask` means default
affinity, so a device with no topology info just behaves like today.

### Device-adaptive candidate generation

The point of "works with a lot of devices" is that **candidates come from measured device
facts, not from a per-device table**:

- **Core count** → thread candidates like `{n, n-2, max(2, n/2)}`.
- **Cluster topology** → read `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`
  (fallback: unreadable → single "all cores" candidate, which is the empty mask = safe
  default). Candidates: all cores / prime+perf / perf only.
- **Backend registration** → NPU/GPU-only dims (hex env vars, gpuLayers sweeps) are offered
  only when that backend actually registered on this device; a MediaTek or Tensor phone
  simply never sees them.
- **ISA features** → informational (KleidiAI's runtime detection, #26076, picks the kernels);
  a HWCAP-less device still runs the baseline kernels.
- **Memory pressure / thermal state** → shrink or skip the sweep entirely when hot or low —
  the device profiler already reads these.
- **Stored results** are keyed by the validation fingerprint (device + driver + build +
  model hash + settings, per ARCHITECTURE.md), so re-running auto-configure skips dimensions
  whose fingerprint has not changed, and a backup restored onto a different phone re-tunes
  instead of trusting stale numbers.

### Sequencing and guardrails

1. Auto-configure order: backend (existing) → threads/mask/poll (decode-shaped dims) →
   batch/ubatch (prompt-shaped) → attention/KV (small sweeps). Per-dimension "Tune" buttons
   stay available for refinement, exactly like "Tune batch" today.
2. **The CPU reference stays pinned** (default threadpool, fixed settings) regardless of what
   gets tuned, so every comparison stays apples-to-apples; tuned pools apply to chat loads.
3. The agreement gate runs before speed ranks, on every dimension — a knob that changes the
   math (attention, KV, ubatch) can never win by being wrong.
4. Every new field joins the load identity; a changed value unloads and reloads rather than
   reusing a stale context (existing mechanism, just more fields).
5. Failure ladder unchanged: candidate fails to load or crashes the inference process → it is
   recorded and skipped; nothing passing means keep current settings and say so in the note.
6. Budget the whole pass at ~10–15 min on the provisioning path (visible progress), with
   lazy per-dimension refinement afterwards.

Implementation shape: new `ModelProfile` fields (`threads=0` device default, `cpuMask=""`,
`cpuStrict=false`, `poll=-1`, `loadMode=Auto`, optional hexagon flags) with `sanitized()`
bounds; JNI `load()` gains the params and builds the threadpool; a generic
`tuneDimension(profile, dimension, candidates)` in `MainViewModel` generalizes
`runBatchTune`; a small sysfs topology helper in `platform:android` feeds candidates; the
profile card gains a per-dimension note line.

## Sources

- llama.cpp pin `132753bf` = master @ 2026-08-12 (GitHub), bumped from `474c92e` for the
  KleidiAI runtime feature detection (#26076).
- Hexagon PRs: #21705 (op batching), #21137 (DMA), #22837 (GDN for Qwen3.5),
  #25425 (VTCM layouts), #25762 (L2 lazy flush). All merged before the pin.
- Snapdragon reference config: llama.cpp `docs/backend/snapdragon`, `scripts/snapdragon`,
  and public run logs in #21705/#25425/#22837 threads.
- KleidiAI: llama.cpp `docs/build.md` (Arm® KleidiAI section), #26076 (runtime feature
  detection, 2026-08-12), Arm kleidi-llama Android binding.
- MLSys 2026: "ExecuTorch... on-device LLM frameworks" paper, performance table on Samsung
  Galaxy S25 Ultra (Snapdragon 8 Elite).
- llama.cpp discussion #21702 (llama.cpp vs Qualcomm HTP engine gap).
- LiteRT-LM issue #2812 (0.14.0 noSuchMethodError root cause + callback workaround).
- MediaPipe LLM Inference Android guide (maintenance-only notice).