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
    lint.disable.add("GradleDependency")
}
