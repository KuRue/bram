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
| CMake                      |  3.22.1 |
| Kotlin                     |  2.3.21 |

AGP 9.3 provides built-in Kotlin for Android modules. Do not add the legacy
`org.jetbrains.kotlin.android` plugin to `app`, `platform:android`, or the runtime Android
libraries. The pure JVM `core` modules continue to use `org.jetbrains.kotlin.jvm`.

`runtime:llamacpp` pins llama.cpp commit
`474c92e722ce77aee2060cd08629b9afb008d81b`. CMake fetches that immutable commit during native
configuration and builds only Bram's ARM64 CPU baseline. It does not track an upstream branch.

## Android Studio

1. Install JDK 17 and an Android Studio version that supports AGP 9.3.
2. In **SDK Manager**, install Android SDK Platform 36, SDK Build Tools 36.0.0,
   NDK 28.2.13676358, and CMake 3.22.1.
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

CI verifies that the pinned native CPU library compiles and is packaged. CPU is only reported as
validated after a selected GGUF passes Bram's on-device tokenizer and one-token decode self-test.
Adreno and Hexagon still require later physical-device correctness tests.
