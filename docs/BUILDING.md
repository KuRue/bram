# Building Bram

Bram is an Android project with both managed Kotlin code and native inference components. The checked-in Gradle wrapper and CI workflow are the reference build paths.

## Toolchain

| Component | Version |
| --- | ---: |
| JDK | 17 |
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

- **CPU** — baseline/reference backend.
- **Vulkan** — built for supported Android targets; correctness must be validated on-device before use.
- **Hexagon NPU** — optional; requires a Qualcomm Hexagon SDK and real compatible hardware for validation.
- **OpenCL / LiteRT-LM** — experimental paths that may require additional local setup depending on the target device and milestone.

Accelerator validation compares candidate output against a deterministic CPU reference. This caught an Adreno Vulkan configuration that reported successful execution while returning incorrect predictions.

## Standard build

Open the project in Android Studio and let it sync, or run the same core checks used by CI:

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

The debug APK is written under:

```text
app/build/outputs/apk/debug/
```

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

OpenCL is also opt-in. Some Android devices expose a vendor OpenCL implementation without providing a normal desktop-style SDK. Bram's native build accommodates that development model, but device-specific link stubs and other local artifacts are intentionally excluded from version control.

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

`BRAM_EMULATOR_ABI=true` adds the x86_64 emulator ABI to the APK so the local runtime stays packaged for smoke and instrumentation runs:

```bash
BRAM_EMULATOR_ABI=true ./gradlew :app:assembleDebug
```

### Known-good AVD

API 35 (`system-images;android-35;google_apis;x86_64`) with 6 GB RAM and a 512 MB VM heap, booted headless (`-no-window -no-snapshot -no-boot-anim`). The API 36.1 image is not usable on some Windows hosts: surfaceflinger aborts in `mapper.ranchu.so` (`Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`, from RegionSamplingThread) during every cold boot, under every GPU mode and even with `GLDMA` disabled, and system_server then restarts every ~15 s. At 4 GB the guest also thrashes during first-boot dexopt; 6 GB avoids it.

### Scripted mock endpoint

`tools/harness-mock/mock_server.py` is a deterministic, standard-library-only OpenAI-compatible server for driving the remote-endpoint path with no model file. It serves `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/responses`, a scenario switcher, request inspection, and a liveness probe:

```bash
py -3 tools/harness-mock/mock_server.py --port 8099 --scenario happy_tool_call
curl http://127.0.0.1:8099/__health
curl -X POST http://127.0.0.1:8099/__scenario/slow_response
curl http://127.0.0.1:8099/__log
```

`GET /__log` returns the last 50 completion requests — scenario, model, tool count, each message's role/tool-call id/content length, and whether a stream was requested — the fastest way to see exactly what the app sent. Scenarios: `text`, `stream_chat` (SSE text plus reasoning), `happy_tool_call` (default; one `device_status` call, then a final reply once the tool result is in the request), `history_check` (answers 400 when an assistant tool call has no matching result), `read_file_oversized`, `parallel_calls`, `malformed_args`, `http_500`, `slow_response`, `oversized_result`, `status_failed`. Unit tests:

```bash
cd tools/harness-mock && py -3 -m unittest discover -v
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
- On Windows a stale mock server can survive a naive restart: two processes can hold the same port (SO_REUSEADDR) and the old one keeps answering. Kill every `python` process before starting a new server, and check `GET /__health` before trusting a run.

The mock binds the host loopback only, so the `10.0.2.2` route needs no firewall change. Bind `0.0.0.0` deliberately (and open the port) only for physical-device testing.

### QEMU caveats

- Auto-configure's CPU-mask measurement can hang under QEMU. Force-stop the app and relaunch; the profile is created regardless.
- The last-used model auto-restores at launch. A stale profile can crash the `:inference` process on the emulator, so clear it (or use a model-free emulator) before testing the remote path.
- With a local embedder designated, tool selection embeds the query plus every offered tool each turn — minutes under QEMU. Unset the embedding model for harness runs unless the test targets selection itself.

### Instrumentation

```bash
adb shell am instrument -w io.github.kurue.bram.app.test/androidx.test.runner.AndroidJUnitRunner
```

Narrow to one class with `-e class io.github.kurue.bram.app.ChatScreenSmokeTest`. The harness suite (`RemoteToolLoopSmokeTest`, `NonToolEndpointSmokeTest`, `ConversationHistoryOnDeviceTest`, `ProposeSkillToolOnDeviceTest`, `SkillDraftReviewOnDeviceTest`, `AgentFolderOnDeviceTest`, `ScreenReadingOnDeviceTest`, `SkillStoreMigrationOnDeviceTest`) assumes the mock server is reachable and skips itself when it is not. `ScreenReadingOnDeviceTest` toggles secure accessibility settings through the instrumentation shell and restores them afterwards.

## CI

`.github/workflows/android-ci.yml` runs the clean-room build and verification path and publishes build artifacts for inspection. Hardware-specific correctness checks are deliberately not treated as CI substitutes: CPU/NPU/GPU validation requires real target hardware.

## Installing a debug build

With a compatible device connected through ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Different development machines may use different Android debug signing keys. If Android rejects an update because the installed build was signed with another debug key, either use a shared local debug keystore or uninstall the prior debug build before installing the new one.
