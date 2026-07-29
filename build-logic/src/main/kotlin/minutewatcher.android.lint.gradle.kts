import com.android.build.api.dsl.CommonExtension

val extension = extensions.findByType(CommonExtension::class.java)

extension?.apply {
    lint.abortOnError = true
    lint.warningsAsErrors = true
    lint.checkReleaseBuilds = true
    lint.error.add("MissingTranslation")
}
