package com.github.djbigmuh.minutewatcher

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.feature.main.MainViewModel
import com.github.djbigmuh.minutewatcher.feature.main.supportedLanguageOptions
import kotlinx.coroutines.launch

/**
 * Helper for observing theme and language changes in MainActivity.
 */
object MainActivityThemeObserver {
    fun observe(
        lifecycleScope: LifecycleCoroutineScope,
        lifecycle: Lifecycle,
        viewModel: MainViewModel,
        onLocaleRestored: () -> Unit = {},
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
                        val appLocales = AppCompatDelegate.getApplicationLocales()
                        val currentTags = if (appLocales.isEmpty) null else appLocales.toLanguageTags().ifEmpty { null }
                        val supportsPerApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

                        val action =
                            resolveLocaleSyncAction(
                                currentTags = currentTags,
                                savedLanguage = lang,
                                supportsSystemPerAppLanguages = supportsPerApp,
                                supportedLanguageCodes = supportedLanguageOptions.mapNotNull { it.code },
                            )

                        when (action) {
                            is LocaleSyncAction.None -> {}
                            is LocaleSyncAction.AdoptSystem -> viewModel.setLanguage(action.tags)
                            is LocaleSyncAction.FollowSystem -> viewModel.setLanguage(null)
                            is LocaleSyncAction.RestoreSaved -> {
                                AppCompatDelegate.setApplicationLocales(
                                    LocaleListCompat.forLanguageTags(action.language),
                                )
                                onLocaleRestored()
                            }
                        }
                    }
                }
            }
        }
    }
}
