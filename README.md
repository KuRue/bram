# Bram

Bram is an Android-first local AI runtime and agent harness. Its goal is to make local models “just work” by profiling the phone, selecting a stable execution plan, and falling back safely across CPU, GPU, NPU, RAM, and storage-assisted execution.

The model is only one component. Bram is designed around tool use, durable conversations, context management, hardware-aware inference, versioned skills, scheduled work, and optional OpenAI-compatible remote models.

> **Status: local CPU chat works on device.** Import a GGUF, load it, and chat, with durable
> conversations, background runs, and a transcript that collapses reasoning and tool calls to a
> line. Validated on a Snapdragon 8 Elite phone and an x86_64 emulator.
>
> The **Hexagon NPU is validated** on that phone at 95.8% agreement with CPU output and 1.5–1.9x
> the speed, depending on the run. **Vulkan is not**: on the same device it disagrees with CPU on a quarter of
> predictions with one offloaded layer and collapses entirely past about seven, while reporting no
> error. It compiles and can be selected, but Bram does not call it validated.
>
> Broader autonomous-agent capabilities, memory, skills, and scheduling remain active development areas.

## What makes Bram different

- **Accelerators are validated, not assumed:** a backend must reproduce the CPU reference before Bram calls it usable. This is not theoretical — it is how a GPU backend that reported success while returning garbage was caught.
- **Hardware-aware planning:** candidate plans account for model weights, KV cache, compute buffers, backend repacking, current memory pressure, and a safety reserve.
- **Stable fallback:** native inference lives in a separate Android process so a driver or backend crash does not have to kill the conversation UI.
- **Runtime-neutral agent:** the same tool loop can use llama.cpp, LiteRT-LM, or an OpenAI-compatible endpoint.
- **Context as a budget:** the transcript remains canonical while summaries, retrieved memories, and KV caches are replaceable projections.
- **Visible storage assistance:** models larger than safe resident RAM are an explicit, opt-in, extremely slow mode—not a hidden performance failure.
- **Private by policy:** remote routing must pass an explicit privacy gate; it is never an accidental fallback.

## Engineering highlights

Bram combines application development, native inference, and systems engineering in one Android codebase:

- Kotlin/Jetpack Compose application with durable conversations and background execution.
- OpenAI-compatible Chat Completions adapter with function/tool support.
- llama.cpp/JNI local inference in an isolated Android process using AIDL.
- Device profiling and execution-plan generation across RAM, CPU, Vulkan, and Hexagon NPU capabilities.
- Correctness-gated accelerator validation instead of trusting backend initialization alone.
- Context budgeting, prompt/template handling, exact token accounting, streaming, cancellation, and model unload.
- Android Keystore-backed encryption for remote endpoint credentials.
- Automated tests, lint, clean-room builds, and APK artifacts through GitHub Actions.

## Bram’s default identity

The harness supplies a versioned `AgentIdentity` to each run. Version `bram/0.1` is intentionally small: Bram is direct, tool-aware, evidence-conscious, and prefers private local execution when policy and capability allow.

The identity is isolated from model adapters in [BramDefaults.kt](app/src/main/kotlin/io/github/kurue/bram/app/BramDefaults.kt). Prompt or identity changes should increment the identity version so future prompt/KV caches can invalidate safely.

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
| OpenAI-compatible `/chat/completions` with function tools | Working |
| Context budgeting and recent-turn preservation | Working |
| Permission-gated iterative tool loop | Working with constrained permissions |
| Read-only `device_status` phone tool | Working |
| Hardware execution-plan generation | Working |
| Separate `:inference` process and AIDL protocol | Implemented |
| Pinned ARM64 llama.cpp CPU generation | Working, validated on device |
| Chat-template application, exact token counts, streaming, cancel, unload | Working |
| Hexagon NPU backend | Working, validated against CPU output |
| Vulkan backend | Compiles and runs; fails correctness validation on Adreno 830 |
| Accelerator validation and layer bisection in-app | Working |
| Collapsed reasoning and tool activity in the transcript | Working |
| Broader autonomous tools, memory, skills, and scheduling | In active development |
| OpenCL and additional runtimes | Experimental / in development |

## Repository layout

| Module | Responsibility |
|---|---|
| `app` | Compose UI, Bram’s default identity, app state, dependency assembly |
| `core:domain` | Runtime-neutral agent, model, memory, tool, and scheduling contracts |
| `core:agent` | Context planning, routing, execution planning, and the tool loop |
| `platform:android` | Device profiling, conversation storage, and Keystore persistence |
| `runtime:openai` | OpenAI-compatible Chat Completions adapter |
| `runtime:llamacpp` | GGUF catalog, AIDL process, llama.cpp/JNI runtime |

See [ARCHITECTURE.md](ARCHITECTURE.md) for design details, [ROADMAP.md](ROADMAP.md) for staged implementation work, [SECURITY.md](SECURITY.md) for the current trust boundaries, and [Building Bram](docs/BUILDING.md) for the toolchain and build process.

## Build

Requirements:

- JDK 17, Android SDK Platform 36, Android SDK Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.30.5
- An API 29+ ARM64 device, or an emulator configured for the supported test ABI

The Hexagon backend additionally needs Qualcomm's Hexagon SDK and is off unless `HEXAGON_SDK_ROOT` points at one. Linux/WSL is the recommended environment for native iteration because the Vulkan host tooling needs a working host C++ compiler.

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

The same clean-room build runs in GitHub Actions and publishes the debug APK as a workflow artifact.

## Security boundaries

- HTTPS is the default for remote endpoints. Cleartext HTTP requires explicit opt-in and is intended only for trusted local networks.
- API keys are stored through Android Keystore-backed encryption and must never be committed.
- Tool output is untrusted input to the model.
- Native model and driver code runs in the isolated inference process.
- Accelerator correctness is measured against a CPU reference before a backend is considered validated.

See [SECURITY.md](SECURITY.md) before enabling broader tools or running untrusted models.

## Contributing

Bram is at the stage where architectural discipline matters more than feature count. Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing runtime boundaries, identity behavior, tool permissions, or persistence.

No open-source license has been selected yet.
