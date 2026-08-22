plugins {
    id("minutewatcher.android-test")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.macrobenchmark"

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
