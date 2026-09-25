package com.example.minutewatcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleSyncActionTest {
    @Test
    fun `resolveLocaleSyncAction returns None when current tags match saved language`() {
        val action =
            resolveLocaleSyncAction(
                currentTags = "de",
                savedLanguage = "de",
                supportsSystemPerAppLanguages = true,
            )
        assertEquals(LocaleSyncAction.None, action)
    }

    @Test
    fun `resolveLocaleSyncAction returns AdoptSystem when current tags differ from saved language`() {
        val action =
            resolveLocaleSyncAction(
                currentTags = "fr",
                savedLanguage = "de",
                supportsSystemPerAppLanguages = true,
            )
        assertEquals(LocaleSyncAction.AdoptSystem("fr"), action)
    }

    @Test
    fun `resolveLocaleSyncAction returns AdoptSystem when current tags exist and saved language is null`() {
        val action =
            resolveLocaleSyncAction(
                currentTags = "de",
                savedLanguage = null,
                supportsSystemPerAppLanguages = true,
            )
        assertEquals(LocaleSyncAction.AdoptSystem("de"), action)
    }

    @Test
    fun `resolveLocaleSyncAction returns FollowSystem when tags null and saved language present on SDK 33+`() {
        val action =
            resolveLocaleSyncAction(
                currentTags = null,
                savedLanguage = "de",
                supportsSystemPerAppLanguages = true,
            )
        assertEquals(LocaleSyncAction.FollowSystem, action)
    }

    @Test
    fun `resolveLocaleSyncAction returns RestoreSaved when tags null and saved language present below SDK 33`() {
        val action =
            resolveLocaleSyncAction(
                currentTags = null,
                savedLanguage = "de",
                supportsSystemPerAppLanguages = false,
            )
        assertEquals(LocaleSyncAction.RestoreSaved("de"), action)
    }

    @Test
    fun `resolveLocaleSyncAction returns None when both current tags and saved language are null`() {
        val action =
            resolveLocaleSyncAction(
                currentTags = null,
                savedLanguage = null,
                supportsSystemPerAppLanguages = true,
            )
        assertEquals(LocaleSyncAction.None, action)
    }
}
