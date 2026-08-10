# Contributing to Bram

Bram is pre-alpha. Changes should strengthen one complete boundary or vertical slice instead of creating UI for behavior that does not exist.

## Ground rules

- Keep agent orchestration independent of llama.cpp, LiteRT, and remote-provider wire formats.
- Keep native inference and driver initialization in the isolated `:inference` process.
- Probe accelerator capability with correctness tests; do not infer support from a marketing device name.
- Preserve the transcript as canonical data. Summaries, memories, retrieval results, and KV caches must be reproducible projections.
- Treat model output, tool arguments, tool results, imported models, and generated skills as untrusted input.
- Do not add silent remote fallback. Routing must obey the run's privacy class and leave a user-visible record.
- Do not commit model weights, endpoint keys, signing keys, local paths, or generated backend caches.

## Bram identity changes

The bundled identity lives in `app/src/main/kotlin/io/github/kurue/bram/app/BramDefaults.kt`. Increment its version whenever behaviorally meaningful prompt text changes. Identity-specific policy must not leak into model adapters.

## Verification

Run the focused JVM tests while iterating:

```bash
./gradlew :core:agent:test
```

Before merging Android or runtime work, also run:

```bash
./gradlew --no-daemon --stacktrace \
  :core:agent:test \
  :platform:android:lintDebug \
  :runtime:openai:lintDebug \
  :runtime:llamacpp:lintDebug \
  :app:lintDebug \
  :app:assembleDebug
```

See [Building Bram](docs/BUILDING.md) for the pinned toolchain and CI behavior.

Native backend changes additionally require correctness, cancellation, process-death, low-memory, and thermal-soak testing on real devices. Performance results are not valid until correctness passes.

## Commit scope

Use a feature branch, keep commits focused, and explain any new permissions, native libraries, network behavior, persistent data, or privacy changes in the pull request.
