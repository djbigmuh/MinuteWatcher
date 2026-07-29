// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 36, compileSdk 37 are project requirements.
plugins {
    id("minutewatcher.android-test")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.macrobenchmark"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidxBenchmarkMacroJunit4)
    implementation(libs.androidxUiautomator)
    implementation(libs.androidxJunit)
}



