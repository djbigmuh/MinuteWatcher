package com.example.minutewatcher

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.feature.main.MainViewModel
import kotlinx.coroutines.launch

/**
 * Helper for observing theme and language changes in MainActivity.
 */
object MainActivityThemeObserver {
    fun observe(
        lifecycleScope: LifecycleCoroutineScope,
        lifecycle: Lifecycle,
        viewModel: MainViewModel,
    ) {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.appTheme.collect { theme ->
                        val mode =
                            when (theme) {
                                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                                AppTheme.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            }
                        AppCompatDelegate.setDefaultNightMode(mode)
                    }
                }
                launch {
                    viewModel.appLanguage.collect { lang ->
                        val appLocale: LocaleListCompat =
                            if (lang == null) {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(lang)
                            }
                        if (AppCompatDelegate.getApplicationLocales() != appLocale) {
                            AppCompatDelegate.setApplicationLocales(appLocale)
                        }
                    }
                }
            }
        }
    }
}
