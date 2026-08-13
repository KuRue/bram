# Building Bram

## Pinned baseline

| Component                  | Version |
| -------------------------- | ------: |
| JDK                        |      17 |
| Android Gradle Plugin      |   9.3.1 |
| Gradle                     |   9.5.0 |
| Android compile/target SDK |      36 |
| Android minimum SDK        |      29 |
| Android SDK Build Tools    |  36.0.0 |
| Android NDK                | 28.2.13676358 |
| CMake                      |  3.30.5 |
| Kotlin                     |  2.3.21 |

CMake 3.30.5 replaces the NDK-default 3.22.1 because the Vulkan backend needs FetchContent's
`find_package` redirect behaviour, which requires 3.24 or newer.

AGP 9.3 provides built-in Kotlin for Android modules. Do not add the legacy
`org.jetbrains.kotlin.android` plugin to `app`, `platform:android`, or the runtime Android
libraries. The pure JVM `core` modules continue to use `org.jetbrains.kotlin.jvm`.

`runtime:llamacpp` pins llama.cpp commit
`132753bf4e52b1a8bda8f6ec33f1785bd80470da`. CMake fetches that immutable commit during native
configuration. It does not track an upstream branch.

## Accelerator backends

| Backend | Default | Requirement |
| --- | --- | --- |
| CPU | always on | none |
| Vulkan (Adreno) | **on** | vendored headers, fetched automatically |
| Hexagon NPU | **off** | `HEXAGON_SDK_ROOT` pointing at a Qualcomm Hexagon SDK |

Compiling a backend is independent of trusting it. Bram reports an accelerator as validated only
after it reproduces the CPU reference on device — see "Accelerator validation" below.

### Vulkan

`GGML_VULKAN` is on by default. Four things are not available in a stock NDK cross-compile and are
handled in `runtime/llamacpp/src/main/cpp/CMakeLists.txt`:

- **SPIRV-Headers** — `ggml-vulkan` calls `find_package(SPIRV-Headers CONFIG REQUIRED)`. A pinned
  copy is vendored and an explicit package-config redirect is written, because FetchContent's
  automatic redirect proved unreliable under this NDK/CMake combination. Its include directory is
  also added to the `ggml-vulkan` target, since `ggml-vulkan.cpp` includes
  `<spirv/unified1/spirv.hpp>` without linking the target.
- **Vulkan-Headers** — the NDK sysroot ships the C headers but not the C++ bindings
  (`vulkan/vulkan.hpp`). A pinned copy is vendored and `Vulkan_INCLUDE_DIR` points at it;
  `libvulkan.so` still links from the sysroot.
- **glslc** — lives in the NDK's `shader-tools/<host>/`, outside the cross-compile sysroot where
  `FindVulkan` cannot see it, so `Vulkan_GLSLC_EXECUTABLE` is set explicitly.
- **A host toolchain** — `vulkan-shaders-gen` is built for the *host* through ExternalProject and
  inherits the Ninja generator without `CMAKE_MAKE_PROGRAM`. Keep `ninja` on `PATH` (the SDK ships
  one at `$ANDROID_SDK_ROOT/cmake/3.30.5/bin`).

On **Windows**, building that host tool additionally needs a working host C++ compiler. The
auto-detected clang defaults to a MinGW link that usually is not installed; run the build from a
shell that has loaded MSVC's `vcvars64.bat`. Linux hosts, including CI and WSL, use stock gcc and
need none of this — which is why WSL is the recommended local environment.

### Hexagon NPU

`GGML_HEXAGON` is enabled only when `HEXAGON_SDK_ROOT` (environment variable or the
`bram.hexagonSdkRoot` Gradle property) points at a Hexagon SDK. The SDK is proprietary Qualcomm
software and is absent on CI, so every build without it is unaffected.

Upstream distributes the SDK inside a public toolchain image. Extract it once:

```bash
docker pull ghcr.io/snapdragon-toolchain/arm64-android:v0.7
mkdir -p ~/hexagon
docker run --rm --platform linux/amd64 -v ~/hexagon:/out \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.7 \
  bash -lc "cp -a /opt/hexagon/6.6.0.0 /out/"
```

Then build with it on:

```bash
export HEXAGON_SDK_ROOT=~/hexagon/6.6.0.0
export PATH="$ANDROID_SDK_ROOT/cmake/3.30.5/bin:$PATH"   # sub-builds need ninja
./gradlew :app:assembleDebug
```

Non-obvious requirements, all already handled in the build:

- `PREBUILT_LIB_DIR` is defined to an inert value. The SDK's `hexagon_fun.cmake` calls
  `string(FIND ${PREBUILT_LIB_DIR} ...)` at include time and fails outright when it is unset.
- `bram_llama` depends explicitly on the `htp-vNN` sub-projects. Gradle asks the native build only
  for `bram_llama`, so the DSP skels would otherwise be configured but never built.
- The skels (`libggml-htp-v73/75/79/81.so`) are `QUALCOMM DSP6` binaries, not ARM64. They are
  excluded from stripping and packaged uncompressed, because the NPU loader resolves them as real
  files on disk rather than from inside the APK.
- `ADSP_LIBRARY_PATH` is set to the app's native library directory before the JNI library loads.
- `AndroidManifest.xml` declares `libcdsprpc.so` and `libadsprpc.so` with `<uses-native-library>`.
  They are listed in `/vendor/etc/public.libraries.txt`, but since Android 12 an app targeting a
  modern SDK must also name them or `dlopen` fails with "library not found".

## WSL as the local build environment

The full native build takes roughly 12 minutes on CI, which is a poor iteration loop for native
work. A warm WSL tree rebuilds changed native sources in about 8 seconds and produces a full APK in
well under a minute, and its Linux host compiler avoids the Windows MSVC requirement above.

Install JDK 17 and the Android SDK packages inside the distro (no root needed — everything lives
under `$HOME`), keep the checkout on the WSL filesystem rather than `/mnt/c`, and point
`local.properties` at the Linux SDK path. Build there and install to the phone with the Windows
`adb`, or with WSL's own `adb` over wireless debugging.

### Debug signing across hosts

Gradle signs debug builds with whichever debug keystore the build host generated, so an APK from
CI, from Windows, and from WSL each carry a different signature. Installing one over another fails
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and needs an uninstall, which clears imported models.

Either pick one build host and stay on it, or share a keystore between them:

```bash
export BRAM_DEBUG_KEYSTORE=~/.android/debug.keystore   # or bram.debugKeystore in gradle.properties
```

Passwords default to Android's conventional `android` / `androiddebugkey` and can be overridden
with `BRAM_DEBUG_KEYSTORE_PASSWORD`, `BRAM_DEBUG_KEY_ALIAS`, and `BRAM_DEBUG_KEY_PASSWORD`.
Keystores are excluded from version control, so sharing one across machines or into CI is a
deliberate choice rather than something the repository does for you.

## Accelerator validation

Compiling a backend proves nothing about its correctness. Bram validates an accelerator by
recording a deterministic greedy decode on CPU and requiring the accelerator to reproduce it.

The comparison uses **teacher forcing**: both backends are fed the identical CPU token sequence and
asked only for the next-token prediction at each position. Free-running generation is unsuitable
because a single differing token sends the rest of the sequence somewhere unrelated, which makes a
small numerical difference indistinguishable from a broken kernel. Exact token equality is likewise
wrong as an acceptance test, since a quantized accelerator legitimately disagrees on near-ties.

Models → select a model → choose an accelerator → **Compare** (all layers) or **Bisect layers**
(binary search for the offload count where agreement breaks down).

Measured on a Samsung S25 Ultra (SM8750) with `LFM2.5-2.6B-Q4_0`:

| Backend | Offload | Agreement with CPU |
| --- | --- | --- |
| Hexagon NPU (HTP v79) | 31/31 layers | 23/24 (95.8%) |
| Vulkan (Adreno 830) | 1 layer | 18/24 (75%) |
| Vulkan (Adreno 830) | 7+ layers | collapses to all-zero logits |

Vulkan reports no error while returning garbage, so it remains unvalidated on that device.

## Android Studio

1. Install JDK 17 and an Android Studio version that supports AGP 9.3.
2. In **SDK Manager**, install Android SDK Platform 36, SDK Build Tools 36.0.0,
   NDK 28.2.13676358, and CMake 3.30.5.
3. Open the repository and allow the checked-in Gradle wrapper to sync.
4. Select an API 29+ device and run the `app` configuration.

The wrapper verifies the downloaded Gradle distribution against the SHA-256 checksum in
`gradle/wrapper/gradle-wrapper.properties`.

## Command line verification

Run the same checks used by CI:

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

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Install it on a connected phone with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## CI

`.github/workflows/android-ci.yml` runs on pull requests, pushes to `main`, and manual dispatches.
It uses a fresh Linux runner, installs the exact Android SDK packages above, runs tests and lint,
assembles the debug APK, and retains the APK and reports for 14 days.

CI verifies that the pinned native library compiles and is packaged with the CPU and Vulkan
backends. It cannot build the Hexagon backend, which needs a proprietary SDK, and it cannot run any
accelerator correctness test, which needs real hardware.

CPU is reported as validated only after a selected GGUF passes Bram's on-device tokenizer and
one-token decode self-test. Accelerators are validated separately against the CPU reference on a
physical device.
