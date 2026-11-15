plugins {
    id("minutewatcher.android-library")
}

android {
    namespace = "host.serv.djbigmuh.minutewatcher.core.resources"
}

dependencies {
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(testFixtures(project(":core:resources")))
}
