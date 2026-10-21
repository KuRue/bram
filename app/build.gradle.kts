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
        versionCode = 3
        versionName = "0.3.0-alpha01"
        testInstrumentationRunner = "io.github.kurue.bram.app.test.BramTestRunner"
    }

    // Opt-in: `-Pbram.applicationIdSuffix=.dev` gives a debug build its own package, so it installs
    // beside a release Bram on a real phone without replacing it or touching its data.
    providers.gradleProperty("bram.applicationIdSuffix").orNull?.takeIf { it.isNotBlank() }?.let { suffix ->
        buildTypes { getByName("debug") { applicationIdSuffix = suffix } }
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

    // Release signing is opt-in locally and mandatory in the release workflow. Never fall back
    // to a debug key: Android updates must retain this signing identity for the app's lifetime.
    val releaseKeystore = providers.environmentVariable("BRAM_RELEASE_KEYSTORE").orNull
    if (!releaseKeystore.isNullOrBlank()) {
        require(file(releaseKeystore).isFile) { "BRAM_RELEASE_KEYSTORE must name an existing keystore" }
        fun signingSecret(name: String): String =
            providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
                ?: error("$name is required when BRAM_RELEASE_KEYSTORE is set")
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingSecret("BRAM_RELEASE_STORE_PASSWORD")
                keyAlias = signingSecret("BRAM_RELEASE_KEY_ALIAS")
                keyPassword = signingSecret("BRAM_RELEASE_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
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
            // The OpenCL library is the platform's, loaded from /vendor at runtime through the
            // uses-native-library declaration. Shipping the copy the linker was given would put a
            // second one in the APK, and Android loads that in preference — it then fails on
            // libcutils.so, which an app cannot see, and takes the whole native library down with
            // it.
            excludes += "**/libOpenCL.so"
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
    implementation(project(":runtime:litertlm"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The android.jar on the unit-test classpath stubs org.json and throws from every method, so
    // the real implementation goes in behind it.
    testImplementation(libs.org.json)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // Backdrop blur. Compose has no built-in way to sample what is behind a composable, and the
    // GraphicsLayer route needs lifecycle handling this library already gets right.
    implementation(libs.haze)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
