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
    }

    val keystoreFile = layout.projectDirectory.file("release.keystore").asFile
    val envStorePassword = providers.environmentVariable("MINUTEWATCHER_STORE_PASSWORD").orNull
    val envKeyAlias = providers.environmentVariable("MINUTEWATCHER_KEY_ALIAS").orNull
    val envKeyPassword = providers.environmentVariable("MINUTEWATCHER_KEY_PASSWORD").orNull

    val hasReleaseSigning =
        keystoreFile.exists() &&
            !envStorePassword.isNullOrEmpty() &&
            !envKeyAlias.isNullOrEmpty() &&
            !envKeyPassword.isNullOrEmpty()

    if (!hasReleaseSigning) {
        val missingParams = mutableListOf<String>()
        if (!keystoreFile.exists()) missingParams.add("release.keystore file (expected at app/release.keystore)")
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
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    debugImplementation(libs.androidxUiTooling)
}
