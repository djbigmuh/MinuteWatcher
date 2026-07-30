import com.android.build.api.dsl.CommonExtension

val extension = extensions.findByType(CommonExtension::class.java)

extension?.apply {
    lint.abortOnError = true
    lint.warningsAsErrors = true
    lint.checkReleaseBuilds = true
    lint.error.add("MissingTranslation")
    lint.error.add("UnusedResources")
    lint.fatal.add("MissingTranslation")
    lint.fatal.add("UnusedResources")
    lint.ignoreTestSources = true
    lint.checkTestSources = false
    lint.disable.add("GradleDependency")
}
