import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties

plugins {
    id("minutewatcher.android-application")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.androidxBaselineprofile)
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProps = Properties()
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use { signingProps.load(it) }
}
val storePath =
    signingProps.getProperty("storeFile") ?: providers.environmentVariable("MINUTEWATCHER_STORE_FILE").orNull
val storePass =
    signingProps.getProperty("storePassword")
        ?: providers.environmentVariable("MINUTEWATCHER_STORE_PASSWORD").orNull
val keyAl = signingProps.getProperty("keyAlias") ?: providers.environmentVariable("MINUTEWATCHER_KEY_ALIAS").orNull
val keyPass =
    signingProps.getProperty("keyPassword") ?: providers.environmentVariable("MINUTEWATCHER_KEY_PASSWORD").orNull

val keystoreFile =
    if (storePath != null) {
        rootProject.file(storePath)
    } else {
        rootProject.file("release.keystore")
    }

val hasReleaseSigning =
    keystoreFile.exists() &&
        !storePass.isNullOrEmpty() &&
        !keyAl.isNullOrEmpty() &&
        !keyPass.isNullOrEmpty()

if (!hasReleaseSigning) {
    val missingParams = mutableListOf<String>()
    if (!keystoreFile.exists()) {
        missingParams.add(
            "release.keystore file (expected at app/release.keystore or signing.properties)",
        )
    }
    if (storePass.isNullOrEmpty()) {
        missingParams.add(
            "storePassword (in signing.properties or MINUTEWATCHER_STORE_PASSWORD env var)",
        )
    }
    if (keyAl.isNullOrEmpty()) {
        missingParams.add(
            "keyAlias (in signing.properties or MINUTEWATCHER_KEY_ALIAS env var)",
        )
    }
    if (keyPass.isNullOrEmpty()) {
        missingParams.add(
            "keyPassword (in signing.properties or MINUTEWATCHER_KEY_PASSWORD env var)",
        )
    }

    val isReleaseBuildRequested =
        rootProject.gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = true) && !it.contains("Test", ignoreCase = true)
        }
    if (isReleaseBuildRequested) {
        throw GradleException(
            "Release signing configuration is missing mandatory parameters: ${missingParams.joinToString(", ")}",
        )
    } else {
        logger.warn("Release signing is disabled due to missing parameters: ${missingParams.joinToString(", ")}")
    }
}

configure<ApplicationExtension> {
    namespace = "com.github.djbigmuh.minutewatcher"

    defaultConfig {
        applicationId = "com.github.djbigmuh.minutewatcher"
        versionCode = 110
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = storePass ?: ""
            keyAlias = keyAl ?: ""
            keyPassword = keyPass ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("benchmark") {
            java.directories.add("src/benchmark/java")
            kotlin.directories.add("src/benchmark/java")
        }
        getByName("testBenchmark") {
            java.directories.add("src/testBenchmark/java")
            kotlin.directories.add("src/testBenchmark/java")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().withBuildType("release")) { variant ->
        val mergedManifestProvider =
            variant.artifacts.get(
                com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST,
            )
        tasks.withType<Test>().configureEach {
            inputs.file(mergedManifestProvider)
            doFirst {
                systemProperty(
                    "minutewatcher.releaseMergedManifestPath",
                    mergedManifestProvider.get().asFile.absolutePath,
                )
            }
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
