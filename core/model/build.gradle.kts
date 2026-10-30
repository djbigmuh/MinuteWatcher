// core:model is a pure Kotlin JVM module containing data classes and business logic.
plugins {
    id("minutewatcher.pure-jvm-library")
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
