plugins {
    id("minutewatcher.android-library")
}

android {
    namespace = "host.serv.djbigmuh.minutewatcher.core.domain"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.javaxInject)
    api(libs.kotlinxCoroutinesCore)

    testImplementation(libs.junit)
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
}
