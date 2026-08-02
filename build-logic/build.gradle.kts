plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

// Use provider for version catalog access
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("android-gradle-plugin").get())
    compileOnly(libs.findLibrary("kotlin-gradle-plugin").get())
}
