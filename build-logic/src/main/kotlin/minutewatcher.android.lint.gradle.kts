import com.android.build.api.dsl.CommonExtension

val extension = extensions.findByType(CommonExtension::class.java)
    ?: error("Android extension is not available; apply an Android plugin before minutewatcher.android.lint")

extension.apply {
    lint.abortOnError = true
    lint.warningsAsErrors = true
    lint.checkReleaseBuilds = true
    lint.fatal.add("MissingTranslation")
    lint.fatal.add("UnusedResources")
    lint.ignoreTestSources = true
    lint.checkTestSources = false
    lint.disable.add("GradleDependency") // Version catalog manages dependency versions centrally
    lint.disable.add("AndroidGradlePluginVersion") // AGP version is managed via version catalog and build-logic conventions
    lint.disable.add("ObsoleteSdkInt") // Explicit SDK checks are required for minSdk 26 compatibility with modern platform APIs
    lint.disable.add("VectorPath") // Complex operator brand vector icon paths are validated via custom compliance tests
}
