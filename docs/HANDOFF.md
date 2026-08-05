# Session handoff — Milestone 1 local CPU alpha

Last updated: 2026-08-04

## Current source of truth

| Item | Value |
|---|---|
| Repository | Private `KuRue/bram` |
| Base branch | `main` at Milestone 0 squash commit [`378879a`](https://github.com/KuRue/bram/commit/378879ac3db554600b143a305d06d84f1dfb74d1) |
| Working branch | `milestone-1-local-cpu` |
| Pull request | Draft [#2 — Milestone 1: Add local GGUF CPU inference alpha](https://github.com/KuRue/bram/pull/2) |
| Last code-changing remote commit | [`8596bc8`](https://github.com/KuRue/bram/commit/8596bc8d189c856b85fd1d5b7900e442fc7702b7) |
| Verified CI | [Android CI run #5](https://github.com/KuRue/bram/actions/runs/30963006531) passed tests, lint, native build, and APK assembly |
| Target phone | Samsung `SM-S938U1`, SoC `SM8750`, 10.9 GB app-visible RAM |
| Reference model | `LFM2.5-2.6B-Q4_0.gguf` |

PR #2 is intentionally still a draft. Do not merge it until the physical-device test below passes
or the user explicitly authorizes merging with a known limitation.

## What is implemented

- Chat and Models are the primary workflow; remote endpoints are optional under Settings.
- Android document-picker GGUF import with bounded metadata parsing, seekability checks, retained
  read permission, full SHA-256 hashing, and a persistent local-model catalog.
- A pinned llama.cpp revision (`474c92e722ce77aee2060cd08629b9afb008d81b`) compiled for ARM64
  CPU through NDK/CMake.
- Native loading and generation stay in the non-exported `:inference` process behind AIDL.
- The runtime uses the GGUF tokenizer and llama.cpp common/Jinja chat-template engine, including
  LFM2.5 templates.
- Load, exact token count, streamed generation, cancellation, unload, Binder-death recovery, and
  prompt/decode/PSS metrics.
- CPU is reported as validated only after the selected model passes a tokenizer and one-token
  decode self-test.
- A conservative 8,192-token context is selected initially when supported; larger trained
  contexts remain manually selectable.

The last CI failure was a nullable `Unit` inference on cancellation. It was fixed in `8596bc8`, and
the complete clean build then passed. There is no known compile or link failure at handoff.

## Prior phone result

The Milestone 0 APK installed and launched successfully on the target phone. It reported:

- 2.9 GB available of 10.9 GB app-visible RAM at that moment.
- No active thermal status.
- Java-side CPU detection only.
- Vulkan validation pending.
- Snapdragon and LiteRT unavailable because the old APK had no compatible native runtime.

That result was for the control-plane APK, not the Milestone 1 native APK. The new APK must not be
treated as device-validated based on the earlier smoke test.

## Immediate next action: S25 Ultra acceptance test

1. Download the debug APK artifact from CI run #5 and install it over the existing debug build.
2. Launch Bram without configuring a remote endpoint. Confirm Chat, Models, and Settings are the
   primary navigation and that endpoint setup is not the main empty state.
3. In Models, import `LFM2.5-2.6B-Q4_0.gguf` from local, seekable device storage. Allow SHA-256
   verification to finish.
4. Confirm the displayed architecture, quantization, file size, trained context, chat-template
   presence, and SHA prefix are plausible. Leave the first run at the recommended 8,192 tokens.
5. Restart Bram before loading and confirm the imported model registration remains present.
6. Load the model. Confirm the native self-test passes and CPU changes from detected/pending to
   validated only after the successful load.
7. Send a short prompt. Confirm output streams incrementally and prompt tokens, output tokens,
   prompt speed, decode speed, and inference-process PSS are shown.
8. Start a longer response and press Stop. Record whether cancellation is prompt and whether Bram
   can generate again without reinstallation.
9. Unload the model, then load it again. Confirm failures are recoverable and the UI remains alive.
10. While the model is loaded or generating, kill only the inference process. For the debuggable
    APK, obtain its PID with:

    ```bash
    adb shell pidof io.github.kurue.bram.app:inference
    ```

    Then substitute that PID here:

    ```bash
    adb shell run-as io.github.kurue.bram.app kill -9 <PID>
    ```

    Bram should keep the UI visible, explain that inference stopped, and offer a safe reload/retry.

If anything crashes, capture logs before relaunching:

```bash
adb logcat -d > bram-logcat.txt
```

Record the exact action, displayed error, selected context, load time, prompt/decode speed,
inference PSS, cancellation latency, and whether the phone became hot or reported thermal status.

## Acceptance decision

Milestone 1 passes only if the reference GGUF can import, persist, load, generate, cancel, unload,
reload, and survive an inference-process kill without taking down the UI. CPU must not be labeled
validated before the native model self-test succeeds.

If the test passes, the next repository action is to mark PR #2 ready and merge it only after
explicit user authorization. The next engineering milestone is accelerator validation: compare
Adreno and Hexagon candidates with the CPU output as the correctness reference.

If the test fails, stay on `milestone-1-local-cpu`, preserve the failing plan and logs, and fix the
smallest failing boundary before adding acceleration or durable memory.

## Known limitations and guardrails

- Vulkan, OpenCL/Adreno, Hexagon, and LiteRT acceleration are not implemented.
- Conversations, memories, skills, and automations are not durable implementations yet.
- Imported model registration persists, but conversation recovery after full app death is deferred.
- The selected document provider must expose a seekable descriptor. App-managed copying and model
  downloading are deferred.
- SHA-256 is an integrity fingerprint for the imported bytes, not publisher provenance.
- Remote Chat Completions remains optional and non-streaming.
- Do not expand Bram's persona, tool permissions, routing, or agent features while diagnosing the
  CPU acceptance test.

## Code landmarks

| Area | Path |
|---|---|
| App state and local/remote selection | `app/src/main/kotlin/io/github/kurue/bram/app/MainViewModel.kt` |
| Compose navigation and model UI | `app/src/main/kotlin/io/github/kurue/bram/app/BramApp.kt` |
| GGUF metadata parser | `runtime/llamacpp/src/main/kotlin/io/github/kurue/bram/runtime/llamacpp/GgufMetadataReader.kt` |
| Persistent model catalog/import | `runtime/llamacpp/src/main/kotlin/io/github/kurue/bram/runtime/llamacpp/LocalModelStore.kt` |
| Runtime adapter | `runtime/llamacpp/src/main/kotlin/io/github/kurue/bram/runtime/llamacpp/LlamaCppRuntime.kt` |
| AIDL client and crash recovery | `runtime/llamacpp/src/main/kotlin/io/github/kurue/bram/runtime/llamacpp/inference/LlamaCppServiceClient.kt` |
| Isolated service and self-test gate | `runtime/llamacpp/src/main/kotlin/io/github/kurue/bram/runtime/llamacpp/inference/InferenceProcessService.kt` |
| JNI generation implementation | `runtime/llamacpp/src/main/cpp/bram_llama_jni.cpp` |
| Pinned native build | `runtime/llamacpp/src/main/cpp/CMakeLists.txt` |
| CI/toolchain | `.github/workflows/android-ci.yml`, `docs/BUILDING.md` |

Start a new session by giving it this file, PR #2, and the physical-device results. The most useful
first response from that session is a pass/fail table against the acceptance steps, followed by a
minimal fix plan for any failure.
