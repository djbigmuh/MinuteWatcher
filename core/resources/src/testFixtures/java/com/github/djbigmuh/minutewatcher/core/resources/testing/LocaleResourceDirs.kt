package com.github.djbigmuh.minutewatcher.core.resources.testing

import java.io.File

/**
 * Utility for mapping between language tags and Android resource directories (qualifiers),
 * including ISO 639 mapping table for localization tests and consistency checks.
 */
object LocaleResourceDirs {
    val resDir: File
        get() = ProjectPaths.resDir

    /**
     * Supported language tags in the application.
     */
    val supportedLanguageTags: Set<String> =
        setOf(
            "en",
            "ar",
            "bho",
            "bn",
            "de",
            "es",
            "fa",
            "fr",
            "gu",
            "ha",
            "hi",
            "id",
            "it",
            "ja",
            "jv",
            "ko",
            "mr",
            "pa",
            "pt-BR",
            "pt-PT",
            "ru",
            "ta",
            "te",
            "tr",
            "ur",
            "vi",
            "yo",
            "zh",
        )

    /**
     * Returns the Android resource directory name for a given language tag.
     */
    fun resourceDirForTag(tag: String): String =
        when (tag) {
            "en" -> "values"
            "pt-PT" -> "values-pt"
            "pt-BR" -> "values-pt-rBR"
            "id" -> "values-in"
            else -> "values-$tag"
        }

    /**
     * Returns the language tag for a given Android resource directory name.
     */
    fun languageTagOfQualifier(dirName: String): String =
        when (dirName) {
            "values" -> "en"
            "values-pt" -> "pt-PT"
            "values-pt-rBR" -> "pt-BR"
            "values-in" -> "id"
            else -> dirName.removePrefix("values-")
        }
}
