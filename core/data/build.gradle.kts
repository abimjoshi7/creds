plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.creds.vault.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        // The schema test exercises SQLCipher's native library and SQLite's FTS5 module,
        // neither of which exists in a host JVM. It runs on a device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MigrationTestHelper reads the exported schemas as test assets.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
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

// Room schemas are committed so migrations are reviewable in a diff.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SQLCipher supplies the SupportSQLiteOpenHelper.Factory Room is handed.
    implementation(libs.sqlcipher.android)

    // Opt-in HIBP range lookup only. Never used unless the user enables it.
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // argon2kt is an implementation dependency inside :core:crypto, so it does not reach
    // this module transitively. The key-store round-trip test needs the real hasher —
    // substituting a fake one here would skip the exact combination that failed in
    // production: real Argon2id output sealed, persisted, read back, and reopened.
    androidTestImplementation(libs.argon2kt)
    // Declared explicitly rather than relying on androidTestImplementation extending
    // implementation: the schema test calls runBlocking directly.
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.room.testing)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
