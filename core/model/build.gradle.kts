// core:model is a pure Kotlin JVM module containing data classes and business logic.
plugins {
    `java-library`
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<Test>().configureEach {
    // Phase 2: Performance optimization.
    // This module has no global state or heavy framework dependencies.
    forkEvery = 0
}

dependencies {
    api(platform(libs.androidxComposeBom))
    api(libs.androidxComposeRuntime)
    implementation(libs.kotlinxCoroutinesCore)

    testImplementation(libs.junit)
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
}
