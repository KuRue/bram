# Building Bram

## Pinned baseline

| Component                  | Version |
| -------------------------- | ------: |
| JDK                        |      17 |
| Android Gradle Plugin      |   9.3.1 |
| Gradle                     |   9.5.0 |
| Android compile/target SDK |      37 |
| Android minimum SDK        |      29 |
| Android SDK Build Tools    |  36.0.0 |
| Kotlin                     |  2.3.21 |

AGP 9.3 provides built-in Kotlin for Android modules. Do not add the legacy
`org.jetbrains.kotlin.android` plugin to `app`, `platform:android`, or the runtime Android
libraries. The pure JVM `core` modules continue to use `org.jetbrains.kotlin.jvm`.

The NDK and CMake are intentionally not installed by the current CI job because no native target
is linked yet. When `runtime:llamacpp` adds its first `externalNativeBuild`, pin the tested NDK and
CMake versions in that module and install those exact packages in CI in the same change.

## Android Studio

1. Install JDK 17 and an Android Studio version that supports AGP 9.3.
2. In **SDK Manager**, install Android SDK Platform 37 and SDK Build Tools 36.0.0.
3. Open the repository and allow the checked-in Gradle wrapper to sync.
4. Select an API 29+ device and run the `app` configuration.

The wrapper verifies the downloaded Gradle distribution against the SHA-256 checksum in
`gradle/wrapper/gradle-wrapper.properties`.

## Command line verification

Run the same checks used by CI:

```bash
./gradlew --no-daemon --stacktrace \
  :core:agent:test \
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

This is the build gate for Milestone 0. A passing emulator or JVM-only build does not validate
native accelerator behavior; CPU, Adreno, and Hexagon support will require separate correctness
tests on physical devices.
