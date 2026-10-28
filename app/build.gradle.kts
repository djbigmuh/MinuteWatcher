import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("minutewatcher.android-application")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.androidxBaselineprofile)
}

configure<ApplicationExtension> {
    namespace = "host.serv.djbigmuh.minutewatcher"

    defaultConfig {
        applicationId = "host.serv.djbigmuh.minutewatcher"
        versionCode = 413
        versionName = "4.3.0"
        testInstrumentationRunner = "host.serv.djbigmuh.minutewatcher.HiltTestRunner"
    }

    val keystoreFile = rootProject.file("release.keystore")
    val envStorePassword = System.getenv("MINUTEWATCHER_STORE_PASSWORD")
    val envKeyAlias = System.getenv("MINUTEWATCHER_KEY_ALIAS")
    val envKeyPassword = System.getenv("MINUTEWATCHER_KEY_PASSWORD")

    val hasReleaseSigning =
        keystoreFile.exists() &&
            !envStorePassword.isNullOrEmpty() &&
            !envKeyAlias.isNullOrEmpty() &&
            !envKeyPassword.isNullOrEmpty()

    if (!hasReleaseSigning) {
        val missingParams = mutableListOf<String>()
        if (!keystoreFile.exists()) missingParams.add("release.keystore file")
        if (envStorePassword.isNullOrEmpty()) missingParams.add("MINUTEWATCHER_STORE_PASSWORD env var")
        if (envKeyAlias.isNullOrEmpty()) missingParams.add("MINUTEWATCHER_KEY_ALIAS env var")
        if (envKeyPassword.isNullOrEmpty()) missingParams.add("MINUTEWATCHER_KEY_PASSWORD env var")
        logger.warn("Release signing is disabled due to missing parameters: ${missingParams.joinToString(", ")}")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The bundle language DSL is incubating in Gradle. Disabling language splits is the
    // documented requirement for per-app language support on API 26-32: all locales must
    // remain in the base APK. Suppression is intentional and documented.
    @Suppress("UnstableApiUsage")
    bundle {
        language {
            enableSplit = false
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
    implementation(libs.androidxGlanceAppwidget)

    baselineProfile(project(":baselineprofile"))

    coreLibraryDesugaring(libs.desugarJdkLibs)

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
    testImplementation(testFixtures(project(":core:resources")))
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(libs.androidxWorkTesting)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidxComposeBom))
    androidTestImplementation(libs.androidxJunit)
    androidTestImplementation(libs.androidxTestRunner)
    androidTestImplementation(libs.androidxUiTestJunit4)
    debugImplementation(libs.androidxUiTooling)
    debugImplementation(libs.androidxUiTestManifest)
}
