package host.serv.djbigmuh.minutewatcher.buildlogic

/**
 * Centralized Android SDK and JVM Toolchain version configuration for all modules.
 *
 * [JAVA_TOOLCHAIN_VERSION] is used as the JVM toolchain version across all project modules.
 *
 * IMPORTANT: DO NOT DOWNGRADE SDK VERSIONS.
 * minSdk 26, targetSdk 37, compileSdk 37 are strict project requirements.
 */
object SdkVersions {
    const val MIN_SDK = 26
    const val TARGET_SDK = 37
    const val COMPILE_SDK = 37
    const val JAVA_TOOLCHAIN_VERSION = 21
}
