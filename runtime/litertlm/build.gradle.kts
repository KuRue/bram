plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.kurue.bram.runtime.litertlm"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
    // LiteRT-LM: Google's on-device LLM runtime (the successor to TFLite LLM Inference). The AAR
    // ships native libraries for arm64-v8a, so a x86_64 emulator build still compiles but a
    // package cannot initialize there; the failure surfaces as a load error, not a build error.
    implementation(libs.litertlm.android)
    testImplementation(libs.junit)
    // android.jar stubs org.json and throws from every method on the unit-test classpath.
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // The litertlm AAR ships Java 21 bytecode (class file 65.0), so the unit-test JVM has to be
    // newer than 17. The launcher resolves against the installed JDKs; this build runs its
    // Android compilation on JDK 17 and only the forked test JVM is newer.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
