plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.kurue.bram.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kurue.bram.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-alpha01"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Gradle otherwise signs debug builds with whichever debug keystore the build host generated,
    // so an APK from CI, from Windows, and from WSL each carry a different signature and cannot be
    // installed over one another without an uninstall that clears imported models. Point
    // BRAM_DEBUG_KEYSTORE at a keystore shared between hosts to make debug installs interchangeable.
    // Keystores stay out of version control by policy, see .gitignore.
    val debugKeystore = providers.environmentVariable("BRAM_DEBUG_KEYSTORE").orNull
        ?: providers.gradleProperty("bram.debugKeystore").orNull
    if (!debugKeystore.isNullOrBlank() && file(debugKeystore).exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(debugKeystore)
                storePassword = providers.environmentVariable("BRAM_DEBUG_KEYSTORE_PASSWORD")
                    .orNull ?: "android"
                keyAlias = providers.environmentVariable("BRAM_DEBUG_KEY_ALIAS")
                    .orNull ?: "androiddebugkey"
                keyPassword = providers.environmentVariable("BRAM_DEBUG_KEY_PASSWORD")
                    .orNull ?: "android"
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // The Hexagon HTP skels are QUALCOMM DSP6 binaries, not ARM64, so the ARM strip tool
            // cannot process them. They also have to exist as real files on disk because the NPU
            // loader resolves them through ADSP_LIBRARY_PATH rather than the APK.
            keepDebugSymbols += "**/libggml-htp-*.so"
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:agent"))
    implementation(project(":platform:android"))
    implementation(project(":runtime:openai"))
    implementation(project(":runtime:llamacpp"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
