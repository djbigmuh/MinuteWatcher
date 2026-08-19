plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("android-gradlePlugin").get())
    compileOnly(libs.findLibrary("kotlin-gradlePlugin").get())
}
