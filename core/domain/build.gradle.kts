plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The v1 import acceptance test reads the real Enpass export in place, if present.
    // It is live credential data: never copied, and the test asserts on counts only.
    systemProperty("creds.backupJson", rootProject.file("backup.json").absolutePath)
}

// Build-time tooling that is never part of the module's output. See BuildBreachFilter.kt.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[tools.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[tools.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("buildBreachFilter") {
    group = "build setup"
    description = "Rebuilds the bundled breached-password Bloom filter from a verified list"
    classpath = tools.runtimeClasspath
    mainClass.set("dev.creds.vault.core.domain.breach.BuildBreachFilterKt")
    val input = providers.gradleProperty("input")
    val sha256 = providers.gradleProperty("sha256")
    args(
        input.orElse("").get(),
        sha256.orElse("").get(),
        layout.projectDirectory
            .file("src/main/resources/dev/creds/vault/core/domain/breach/breached_passwords.bloom")
            .asFile.path,
    )
}

apply(from = rootProject.file("gradle/purity.gradle.kts"))
