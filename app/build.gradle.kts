// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 36, compileSdk 37 are project requirements.
import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("minutewatcher.android-application")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.androidxBaselineprofile)
}

kotlin {
    jvmToolchain(21)
}

configure<ApplicationExtension> {
    namespace = "com.example.minutewatcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.minutewatcher"
        minSdk = 26
        targetSdk = 37
        versionCode = 240
        versionName = "2.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            @Suppress("UnstableApiUsage")
            enableSplit = false
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:android"))
    implementation(project(":core:resources"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:main"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:semantic"))

    baselineProfile(project(":baselineprofile"))

    coreLibraryDesugaring(libs.desugarJdkLibs)
    implementation(libs.skydovesColorpickerview)
    implementation(libs.androidxAppsearch)
    ksp(libs.androidxAppsearchCompiler)
    implementation(libs.androidxAppsearchLocalStorage)

    // Hilt
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)
    implementation(libs.androidxHiltWorker)
    ksp(libs.androidxHiltCompilerWorker)

    // WorkManager
    implementation(libs.androidxWorkRuntimeKtx)

    // Lifecycle
    implementation(libs.androidxLifecycleViewmodelKtx)
    implementation(libs.androidxLifecycleRuntimeCompose)

    // DataStore
    implementation(libs.androidxDatastorePreferences)
    implementation(libs.kotlinxCoroutinesGuava)

    implementation(libs.googleMaterial)
    implementation(libs.androidxMaterial3)
    implementation(libs.androidxMaterial3WindowSize)
    implementation(libs.androidxMaterialIconsCore)
    implementation(libs.androidxMaterialIconsExtended)
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.androidxLifecycleRuntimeKtx)
    implementation(libs.androidxActivityCompose)
    implementation(platform(libs.androidxComposeBom))
    implementation(libs.androidxUi)
    implementation(libs.androidxUiGraphics)
    implementation(libs.androidxUiToolingPreview)

    // LeakCanary
    debugImplementation(libs.leakcanaryAndroid)

    testImplementation(libs.junit)
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(libs.androidxWorkTesting)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidxComposeBom))
    androidTestImplementation(libs.androidxJunit)
    androidTestImplementation(libs.androidxEspressoCore)
    androidTestImplementation(libs.androidxUiTestJunit4)
    debugImplementation(platform(libs.androidxComposeBom))
    debugImplementation(libs.androidxUiTooling)
    debugImplementation(libs.androidxUiTestManifest)
}



