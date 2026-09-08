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

## CI

`.github/workflows/android-ci.yml` runs the clean-room build and verification path and publishes build artifacts for inspection. Hardware-specific correctness checks are deliberately not treated as CI substitutes: CPU/NPU/GPU validation requires real target hardware.

## Installing a debug build

With a compatible device connected through ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Different development machines may use different Android debug signing keys. If Android rejects an update because the installed build was signed with another debug key, either use a shared local debug keystore or uninstall the prior debug build before installing the new one.
