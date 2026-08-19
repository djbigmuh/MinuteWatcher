plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

val libs = versionCatalogs.named("libs")

dependencies {
    compileOnly(libs.findLibrary("android-gradlePlugin").get())
    compileOnly(libs.findLibrary("kotlin-gradlePlugin").get())
}
