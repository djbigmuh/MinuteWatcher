plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.kotlinKsp)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.core.common"

    defaultConfig {
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:resources"))
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)
    api(libs.kotlinxCoroutinesCore)
    implementation(libs.androidxLifecycleViewmodelKtx)
    implementation(libs.javaxInject)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.mockingCore)
    testImplementation(libs.kotlinxCoroutinesTesting)

    testFixturesApi(libs.mockingKotlin)
}
