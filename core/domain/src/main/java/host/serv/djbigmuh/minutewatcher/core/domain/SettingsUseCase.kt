package host.serv.djbigmuh.minutewatcher.core.domain

import host.serv.djbigmuh.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Use case for managing application settings.
 *
 * All methods are safe to call from any coroutine context.
 * Operations comply with AOSP DataStore best practices via [AppSettingsRepository].
 */
@Singleton
class SettingsUseCase
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
) {
    /**
     * Flow of the current application theme.
     */
    val appTheme: Flow<AppTheme> = appSettingsRepository.observeAppSettings().map { it.theme }

    /**
     * Flow of the current application language code.
     */
    val appLanguage: Flow<String?> = appSettingsRepository.observeAppSettings().map { it.language }

    /**
     * Sets the application theme.
     *
     * @param theme The new theme to apply.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setTheme(theme: AppTheme) {
        appSettingsRepository.updateAppSettings { it.copy(theme = theme) }
    }

    /**
     * Sets the application language.
     *
     * @param language The new language code to apply.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setLanguage(language: String?) {
        appSettingsRepository.updateAppSettings { it.copy(language = language) }
    }
}
