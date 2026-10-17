plugins {
    alias(libs.plugins.android.application)
}

// A minimal engine pack: an APK that carries one native library and the metadata Bram reads to
// find and trust it. It exists to test, on real Android versions, that Bram's processes can load a
// library from another app's native library directory after checking its signature — the
// mechanism every real engine pack (Qualcomm, MediaTek, ...) depends on.
android {
    namespace = "io.github.kurue.bram.engine.probe"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.kurue.bram.engine.probe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    // Extracted to nativeLibraryDir so the host can load the file by path. Packs could instead be
    // loaded straight from the APK (uncompressed, page-aligned); extraction keeps the probe simple.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
