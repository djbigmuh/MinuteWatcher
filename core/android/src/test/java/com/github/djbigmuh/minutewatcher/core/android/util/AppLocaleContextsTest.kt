package com.github.djbigmuh.minutewatcher.core.android.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.resources.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AppLocaleContextsTest {
    private lateinit var context: Context
    private var initialLocales: LocaleListCompat = LocaleListCompat.getEmptyLocaleList()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        initialLocales = AppCompatDelegate.getApplicationLocales()
    }

    @After
    fun tearDown() {
        setAppLocale(initialLocales)
    }

    private fun setAppLocale(locales: LocaleListCompat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (localeManager != null) {
                val targetList =
                    if (locales.isEmpty) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        val first = locales.get(0)
                        if (first != null) LocaleList(first) else LocaleList.getEmptyLocaleList()
                    }
                localeManager.applicationLocales = targetList
            }
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    @Test
    fun `forAppLocale returns original context when app locales empty`() {
        setAppLocale(LocaleListCompat.getEmptyLocaleList())

        val result = AppLocaleContexts.forAppLocale(context)
        assertEquals(context, result)
    }

    @Test
    fun `forAppLocale applies selected app locale`() {
        val russianLocale = Locale.forLanguageTag("ru")
        setAppLocale(LocaleListCompat.create(russianLocale))

        val localizedContext = AppLocaleContexts.forAppLocale(context)
        val currentLocale =
            localizedContext
                .resources
                .configuration
                .locales
                .get(0)

        assertEquals("ru", currentLocale.language)
        assertEquals("SIM-карты не найдены", localizedContext.getString(R.string.no_sim_found))
    }

    @Test
    fun `forAppLocale updates when app locale changes`() {
        val spanishLocale = Locale.forLanguageTag("es")
        setAppLocale(LocaleListCompat.create(spanishLocale))

        val localizedContext = AppLocaleContexts.forAppLocale(context)
        val currentLocale =
            localizedContext
                .resources
                .configuration
                .locales
                .get(0)

        assertEquals("es", currentLocale.language)
        assertEquals("Tarjetas SIM no encontradas", localizedContext.getString(R.string.no_sim_found))
    }

    @Test
    fun `forAppLocale caches localized context and returns same instance`() {
        val russianLocale = Locale.forLanguageTag("ru")
        setAppLocale(LocaleListCompat.create(russianLocale))

        val localized1 = AppLocaleContexts.forAppLocale(context)
        val localized2 = AppLocaleContexts.forAppLocale(context)

        org.junit.Assert.assertSame(localized1, localized2)
    }

    @Test
    fun `forAppLocale uses application context for creating configuration context`() {
        val russianLocale = Locale.forLanguageTag("ru")
        setAppLocale(LocaleListCompat.create(russianLocale))

        val mockAppCtx: Context = mock()
        val mockBaseCtx: Context = mock()
        val mockConfigCtx: Context = mock()

        doReturn(
            context.getSystemService(LocaleManager::class.java),
        ).whenever(mockBaseCtx).getSystemService(eq(LocaleManager::class.java))
        doReturn(
            context.getSystemService(LocaleManager::class.java),
        ).whenever(mockAppCtx).getSystemService(eq(LocaleManager::class.java))
        doReturn(mockAppCtx).whenever(mockBaseCtx).applicationContext
        doReturn(context.resources).whenever(mockAppCtx).resources
        doReturn(
            mockConfigCtx,
        ).whenever(mockAppCtx).createConfigurationContext(org.mockito.ArgumentMatchers.any(Configuration::class.java))

        val result = AppLocaleContexts.forAppLocale(mockBaseCtx)
        assertEquals(mockConfigCtx, result)
        org.mockito.kotlin
            .verify(
                mockAppCtx,
            ).createConfigurationContext(org.mockito.ArgumentMatchers.any(Configuration::class.java))
        org.mockito.kotlin
            .verify(
                mockBaseCtx,
                org.mockito.kotlin.never(),
            ).createConfigurationContext(org.mockito.ArgumentMatchers.any(Configuration::class.java))
    }
}
