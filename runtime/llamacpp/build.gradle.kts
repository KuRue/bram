plugins {
    alias(libs.plugins.android.library)
}

val bramIncludeEmulatorAbi: Boolean =
    (providers.environmentVariable("BRAM_EMULATOR_ABI").orNull
        ?: providers.gradleProperty("bram.emulatorAbi").orNull)
        ?.toBooleanStrictOrNull() ?: false

// The Hexagon NPU backend needs Qualcomm's proprietary Hexagon SDK, which cannot be fetched
// automatically and is absent on CI. Enable it only when a developer points HEXAGON_SDK_ROOT at a
// local install, so every other build is unaffected.
val hexagonSdkRoot: String? =
    providers.environmentVariable("HEXAGON_SDK_ROOT").orNull
        ?: providers.gradleProperty("bram.hexagonSdkRoot").orNull
val hexagonEnabled: Boolean = !hexagonSdkRoot.isNullOrBlank() && !bramIncludeEmulatorAbi

// A phone build that must carry the NPU backend can opt in (`-Pbram.requireHexagon=true`) to a
// loud failure at configuration time, so the build does not silently produce a Hexagon-less
// libbram_llama.so when the SDK is unset. Evaluated eagerly (not inside the cmake block, which
// AGP defers) so even `./gradlew help` catches it. This does not detect a poisoned CMake cache
// (SDK set but GGML_HEXAGON cached OFF); the message names that fix, and the packaged .so should
// still be verified with `strings | grep ggml-hex` after the build.
val requireHexagon = (findProperty("bram.requireHexagon")?.toString() ?: "false").toBoolean()
if (requireHexagon) {
    require(hexagonEnabled) {
        val why = buildList {
            if (hexagonSdkRoot.isNullOrBlank()) add("HEXAGON_SDK_ROOT / bram.hexagonSdkRoot is unset")
            if (bramIncludeEmulatorAbi) add("the emulator ABI is included")
        }.joinToString(", ")
        "bram.requireHexagon is set but the Hexagon backend will not compile ($why). " +
            "If the SDK is set and Hexagon is still absent, the CMake cache has likely poisoned " +
            "GGML_HEXAGON=OFF — delete runtime/llamacpp/.cxx and rebuild, then verify the packaged " +
            "libbram_llama.so with `strings | grep ggml-hex`."
    }
}

// Where AGP stages the CMake build tree. The default (module/.cxx) nests deeply enough that the
// llama.cpp Vulkan shader generator's try-compiles exceed MSVC's 250-character object path limit on
// machines with a long project root. A short absolute path (e.g. C:\Users\<you>\bramcxx) sidesteps
// that; unset means the AGP default.
val bramNativeStaging: String? =
    providers.environmentVariable("BRAM_NATIVE_STAGING").orNull
        ?: providers.gradleProperty("bram.nativeStaging").orNull

android {
    namespace = "io.github.kurue.bram.runtime.llamacpp"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29

        ndk {
            abiFilters += "arm64-v8a"
            // The stock Android emulator images are x86_64, so a phone-only ABI cannot load the
            // native runtime there at all. Opt in when testing on an emulator; leaving it off keeps
            // CI and phone builds from paying for a second full llama.cpp compile.
            if (bramIncludeEmulatorAbi) abiFilters += "x86_64"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                // The Hexagon NPU backend needs Qualcomm's proprietary Hexagon SDK, which cannot be
                // fetched automatically and is absent on CI. Enable it only when a developer points
                // HEXAGON_SDK_ROOT at a local install, so every other build is unaffected.
                if (hexagonEnabled) {
                    arguments += listOf(
                        "-DGGML_HEXAGON=ON",
                        "-DHEXAGON_SDK_ROOT=$hexagonSdkRoot",
                    )
                }
                // OpenCL for Adreno. Opt-in like Hexagon, though for a different reason: it needs
                // no proprietary SDK, but it fetches two Khronos repositories and is worth nothing
                // on a device whose driver does not expose OpenCL. Off unless asked for.
                val openCl = providers.environmentVariable("BRAM_OPENCL").orNull
                    ?: providers.gradleProperty("bram.opencl").orNull
                if (openCl.toBoolean() && !bramIncludeEmulatorAbi) {
                    arguments += listOf("-DBRAM_OPENCL=ON")
                }
            }
        }

        buildConfigField(
            "String",
            "LLAMA_CPP_COMMIT",
            "\"474c92e722ce77aee2060cd08629b9afb008d81b\"",
        )
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // 3.24+ is required so FetchContent auto-generates the find_package redirect for the
            // vendored SPIRV-Headers that ggml-vulkan resolves with find_package(... CONFIG).
            version = "3.30.5"
            if (bramNativeStaging != null) {
                buildStagingDirectory = file(bramNativeStaging)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // android.jar stubs org.json and throws from every method on the unit-test classpath.
    testImplementation(libs.org.json)
}
