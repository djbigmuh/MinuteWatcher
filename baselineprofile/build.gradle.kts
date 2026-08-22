plugins {
    id("minutewatcher.android-test")
    alias(libs.plugins.androidxBaselineprofile)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.baselineprofile"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidxBenchmarkMacroJunit4)
    implementation(libs.androidxUiautomator)
    implementation(libs.androidxJunit)
}
