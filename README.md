# Bram

Bram is an Android-first local AI runtime and agent harness. Its goal is to make local models “just work” by profiling the phone, selecting a stable execution plan, and falling back safely across CPU, GPU, NPU, RAM, and storage-assisted execution.

The model is only one component. Bram is designed from the start for tool use, durable memory, versioned skills, scheduled work, and optional OpenAI-compatible remote models.

> **Status: local CPU alpha in development.** The model-first UI, verified GGUF catalog, and
> isolated llama.cpp CPU path are implemented. CI and S25 Ultra
> validation are still required before this path is called working. Accelerator and autonomous
> agent capabilities remain staged work.

## What makes Bram different

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
| Model-first Compose chat, Models, Settings, and diagnostics | Implemented; device validation pending |
| Persisted GGUF import, metadata validation, and SHA-256 | Implemented; device validation pending |
| Android RAM, storage, CPU, Vulkan-feature, and thermal profiling | Working |
| Encrypted-at-rest endpoint API keys using Android Keystore | Working |
| OpenAI-compatible `/chat/completions` with function tools | Working, non-streaming |
| Context budgeting and recent-turn preservation | Working |
| Permission-gated iterative tool loop | Working |
| Read-only `device_status` phone tool | Working |
| Hardware execution-plan generation | Working |
| Separate `:inference` process and AIDL protocol | Implemented |
| Pinned ARM64 llama.cpp CPU generation | Implemented; CI/device validation pending |
| Chat-template application, exact token counts, streaming, cancel, unload | Implemented; validation pending |
| Hexagon, Adreno, Vulkan, and LiteRT native self-tests | Not implemented yet |
| Durable conversations, memory, skills, and automations | Interfaces only |

## Repository layout

| Module | Responsibility |
|---|---|
| `app` | Compose UI, Bram’s default identity, app state, dependency assembly |
| `core:domain` | Runtime-neutral agent, model, memory, tool, and scheduling contracts |
| `core:agent` | Context planning, routing, execution planning, and the tool loop |
| `platform:android` | Device profiling and Keystore persistence |
| `runtime:openai` | OpenAI-compatible Chat Completions adapter |
| `runtime:llamacpp` | GGUF catalog, AIDL process, llama.cpp/JNI CPU runtime |

See [ARCHITECTURE.md](ARCHITECTURE.md) for design details and [ROADMAP.md](ROADMAP.md) for the staged implementation plan.

## Build

Requirements:

- Android Studio with JDK 17
- Android SDK Platform 36 and SDK Build Tools 36.0.0
- An API 29+ ARM64 device for the intended runtime path

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
