plugins {
    alias(libs.plugins.android.library)
}

val bramIncludeEmulatorAbi: Boolean =
    (providers.environmentVariable("BRAM_EMULATOR_ABI").orNull
        ?: providers.gradleProperty("bram.emulatorAbi").orNull)
        ?.toBooleanStrictOrNull() ?: false

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
                val hexagonSdkRoot = providers.environmentVariable("HEXAGON_SDK_ROOT").orNull
                    ?: providers.gradleProperty("bram.hexagonSdkRoot").orNull
                if (!hexagonSdkRoot.isNullOrBlank() && !bramIncludeEmulatorAbi) {
                    arguments += listOf(
                        "-DGGML_HEXAGON=ON",
                        "-DHEXAGON_SDK_ROOT=$hexagonSdkRoot",
                    )
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
}
