plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

// Use provider for version catalog access
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val agpVersion = libs.findVersion("agp").get().requiredVersion
    val kotlinVersion = libs.findVersion("kotlin").get().requiredVersion
    compileOnly("com.android.tools.build:gradle:$agpVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
}
