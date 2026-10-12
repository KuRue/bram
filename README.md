<p align="center">
  <img src="docs/assets/bram-banner.svg" alt="Bram — Intelligence, on your terms. Private local AI for Android." width="100%" />
</p>

<p align="center">
  <a href="https://github.com/KuRue/bram/actions/workflows/android-ci.yml"><img src="https://github.com/KuRue/bram/actions/workflows/android-ci.yml/badge.svg" alt="Android CI" /></a>
  <a href="https://github.com/KuRue/bram/actions/workflows/android-release.yml"><img src="https://github.com/KuRue/bram/actions/workflows/android-release.yml/badge.svg" alt="Android release" /></a>
  <img src="https://img.shields.io/badge/Android-10%2B-7be3cf?logo=android&amp;logoColor=white" alt="Android 10 and newer" />
  <img src="https://img.shields.io/badge/status-alpha-9ca9ff" alt="Alpha software" />
</p>

<p align="center">
  <a href="https://github.com/KuRue/bram/releases"><b>Releases</b></a> ·
  <a href="docs/BUILDING.md">Build it</a> ·
  <a href="ARCHITECTURE.md">Architecture</a> ·
  <a href="ROADMAP.md">Roadmap</a> ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>

## Your phone. Your models. Your assistant.

Bram runs local AI on Android, with durable conversations, tools, memory, and scheduled work.
Import a GGUF, load a model, and start chatting. When you choose to use a remote model,
OpenAI-compatible endpoints fit into the same agent loop, behind an explicit privacy policy.

It profiles your hardware, budgets memory, and tests accelerators against CPU output before
calling them validated. Native inference runs in a separate process so a backend crash can be
contained without taking down the conversation UI.

| Chat that lasts | Tools that do work | Hardware you can trust |
| :--- | :--- | :--- |
| Persistent threads, Markdown, streaming, and background runs. | Permission-gated tools, remote MCP, versioned skills, and scheduled automations. | Device profiling, memory-aware plans, accelerator validation, and fallback. |

## Get Bram

**Android 10+ · ARM64 phone · bring your own model or endpoint**

- **Release builds:** download the APK and `SHA256SUMS` from [Releases](https://github.com/KuRue/bram/releases) when a version is published. The release pipeline prepares signed APKs for review; a draft is visible only to maintainers.
- **Development builds:** open a successful [Android CI run](https://github.com/KuRue/bram/actions/workflows/android-ci.yml) and download its `bram-debug-…` artifact. GitHub requires sign-in for workflow artifact downloads. Unzip it to get the APK.
- **Build locally:** follow [Building Bram](docs/BUILDING.md) for the SDK, native toolchain, and emulator instructions.

Install the APK, open **Models** to import a GGUF, or configure an OpenAI-compatible endpoint in
**Settings**. Model files are separate downloads and are not bundled with the app.

Release and development builds use different signing keys. Android requires the same key to
update an installed app; back up anything you need before switching between them. CI debug keys
can also differ between runs. See [release setup](docs/RELEASING.md) for stable signing and publishing.

## Where things stand

Bram is **alpha software**. Local CPU chat, conversations, tools, memory, skills, and automations
are implemented. CPU inference has been validated on a Snapdragon 8 Elite phone; the emulator
covers UI and harness flows.

| Runtime / backend | Current evidence |
| :--- | :--- |
| llama.cpp CPU | Working and validated on device. |
| Hexagon NPU | Phone validation measured 95.8% agreement with CPU and 1.5–1.9× speed. Requires an optional proprietary SDK; standard GitHub builds do not include it. |
| Vulkan | Builds and runs, but fails correctness validation on Adreno 830. Availability does not imply correctness. |
| LiteRT-LM / OpenCL | Experimental; broader device validation remains open. OpenCL is opt-in at build time. |

Device adaptation remains active: efficiency modes, reporting, and wider hardware coverage.
On-device quant conversion is implemented, with a converted-Q8_0 replay hang still under investigation.
See the [detailed project overview](docs/PROJECT_OVERVIEW.md) and [roadmap](ROADMAP.md).

## Explore the code

| Module | Role |
| :--- | :--- |
| `app` | Jetpack Compose UI, app state, and dependency assembly |
| `core:domain` / `core:agent` | Agent contracts, context budgets, routing, planning, and tool execution |
| `platform:android` | Device profiling, storage, and encrypted credentials |
| `runtime:llamacpp` | GGUF import, JNI inference, and isolated native process |
| `runtime:openai` / `runtime:litertlm` | Remote model adapters and LiteRT-LM integration |

[Architecture](ARCHITECTURE.md) explains the boundaries. [Security](SECURITY.md) documents privacy,
credentials, and tool trust. [Contributing](CONTRIBUTING.md) covers development expectations.

No open-source license has been selected yet.
