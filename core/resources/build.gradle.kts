// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 37, compileSdk 37 are project requirements.
plugins {
    id("minutewatcher.android-library")
}

android {
    namespace = "com.example.minutewatcher.core.resources"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
}
