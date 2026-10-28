plugins {
    id("minutewatcher.android-test")
}

android {
    namespace = "host.serv.djbigmuh.minutewatcher.macrobenchmark"

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidxBenchmarkMacroJunit4)
    implementation(libs.androidxUiautomator)
    implementation(libs.androidxJunit)
}
