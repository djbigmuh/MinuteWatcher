plugins {
    id("minutewatcher.android-library")
}

android {
    namespace = "host.serv.djbigmuh.minutewatcher.core.resources"

    // AGP's testFixtures DSL is @Incubating but is the only supported way to enable
    // test fixtures in Android library modules. Suppression is intentional and documented.
    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }
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
