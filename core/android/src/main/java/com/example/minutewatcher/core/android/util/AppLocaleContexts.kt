package com.example.minutewatcher.core.android.util

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Utility for creating localized contexts that respect the per-app locale
 * set via [AppCompatDelegate.setApplicationLocales] or [LocaleManager].
 *
 * Safe to call on any API level and with any context (Application, Service, or Activity).
 */
object AppLocaleContexts {
    /**
     * Returns a [Context] with the application locale applied if specified via [AppCompatDelegate] or system settings.
     *
     * If no per-app locale is set, returns the original [context].
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

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(firstLocale)
        configuration.setLayoutDirection(firstLocale)

        val localizedContext = context.createConfigurationContext(configuration)
        @Suppress("DEPRECATION")
        localizedContext.resources.updateConfiguration(configuration, localizedContext.resources.displayMetrics)
        return localizedContext
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
