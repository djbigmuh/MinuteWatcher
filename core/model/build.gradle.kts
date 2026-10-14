// core:model is a pure Kotlin JVM module containing data classes and business logic.
plugins {
    `java-library`
    kotlin("jvm")
    id("minutewatcher.static-analysis")
    id("minutewatcher.test-infrastructure")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
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
