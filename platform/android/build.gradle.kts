plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.kurue.bram.platform.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime)
}
