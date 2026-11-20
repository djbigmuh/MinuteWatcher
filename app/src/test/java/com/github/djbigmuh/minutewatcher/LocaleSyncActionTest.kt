package com.github.djbigmuh.minutewatcher

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
                supportedLanguageCodes = listOf("de", "en", "fr"),
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
                supportedLanguageCodes = listOf("de", "en", "fr"),
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
                supportedLanguageCodes = listOf("de", "en", "fr"),
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
                supportedLanguageCodes = listOf("de", "en", "fr"),
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
                supportedLanguageCodes = listOf("de", "en", "fr"),
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
                supportedLanguageCodes = listOf("de", "en", "fr"),
            )
        assertEquals(LocaleSyncAction.None, action)
    }

    @Test
    fun `normalizeToSupportedLanguageCode matches exact code ignoring case`() {
        val result = normalizeToSupportedLanguageCode("FR", listOf("en", "fr", "de"))
        assertEquals("fr", result)
    }

    @Test
    fun `normalizeToSupportedLanguageCode matches primary subtag with regional subtag`() {
        val result = normalizeToSupportedLanguageCode("de-DE", listOf("en", "de"))
        assertEquals("de", result)
    }

    @Test
    fun `normalizeToSupportedLanguageCode returns raw tag when ambiguous primary subtag matches`() {
        val result = normalizeToSupportedLanguageCode("pt-AO", listOf("en", "pt", "pt-BR"))
        assertEquals("pt-AO", result)
    }

    @Test
    fun `normalizeToSupportedLanguageCode returns raw tag when unsupported or invalid tag`() {
        assertEquals("sw-KE", normalizeToSupportedLanguageCode("sw-KE", listOf("en", "de")))
        assertEquals("!!!", normalizeToSupportedLanguageCode("!!!", listOf("en", "de")))
    }

    @Test
    fun `normalizeToSupportedLanguageCode returns raw tag when supported codes list is empty`() {
        val result = normalizeToSupportedLanguageCode("de-DE", emptyList())
        assertEquals("de-DE", result)
    }

    @Test
    fun `resolveLocaleSyncAction normalizes current tags when adopting system language`() {
        val action =
            resolveLocaleSyncAction(
                currentTags = "de-DE",
                savedLanguage = "en",
                supportsSystemPerAppLanguages = true,
                supportedLanguageCodes = listOf("en", "de"),
            )
        assertEquals(LocaleSyncAction.AdoptSystem("de"), action)
    }
}
