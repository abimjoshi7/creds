plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.creds.vault.core.crypto"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        // argon2kt's native library ships for Android ABIs only, so the Argon2id
        // known-answer vectors cannot run in a host JVM test. They run on a device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    // Argon2id via JNI — no NDK build step required.
    implementation(libs.argon2kt)
    // Android implementation of the PasswordStrengthEstimator interface in :core:domain.
    implementation(libs.zxcvbn)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The Argon2id known-answer vectors need argon2kt's native library, which ships for
    // Android ABIs only. They run on a device and therefore on JUnit4, not JUnit5.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
