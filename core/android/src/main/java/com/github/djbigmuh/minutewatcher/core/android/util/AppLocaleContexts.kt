package com.github.djbigmuh.minutewatcher.core.android.util

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for creating localized contexts that respect the per-app locale
 * set via [AppCompatDelegate.setApplicationLocales] or [LocaleManager].
 *
 * Safe to call on any API level and with any context (Application, Service, or Activity).
 */
object AppLocaleContexts {
    private data class CacheKey(
        val appContext: Context,
        val localeTag: String,
    )

    private val cache = ConcurrentHashMap<CacheKey, Context>()

    /**
     * Returns a [Context] with the application locale applied if specified via [AppCompatDelegate] or system settings.
     * Caches localized contexts for performance.
     *
     * If no per-app locale is set, returns the original [context].
     *
     * Note: AppBundleLocaleChanges is suppressed because bundle language split is disabled in consuming :app
     * module, but library module isolation prevents Android Lint from inspecting app bundle DSL.
     *
     * @param context The base context.
     * @return Localized [Context] if app locale is set, otherwise [context].
     */
    @SuppressLint("AppBundleLocaleChanges")
    fun forAppLocale(context: Context): Context {
        val appLocales = getAppLocales(context)
        val firstLocale = if (!appLocales.isEmpty) appLocales.get(0) else null

        if (firstLocale == null) {
            return context
        }

        val localeTag = firstLocale.toLanguageTag()
        val appContext = context.applicationContext ?: context
        val key = CacheKey(appContext, localeTag)

        return cache.computeIfAbsent(key) {
            val configuration = Configuration(appContext.resources.configuration)
            configuration.setLocale(firstLocale)
            configuration.setLayoutDirection(firstLocale)
            appContext.createConfigurationContext(configuration)
        }
    }

    private fun getAppLocales(context: Context): LocaleListCompat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val frameworkLocales = localeManager?.applicationLocales
            if (frameworkLocales != null && !frameworkLocales.isEmpty) {
                LocaleListCompat.wrap(frameworkLocales)
            } else {
                AppCompatDelegate.getApplicationLocales()
            }
        } else {
            AppCompatDelegate.getApplicationLocales()
        }
}
