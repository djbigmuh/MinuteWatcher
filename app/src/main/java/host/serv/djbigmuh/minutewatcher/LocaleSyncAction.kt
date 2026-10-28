package host.serv.djbigmuh.minutewatcher

internal sealed interface LocaleSyncAction {
    data object None : LocaleSyncAction

    data class AdoptSystem(
        val tags: String,
    ) : LocaleSyncAction

    data object FollowSystem : LocaleSyncAction

    data class RestoreSaved(
        val language: String,
    ) : LocaleSyncAction
}

internal fun normalizeToSupportedLanguageCode(
    rawTag: String,
    supportedCodes: List<String>,
): String {
    supportedCodes.firstOrNull { it.equals(rawTag, ignoreCase = true) }?.let { return it }

    val rawPrimary =
        java.util.Locale
            .forLanguageTag(rawTag)
            .language
    if (rawPrimary.isNotEmpty()) {
        val matchingCodes =
            supportedCodes.filter { code ->
                val codePrimary =
                    java.util.Locale
                        .forLanguageTag(code)
                        .language
                codePrimary.isNotEmpty() && codePrimary.equals(rawPrimary, ignoreCase = true)
            }
        if (matchingCodes.size == 1) {
            return matchingCodes.first()
        }
    }

    return rawTag
}

internal fun resolveLocaleSyncAction(
    currentTags: String?,
    savedLanguage: String?,
    supportsSystemPerAppLanguages: Boolean,
    supportedLanguageCodes: List<String>,
): LocaleSyncAction =
    when {
        currentTags == null ->
            when {
                savedLanguage == null -> LocaleSyncAction.None
                supportsSystemPerAppLanguages -> LocaleSyncAction.FollowSystem
                else -> LocaleSyncAction.RestoreSaved(savedLanguage)
            }

        currentTags == savedLanguage -> LocaleSyncAction.None
        else -> LocaleSyncAction.AdoptSystem(normalizeToSupportedLanguageCode(currentTags, supportedLanguageCodes))
    }
