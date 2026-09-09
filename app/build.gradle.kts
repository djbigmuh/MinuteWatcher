import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("minutewatcher.android-application")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.androidxBaselineprofile)
    alias(libs.plugins.screenshot)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configure<ApplicationExtension> {
    namespace = "com.example.minutewatcher"

    defaultConfig {
        applicationId = "com.example.minutewatcher"
        versionCode = 320
        versionName = "3.2.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        testInstrumentationRunner = "com.example.minutewatcher.HiltTestRunner"
        experimentalProperties["android.experimental.enableScreenshotTest"] = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            manifestPlaceholders["benchmarkReceiverEnabled"] = false
        }
        debug {
            manifestPlaceholders["benchmarkReceiverEnabled"] = true
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["benchmarkReceiverEnabled"] = true
            proguardFiles("benchmark-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            // enableSplit is @Incubating in Gradle 9.x but is required for
            // proper app bundle configuration.
            @Suppress("UnstableApiUsage")
            enableSplit = false
        }
    }

    lint {
        // Lint baseline captures ONLY pre-existing issues.
        // New code MUST NOT be added to the baseline — fix issues at the source.
        // When refactoring a file, remove its entries from the baseline first.
        baseline = file("lint-baseline.xml")
        disable += "AndroidGradlePluginVersion"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    tasks.withType<Test>().configureEach {
        // Phase 2: Structural acceleration.
        // Robolectric sandboxes are reused within the same JVM.
        forkEvery = 10
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
    implementation(libs.androidxGlanceAppwidget)

    baselineProfile(project(":baselineprofile"))

    coreLibraryDesugaring(libs.desugarJdkLibs)
    implementation(libs.skydovesColorpickerview)

    // Hilt
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)
    implementation(libs.androidxHiltWorker)
    ksp(libs.androidxHiltCompilerWorker)
    implementation(libs.androidxHiltNavigationCompose)

    // WorkManager
    implementation(libs.androidxWorkRuntimeKtx)

    // Lifecycle
    implementation(libs.androidxLifecycleViewmodelKtx)
    implementation(libs.androidxLifecycleRuntimeCompose)
    implementation(libs.androidxLifecycleProcess)

    // Hilt Testing
    androidTestImplementation(libs.daggerHiltAndroidTesting)
    kspAndroidTest(libs.daggerHiltCompiler)
    testImplementation(libs.daggerHiltAndroidTesting)
    kspTest(libs.daggerHiltCompiler)

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
    testImplementation(testFixtures(project(":core:common")))
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

    screenshotTestImplementation(libs.screenshot.validation.api)
}
