plugins {
    id("minutewatcher.android-library")
}

android {
    namespace = "com.example.minutewatcher.core.resources"

    defaultConfig {
    }

    lint {
        // Lint baseline captures ONLY pre-existing issues.
        // New code MUST NOT be added to the baseline — fix issues at the source.
        // When refactoring a file, remove its entries from the baseline first.
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
}
