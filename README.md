# Bram

Bram is an Android-first local AI runtime and agent harness. Its goal is to make local models “just work” by profiling the phone, selecting a stable execution plan, and falling back safely across CPU, GPU, NPU, RAM, and storage-assisted execution.

The model is only one component. Bram is designed from the start for tool use, durable memory, versioned skills, scheduled work, and optional OpenAI-compatible remote models.

> **Status: local CPU chat works on device.** Import a GGUF, load it, and chat, with durable
> conversations, background runs, and a transcript that collapses reasoning and tool calls to a
> line. Validated on a Snapdragon 8 Elite phone and an x86_64 emulator.
>
> The **Hexagon NPU is validated** on that phone at 95.8% agreement with CPU output and 1.5–1.9x
> the speed, depending on the run. **Vulkan is not**: on the same device it disagrees with CPU on a quarter of
> predictions with one offloaded layer and collapses entirely past about seven, while reporting no
> error. It compiles and can be selected, but Bram does not call it validated.
>
> Autonomous agent capabilities — a task queue, tools beyond one read-only probe, memory, and
> skills — remain staged work.

## What makes Bram different

- **Accelerators are validated, not assumed:** a backend must reproduce the CPU reference before Bram calls it usable. This is not theoretical — it is how a GPU backend that reported success while returning garbage was caught.
- **Hardware-aware planning:** candidate plans account for model weights, KV cache, compute buffers, backend repacking, current memory pressure, and a safety reserve.
- **Stable fallback:** native inference lives in a separate Android process so a driver or backend crash does not have to kill the conversation UI.
- **Runtime-neutral agent:** the same tool loop can use llama.cpp, LiteRT-LM, or an OpenAI-compatible endpoint.
- **Context as a budget:** the transcript remains canonical while summaries, retrieved memories, and KV caches are replaceable projections.
- **Visible storage assistance:** models larger than safe resident RAM are an explicit, opt-in, extremely slow mode—not a hidden performance failure.
- **Private by policy:** remote routing must pass an explicit privacy gate; it is never an accidental fallback.

## Bram’s default identity

The harness supplies a versioned `AgentIdentity` to each run. Version `bram/0.1` is intentionally small: Bram is direct, tool-aware, evidence-conscious, and prefers private local execution when policy and capability allow.

The identity is isolated from model adapters in [BramDefaults.kt](app/src/main/kotlin/io/github/kurue/bram/app/BramDefaults.kt). We can develop Bram’s fuller persona later without coupling it to GGUF, a vendor backend, or a particular endpoint. Prompt or identity changes should increment the identity version so future prompt/KV caches can invalidate safely.

## Current vertical slice

| Area | State |
|---|---|
| Compose chat, Models, Settings, and diagnostics | Working |
| Persisted GGUF import, metadata validation, and SHA-256 | Working |
| Durable conversations with multiple threads and history | Working |
| Agent runs that continue while the app is backgrounded | Working |
| Retry, edit-and-resend, copy, and Markdown rendering | Working |
| Android RAM, storage, CPU, Vulkan-feature, and thermal profiling | Working |
| Encrypted-at-rest endpoint API keys using Android Keystore | Working |
| OpenAI-compatible `/chat/completions` with function tools | Working, non-streaming |
| Context budgeting and recent-turn preservation | Working |
| Permission-gated iterative tool loop | Working |
| Read-only `device_status` phone tool | Working |
| Hardware execution-plan generation | Working |
| Separate `:inference` process and AIDL protocol | Implemented |
| Pinned ARM64 llama.cpp CPU generation | Working, validated on device |
| Chat-template application, exact token counts, streaming, cancel, unload | Working |
| Hexagon NPU backend | Working, validated against CPU output |
| Vulkan backend | Compiles and runs; fails correctness validation on Adreno 830 |
| Accelerator validation and layer bisection in-app | Working |
| Collapsed reasoning and tool activity in the transcript | Working |
| Task queue, scheduling, and tools beyond `device_status` | Not implemented |
| Model download | Not implemented |
| Memory, skills, and automations | Interfaces only |
| OpenCL and LiteRT | Not implemented |

## Repository layout

| Module | Responsibility |
|---|---|
| `app` | Compose UI, Bram’s default identity, app state, dependency assembly |
| `core:domain` | Runtime-neutral agent, model, memory, tool, and scheduling contracts |
| `core:agent` | Context planning, routing, execution planning, and the tool loop |
| `platform:android` | Device profiling, conversation storage, and Keystore persistence |
| `runtime:openai` | OpenAI-compatible Chat Completions adapter |
| `runtime:llamacpp` | GGUF catalog, AIDL process, llama.cpp/JNI CPU runtime |

See [ARCHITECTURE.md](ARCHITECTURE.md) for design details, [ROADMAP.md](ROADMAP.md) for the staged
implementation plan, and the [current session handoff](docs/HANDOFF.md) for the exact branch,
validation state, and next action.

## Build

Requirements:

- JDK 17, Android SDK Platform 36, SDK Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.30.5
- An API 29+ ARM64 device, or an emulator with `BRAM_EMULATOR_ABI=true` to add `x86_64`

The Hexagon backend additionally needs Qualcomm's Hexagon SDK and is off unless `HEXAGON_SDK_ROOT`
points at one. A local WSL or Linux checkout rebuilds native changes in seconds rather than the
minutes CI takes, and avoids an MSVC requirement on Windows hosts.

Open the repository in Android Studio and let it sync, or use the checked-in wrapper:

```bash
./gradlew --no-daemon --stacktrace \
  :core:agent:test \
  :runtime:llamacpp:testDebugUnitTest \
  :platform:android:lintDebug \
  :runtime:openai:lintDebug \
  :runtime:llamacpp:lintDebug \
  :app:lintDebug \
  :app:assembleDebug
```

The same clean-room build runs in GitHub Actions and publishes the debug APK as a workflow artifact. See [Building Bram](docs/BUILDING.md) for the pinned toolchain, Android Studio setup, and install command.

## Security boundaries

- HTTPS is the default for remote endpoints. Cleartext HTTP requires explicit opt-in and is intended only for trusted local networks.
- API keys are stored through Android Keystore-backed encryption and must never be committed.
- Tool output is untrusted input to the model.
- Agent-created skills begin as drafts and require a separate activation policy.
- Native model and driver code must stay in the isolated inference process.
- Scheduled runs need explicit time, token, tool, network, and charging limits.

See [SECURITY.md](SECURITY.md) before enabling broader tools or running untrusted models.

## Contributing

Bram is at the stage where architectural discipline matters more than feature count. Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing runtime boundaries, identity behavior, tool permissions, or persistence.

No open-source license has been selected yet.
