plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

// Официальный API Gradle 8+ для доступа к каталогам версий в build-logic.
// Заменяет устаревший extensions.getByType<VersionCatalogsExtension>().named("libs").
val libs = versionCatalogs.named("libs")

dependencies {
    compileOnly(libs.findLibrary("android-gradlePlugin").get())
    compileOnly(libs.findLibrary("kotlin-gradlePlugin").get())
}
