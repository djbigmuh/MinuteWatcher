plugins {
    id("minutewatcher.android-test")
}

android {
    namespace = "com.github.djbigmuh.minutewatcher.macrobenchmark"

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidxBenchmarkMacroJunit4)
    implementation(libs.androidxUiautomator)
    implementation(libs.androidxJunit)
}
