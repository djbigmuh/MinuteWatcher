plugins {
    id("minutewatcher.android-test")
    alias(libs.plugins.androidxBaselineprofile)
}

android {
    namespace = "com.github.djbigmuh.minutewatcher.baselineprofile"

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidxBenchmarkMacroJunit4)
    implementation(libs.androidxUiautomator)
    implementation(libs.androidxJunit)
}
