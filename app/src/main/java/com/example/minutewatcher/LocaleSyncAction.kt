package com.example.minutewatcher

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

internal fun resolveLocaleSyncAction(
    currentTags: String?,
    savedLanguage: String?,
    supportsSystemPerAppLanguages: Boolean,
): LocaleSyncAction =
    when {
        currentTags == null ->
            when {
                savedLanguage == null -> LocaleSyncAction.None
                supportsSystemPerAppLanguages -> LocaleSyncAction.FollowSystem
                else -> LocaleSyncAction.RestoreSaved(savedLanguage)
            }

        currentTags == savedLanguage -> LocaleSyncAction.None
        else -> LocaleSyncAction.AdoptSystem(currentTags)
    }
