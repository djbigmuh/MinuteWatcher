import com.example.minutewatcher.buildlogic.SdkVersions

plugins {
    id("minutewatcher.android-test")
}

kotlin {
    jvmToolchain(SdkVersions.JAVA_TOOLCHAIN_VERSION)
}

android {
    namespace = "com.example.minutewatcher.macrobenchmark"

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidxBenchmarkMacroJunit4)
    implementation(libs.androidxUiautomator)
    implementation(libs.androidxJunit)
}
