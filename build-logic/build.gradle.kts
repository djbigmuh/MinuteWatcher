plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

// Use provider for version catalog access
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("androidGradlePlugin").get())
    compileOnly(libs.findLibrary("kotlinGradlePlugin").get())
}
