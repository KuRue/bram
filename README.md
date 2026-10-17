<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/bram-banner.svg" />
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/bram-banner-light.svg" />
    <img src="docs/assets/bram-banner.svg" alt="Bram — Local intelligence. Lasting conversations. The app’s copper dot-lattice mark." width="100%" />
  </picture>
</p>

<p align="center">
  <a href="https://github.com/KuRue/bram/actions/workflows/android-ci.yml"><img src="https://github.com/KuRue/bram/actions/workflows/android-ci.yml/badge.svg" alt="Android CI" /></a>
  <a href="https://github.com/KuRue/bram/actions/workflows/android-release.yml"><img src="https://github.com/KuRue/bram/actions/workflows/android-release.yml/badge.svg" alt="Android release" /></a>
  <img src="https://img.shields.io/badge/Android-10%2B-8A5A3B?logo=android&amp;logoColor=white" alt="Android 10 and newer" />
  <img src="https://img.shields.io/badge/status-alpha-6B6259" alt="Alpha software" />
</p>

<p align="center">
  <a href="https://github.com/KuRue/bram/releases"><b>Releases</b></a> ·
  <a href="docs/BUILDING.md">Build it</a> ·
  <a href="ARCHITECTURE.md">Architecture</a> ·
  <a href="docs/PROJECT_OVERVIEW.md">Status</a> ·
  <a href="ROADMAP.md">Roadmap</a> ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>

## Your phone. Your models. Your assistant.

Bram runs local AI on Android, with durable conversations, tools, memory, and scheduled work.
Add a GGUF or a LiteRT-LM model, or connect an OpenAI-compatible server, and start chatting.
Remote models fit into the same agent loop, behind an explicit privacy policy.

It profiles your hardware, budgets memory, and tests accelerators against CPU output before
using them. Native inference runs in a separate process, so a backend crash is contained without
taking down the conversation.

| Chat that lasts | Tools that do work | Hardware you can trust |
| :--- | :--- | :--- |
| Persistent threads, Markdown, streaming, and background runs. | Permission-gated tools, remote MCP, versioned skills, and scheduled automations. | Device profiling, memory-aware plans, accelerator validation, and fallback. |

## Get Bram

**Android 10+ · ARM64 · bring your own model or server**

- **Release builds:** download the APK and `SHA256SUMS` from [Releases](https://github.com/KuRue/bram/releases) once a version is published. Release drafts are visible only to maintainers.
- **Development builds:** open a successful [Android CI run](https://github.com/KuRue/bram/actions/workflows/android-ci.yml) and download its `bram-debug-…` artifact (GitHub requires sign-in), then unzip it to get the APK.
- **Build it yourself:** see [Building Bram](docs/BUILDING.md).

On first launch, Bram's setup guide checks what the phone can run — memory, processor, NPU, and
GPU — and suggests the kind of model to add. Models are separate downloads and are not bundled:
get a `.gguf` or `.litertlm` file in your browser (for example from Hugging Face) and add it, or
connect a server such as Ollama or LM Studio. The guide is under **Settings › Setup guide**, and
models and servers live under **Models**.

On-device models need an ARMv8.2 processor (fp16 and dot-product instructions); Bram says so
before loading on an older one, and a server still works there.

Release and development builds use different signing keys, and Android only updates an app signed
with the same key, so switching between them means reinstalling, which clears app data. See
[APK releases](docs/RELEASING.md).

## Where things stand

Bram is **alpha** software. Local chat, conversations, tools, memory, skills, and automations
work. Hardware results come from one phone, a Snapdragon 8 Elite Galaxy S25 Ultra; the emulator
covers the UI and agent flows.

| Runtime / backend | Current evidence |
| :--- | :--- |
| llama.cpp CPU | Validated on device, with Arm's KleidiAI kernels. |
| Hexagon NPU | Validated at 95.8% agreement with CPU. Needs Qualcomm's SDK at build time, so release builds do not include it. |
| OpenCL (Adreno) | Validated at 96% agreement. Opt-in at build time. |
| Vulkan | Builds and runs, but fails correctness validation on the Adreno 830, so it is not used by default. |
| LiteRT-LM | Ships in the build and runs `.litertlm` packages on CPU or GPU; not yet validated across devices. |

Open work is device adaptation across more hardware, efficiency modes, and the items on the
[roadmap](ROADMAP.md). The [project overview](docs/PROJECT_OVERVIEW.md) has the full status.

## Explore the code

| Module | Role |
| :--- | :--- |
| `app` | Jetpack Compose UI, app state, built-in tools, and dependency assembly |
| `core:domain` / `core:agent` | Agent contracts, context budgets, routing, planning, and tool execution |
| `platform:android` | Device profiling, storage, memory, and encrypted credentials |
| `runtime:llamacpp` | GGUF import, JNI inference, and the isolated native process |
| `runtime:openai` / `runtime:litertlm` | Remote model adapter and LiteRT-LM integration |

[Architecture](ARCHITECTURE.md) explains the boundaries. [Security](SECURITY.md) documents privacy,
credentials, and tool trust. [Contributing](CONTRIBUTING.md) covers development expectations, and
the [docs index](docs/README.md) lists everything else.

No open-source license has been selected yet.
