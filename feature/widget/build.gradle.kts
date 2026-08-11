// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 37, compileSdk 37 are project requirements.
plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.feature.widget"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.sdk", 35)
}

dependencies {
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
    implementation(libs.androidxMaterial3)
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:android"))
    implementation(project(":core:resources"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    implementation(libs.androidxCoreKtx)
    implementation(platform(libs.androidxComposeBom))
    implementation(libs.androidxComposeRuntime)
    implementation(libs.androidxUi)
    implementation(libs.androidxMaterialIconsCore)
    implementation(libs.androidxMaterialIconsExtended)
    implementation(libs.androidxGlanceAppwidget)
    implementation(libs.androidxGlanceMaterial3)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.kotlinxCoroutinesTesting)
}
