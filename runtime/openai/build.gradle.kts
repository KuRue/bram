plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.kurue.bram.runtime.openai"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
    // A cancelled turn has to close its socket, or the run waits out the read timeout: a thread
    // blocked in a read cannot be interrupted, and HttpURLConnection offers no way to reach it.
    // OkHttp documents exactly that guarantee, so the streaming path uses it.
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    // android.jar stubs org.json and throws from every method on the unit-test classpath.
    testImplementation(libs.org.json)
}
