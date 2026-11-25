plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "com.github.djbigmuh.minutewatcher.core.designsystem"
    buildFeatures { compose = true }
}

dependencies {
    api(platform(libs.androidxComposeBom))
    api(libs.androidxUi)
    api(libs.androidxComposeFoundation)
    api(libs.androidxMaterial3)
    api(libs.androidxMaterialIconsCore)
    implementation(libs.androidxUiToolingPreview)
}
