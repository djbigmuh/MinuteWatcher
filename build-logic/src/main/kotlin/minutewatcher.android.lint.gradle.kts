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
    lint.disable.add("GradleDependency") // Versions managed centrally by version catalog
    lint.disable.add("AndroidGradlePluginVersion") // AGP version managed centrally by version catalog and conventions
    lint.disable.add("VectorPath") // Validated by automated OperatorIconAssetComplianceTest in core:resources
    lint.ignore.add("ObsoleteSdkInt") // Ignored for false positive on mipmap-anydpi-v26 resource directory naming

}
