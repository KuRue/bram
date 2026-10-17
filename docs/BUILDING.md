# Building Bram

Bram is an Android project with both managed Kotlin code and native inference components. The checked-in Gradle wrapper and CI workflow are the reference build paths.

## Toolchain

| Component | Version |
| --- | ---: |
| JDK | 17 (21 only for the `:runtime:litertlm` unit tests) |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5.0 |
| Android compile / target SDK | 36 |
| Android minimum SDK | 29 |
| Android SDK Build Tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| CMake | 3.30.5 |
| Kotlin | 2.3.21 |

The llama.cpp runtime is pinned to an immutable upstream revision in `runtime/llamacpp/src/main/cpp/CMakeLists.txt`; the build never intentionally follows a moving upstream branch.

## Backends

Bram separates **backend availability** from **backend correctness**. A backend compiling or initializing successfully is not enough for Bram to consider it usable.

| Backend | In a standard build | Notes |
| --- | --- | --- |
| CPU | Yes | The reference. The arm64 build requires ARMv8.2 fp16 and dot-product instructions, and includes Arm's KleidiAI kernels, chosen at runtime by CPU feature. |
| Vulkan | Yes | Compiled in, but has failed validation on the Adreno 830. |
| Hexagon NPU | No | Needs Qualcomm's Hexagon SDK (below). Not in CI or release builds. |
| OpenCL | No | Needs `BRAM_OPENCL=true` and a vendor library from a device (below). |
| LiteRT-LM | Yes | A Maven dependency (`litertlm-android`); no extra setup. Runs in the app process on CPU or GPU. |

Accelerator validation compares candidate output against a deterministic CPU reference. This caught an Adreno Vulkan configuration that reported successful execution while returning incorrect predictions.

## Standard build

Open the project in Android Studio and let it sync, or use the wrapper:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/` and the instrumentation APK to
`app/build/outputs/apk/androidTest/debug/`. The full set of checks CI runs, and how to run the
device tests, are in [Contributing › Verification](../CONTRIBUTING.md#verification).

The `:runtime:litertlm` unit tests fork a JDK 21 JVM, because the LiteRT-LM AAR ships Java 21
bytecode. Register that install with `org.gradle.java.installations.paths` in
`~/.gradle/gradle.properties`; Gradle itself stays on 17.

## Native development

Linux or WSL is the recommended environment for native iteration. The Vulkan build includes host-side shader tooling, so the host needs a working C/C++ compiler in addition to the Android NDK toolchain.

CMake 3.30.5 is used because the native dependency/build setup relies on newer CMake behavior than the older NDK-default versions provide.

### Hexagon NPU

The Hexagon backend is opt-in. Set `HEXAGON_SDK_ROOT` to a locally installed compatible Qualcomm Hexagon SDK before building it. Proprietary SDK files are not committed to this repository.

```bash
export HEXAGON_SDK_ROOT=/path/to/hexagon-sdk
export PATH="$ANDROID_SDK_ROOT/cmake/3.30.5/bin:$PATH"
./gradlew :app:assembleDebug
```

The NPU path must still pass Bram's on-device correctness validation before the application treats it as validated.

### OpenCL

OpenCL is also opt-in: build with `BRAM_OPENCL=true`. Android exposes the vendor OpenCL library
without an SDK, so the linker needs a copy pulled from a device into
`runtime/llamacpp/src/main/cpp/opencl-stub/libOpenCL.so`. It is Qualcomm's device-specific binary
and is not committed, which is why CI cannot build this backend. `GGML_OPENCL` is FORCE-set in the
CMake cache, so after turning it off, delete `runtime/llamacpp/.cxx` before rebuilding.

## Local files and credentials

The repository intentionally ignores local and sensitive build material, including:

- `local.properties`
- `.env` / `.env.*`
- `*.keystore` / `*.jks`
- `secrets.properties`
- local model artifacts such as GGUF/ONNX/Safetensors files
- generated backend caches and logs

Remote endpoint credentials entered in the application are stored using Android Keystore-backed encryption; they are not build-time repository configuration.

## Emulator harness testing

A standard build packages only `arm64-v8a`, so on an x86_64 emulator Bram has no llama.cpp library:
local GGUF models cannot load, and the first-run guide says so and points to a server. That is
how the device suite runs today. `BRAM_EMULATOR_ABI=true` adds the x86_64 ABI so the local runtime
is packaged for emulator runs (it also disables the Hexagon backend):

```bash
BRAM_EMULATOR_ABI=true ./gradlew :app:assembleDebug
```

### Emulators

- **`s25u_sim`** — Android 16 (API 36), 1440x3120 like the S25 Ultra, x86_64. The default test
  device on Linux. Boot it cold for test runs:

  ```bash
  emulator -avd s25u_sim -no-window -no-audio -no-snapshot -gpu swiftshader_indirect > /tmp/emu.log 2>&1 &
  ```

- **`bram_api35`** — API 35 (`system-images;android-35;google_apis;x86_64`) with 6 GB RAM and a
  512 MB VM heap. The known-good image on Windows hosts, where the API 36.1 image aborts
  surfaceflinger in `mapper.ranchu.so` (`!rcEnc->featureInfo()->hasReadColorBufferDma`) on every
  cold boot under every GPU mode. At 4 GB the guest thrashes during first-boot dexopt; 6 GB avoids
  it.

### Scripted mock endpoint

`tools/harness-mock/mock_server.py` is a deterministic, standard-library-only OpenAI-compatible server for driving the remote-endpoint path with no model file. It serves `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/responses`, a scenario switcher, request inspection, and a liveness probe:

```bash
python3 tools/harness-mock/mock_server.py --port 8099 --scenario happy_tool_call   # py -3 on Windows
curl http://127.0.0.1:8099/__health
curl -X POST http://127.0.0.1:8099/__scenario/slow_response
curl http://127.0.0.1:8099/__log
```

`GET /__log` returns the last 50 completion requests — scenario, model, tool count, each message's role/tool-call id/content length, and whether a stream was requested — the fastest way to see exactly what the app sent. Each entry also carries a `seq` number; count requests by `seq`, not by log length, because the
log is shared by every scenario in a run. Scenarios: `text`, `stream_chat` (SSE text plus
reasoning), `happy_tool_call` (default; one `device_status` call, then a final reply once the tool
result is in the request), `history_check` (answers 400 when an assistant tool call has no matching
result), `read_file_oversized`, `parallel_calls`, `malformed_args`, `http_500`, `slow_response`,
`stall_response`, `oversized_result`, `status_failed`, `truncated_then_answer`,
`truncated_partial`, and `tap_own_ui`. Unit tests:

```bash
cd tools/harness-mock && python3 -m unittest discover -v
```

### Configuring the mock endpoint without the UI

For automated runs, inject the endpoint and routing preferences directly with the app stopped, then relaunch. Endpoint metadata lives in `shared_prefs/bram.remote_endpoints.xml` under `endpoints.v1` (a JSON array); routing lives in `shared_prefs/bram-routing-v1.xml`:

```xml
<string name="endpoints.v1">[{"id":"mock","displayName":"Mock endpoint","baseUrl":"http://127.0.0.1:8099/v1","modelName":"mock-small","apiKind":"CHAT_COMPLETIONS","contextWindowTokens":8192,"supportsToolCalling":true,"allowInsecureHttp":true,"credentialAlias":"endpoint-api-key","customHeaders":{},"bodyOptionsJson":"{}"}]</string>
```

```xml
<string name="routingMode">remote_only</string>
<string name="primaryTargetId">remote:mock</string>
```

`adb push` the files to `/data/local/tmp`, copy them in with `run-as io.github.kurue.bram.app cp ... shared_prefs/...` (creating `shared_prefs` first), and force-stop before writing. A model-free emulator routes to the only endpoint under AUTO as well; `remote_only` only removes routing ambiguity.

### Reaching the host from the emulator

- `10.0.2.2` is the host loopback alias inside the QEMU emulator. Add the endpoint as `http://10.0.2.2:8099/v1` and allow insecure HTTP.
- `adb reverse tcp:8099 tcp:8099` forwards the emulator's `127.0.0.1:8099` to the host, making the endpoint URL `http://127.0.0.1:8099/v1`. The reverse is bound to the adb server instance and to the emulator session — re-run it after any adb restart and after every emulator reboot. If a vendor adb watchdog keeps re-binding 5037, run a private server: `ANDROID_ADB_SERVER_PORT=5038 adb ...` then `adb connect 127.0.0.1:5555`.
- A stale mock server can survive a restart: two processes can hold the same port (SO_REUSEADDR)
  and the old one keeps answering. Stop the server you started by its PID before starting another,
  and check `GET /__health` before trusting a run.

The mock binds the host loopback only, so the `10.0.2.2` route needs no firewall change. Bind `0.0.0.0` deliberately (and open the port) only for physical-device testing.

### QEMU caveats

- Auto-configure's CPU-mask measurement can hang under QEMU. Force-stop the app and relaunch; the profile is created regardless.
- The last-used model auto-restores at launch. A stale profile can crash the `:inference` process on the emulator, so clear it (or use a model-free emulator) before testing the remote path.
- With a local embedder designated, tool selection embeds the query plus every offered tool each turn — minutes under QEMU. Unset the embedding model for harness runs unless the test targets selection itself.

### Instrumentation

The suite runs through `BramTestRunner`, which marks the first-run guide as finished before any
test starts so that tests beginning with no models see the chat screen:

```bash
adb shell am instrument -w io.github.kurue.bram.app.test/.BramTestRunner
```

Narrow to one class with `-e class io.github.kurue.bram.app.test.ChatScreenSmokeTest`. The full
procedure, including the cold boot and the mock server, is in
[Contributing › Verification](../CONTRIBUTING.md#2-device-tests-on-the-emulator).

- `RemoteToolLoopSmokeTest`, `NonToolEndpointSmokeTest`, `RemoteTurnSettlingTest`,
  `TruncationContinuationOnDeviceTest`, and `TurnStallOnDeviceTest` need the mock server, seed and
  restore the endpoint and routing preferences themselves, and skip when the mock is unreachable.
- `OnboardingOnDeviceTest` clears the guide flag and any endpoints for itself and restores both; it
  skips on a device that has imported models.
- `ScreenReadingOnDeviceTest` toggles secure accessibility settings through the instrumentation
  shell and restores them afterwards.
- The rest (chat shell, conversation history, memory, skills, the agent folder, alarms and
  notifications, Termux snippets, and `:inference` survival) need neither the mock nor a model.

## CI

`.github/workflows/android-ci.yml` runs every module's unit tests, lint, and the debug build on each
pull request and push to `main`, and keeps the debug APK and reports as artifacts for 14 days. It
does not run the device suite or any hardware check: CPU, NPU, and GPU validation needs the real
hardware.

Signed APKs and draft GitHub releases are prepared by `.github/workflows/android-release.yml`.
See [APK releases](RELEASING.md) for permanent signing-key setup, version tags, and publishing.

## Installing a debug build

With a compatible device connected through ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Different development machines may use different Android debug signing keys. If Android rejects an update because the installed build was signed with another debug key, either use a shared local debug keystore or uninstall the prior debug build before installing the new one.

### Enabling screen reading on a sideloaded build

The in-app "Turn on screen reading" button opens the accessibility settings, but Android 13+ blocks a *sideloaded* app from being toggled there until "restricted settings" is allowed for it: App info → the ⋮ menu → Allow restricted settings, after which Bram's toggle works normally. Installing through a store or an IDE does not hit this. For testing, the instrumentation shell path bypasses the gate entirely:

```bash
adb shell settings put secure enabled_accessibility_services io.github.kurue.bram.app/io.github.kurue.bram.app.BramAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Verify the bind with `adb shell dumpsys accessibility | grep -A2 "Bound services"`. The setting is per-app-data — a reinstall clears it, and a same-value rewrite never rebinds a stale service after an APK update (change the value, or toggle it off and on).
