# Contributing to Bram

Bram is alpha software. Changes should strengthen one complete boundary or vertical slice instead
of creating UI for behavior that does not exist.

By contributing, you agree that your contributions are licensed under the project's
[Apache License 2.0](LICENSE).

## Ground rules

- Keep agent orchestration independent of llama.cpp, LiteRT-LM, and remote-provider wire formats.
- Keep native inference and driver initialization in the isolated `:inference` process.
- Probe accelerator capability with correctness tests; do not infer support from a device name.
- Preserve the transcript as canonical data. Summaries, memories, retrieval results, and KV caches
  must be reproducible projections.
- Treat model output, tool arguments, tool results, imported models, and generated skills as
  untrusted input.
- Do not add silent remote fallback. Routing must obey the run's privacy class and leave a
  user-visible record.
- Do not commit model weights, endpoint keys, signing keys, local paths, or generated backend
  caches.
- Put new pure logic in `core:domain` with unit tests, rather than in `MainViewModel.kt` or
  `BramApp.kt`, which are already large.

## Bram identity changes

The bundled identity lives in `app/src/main/kotlin/io/github/kurue/bram/app/BramDefaults.kt`.
Increment its version whenever behaviorally meaningful prompt text changes. Identity-specific
policy must not leak into model adapters.

## Verification

The toolchain is in [Building Bram](docs/BUILDING.md). A change is ready to merge when these pass.

### 1. Unit tests, lint, and build

This is exactly what GitHub Actions runs (`.github/workflows/android-ci.yml`):

```bash
./gradlew --no-daemon --stacktrace \
  :core:domain:test \
  :core:agent:test \
  :runtime:openai:testDebugUnitTest \
  :runtime:llamacpp:testDebugUnitTest \
  :runtime:litertlm:testDebugUnitTest \
  :app:testDebugUnitTest \
  :platform:android:lintDebug \
  :runtime:openai:lintDebug \
  :runtime:llamacpp:lintDebug \
  :runtime:litertlm:lintDebug \
  :app:lintDebug \
  :app:assembleDebug
```

`:runtime:litertlm:testDebugUnitTest` needs a JDK 21 registered with Gradle. While iterating, run
only the module you are changing, for example `./gradlew :core:domain:test`.

### 2. Device tests on the emulator

UI, chat flow, Stop, persistence, and harness behavior are tested on the `s25u_sim` emulator
(Android 16, S25 Ultra screen size). Run the class you changed first, then the full suite on a
**cold** emulator — booted with `-no-snapshot`:

```bash
emulator -avd s25u_sim -no-window -no-audio -no-snapshot -gpu swiftshader_indirect > /tmp/emu.log 2>&1 &
adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done

python3 tools/harness-mock/mock_server.py --port 8099 > /tmp/mock.log 2>&1 &
adb reverse tcp:8099 tcp:8099

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# one class
adb shell am instrument -w -e class io.github.kurue.bram.app.test.OnboardingOnDeviceTest \
  io.github.kurue.bram.app.test/.BramTestRunner
# the whole suite
adb shell am instrument -w io.github.kurue.bram.app.test/.BramTestRunner
```

Re-run `adb reverse` after every emulator boot. Tests that need the mock server skip themselves
when it is unreachable, so check the skip count as well as the failures. Stop the mock server and
the emulator when you are done.

Some instrumentation state is process-global and shared by the whole run, such as
`EndpointHealth`'s down-marks and the onboarding flag that `BramTestRunner` sets. When a test is
red in the full suite and green on its own, suspect state left behind by an earlier class first.

### 3. Real hardware

Native backend changes also need correctness, cancellation, process-death, low-memory, and
thermal-soak testing on a real device. Performance results do not count until correctness passes.
Hexagon NPU, Adreno OpenCL/Vulkan, real-model speed, thermals, and memory cannot be checked on the
emulator.

## Commit scope

Use a feature branch, keep commits focused, and explain any new permissions, native libraries,
network behavior, persistent data, or privacy changes in the pull request. Record what was
verified, and on which device.
