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
    testImplementation(libs.junit)
    // android.jar stubs org.json and throws from every method on the unit-test classpath.
    testImplementation(libs.org.json)
}
