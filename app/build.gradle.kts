plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.creds.vault"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = "dev.creds.vault"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Substitutes a plain Application for CredsApplication, so UI tests do not boot
        // Hilt and the lock coordinator's background work just to render a composable.
        testInstrumentationRunner = "dev.creds.vault.CredsTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // No applicationIdSuffix: the autofill service registration and the trust
            // store are keyed by package name, so debug and release must stay the same
            // package to exercise the real fill path.
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:crypto"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":feature:autofill"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    // BiometricPrompt only accepts a FragmentActivity or Fragment, so MainActivity
    // extends FragmentActivity. Declared directly rather than inherited through
    // biometric, so bumping that dependency cannot remove our superclass.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // The BOM is inherited through :core:ui for the main source set, but androidTest
    // needs its own platform declaration to version these.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Supplies the empty activity createComposeRule() launches into. It must be on the
    // androidTest configuration, not just debugImplementation: for an application module
    // the instrumentation APK is a separate package (dev.creds.vault.test), and the rule
    // resolves the host activity there rather than in the app under test.
    androidTestImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
