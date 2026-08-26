package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.minutewatcher.core.android.util.WidgetStringsFactory
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Основной класс виджета MinuteWatcher.
 * Рендерит состояние SIM-карт на домашнем экране.
 *
 * Строгое соблюдение API: все компоненты используют Glance API
 * в соответствии с документацией. На AOSP Glance обеспечивает
 * стабильный рендер; отклонения возможны только на кастомных прошивках.
 */
class MinutesGlanceWidget : GlanceAppWidget() {
    companion object {
        /**
         * Flag to override the global limit exceeded setting during a probe update.
         * Only valid within the same process.
         */
        @Volatile
        var isProbeActive: Boolean = false
    }

    /**
     * Hilt EntryPoint для виджета.
     * Позволяет получать зависимости в виджете через EntryPointAccessors.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun usageStateRepository(): UsageStateRepository

        fun widgetSettingsLoader(): WidgetSettingsLoader

        fun navigationProvider(): NavigationProvider

        fun timeProvider(): TimeProvider
    }

    /**
     * Рендерит виджет.
     * Вызывается системой при обновлении виджета.
     * При любом исключении показывает FallbackContent.
     */
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        try {
            val uiState = getUiState(context)
            val navigationProvider =
                try {
                    getNavigationProvider(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Widget: failed to get NavigationProvider", e)
                    null
                }

            provideContent {
                GlanceTheme {
                    WidgetContent(uiState, navigationProvider)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Widget provideGlance failed, showing fallback", e)
            provideContent {
                GlanceTheme {
                    FallbackContent(context)
                }
            }
        }
    }

    /**
     * Рендерит fallback-содержимое виджета.
     */
    @Composable
    private fun FallbackContent(context: Context) {
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = context.getString(R.string.refresh_error),
                style =
                    TextStyle(
                        fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                    ),
            )
        }
    }

    private fun getNavigationProvider(context: Context): NavigationProvider {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        return entryPoint.navigationProvider()
    }

    /**
     * Получает состояние виджета.
     */
    private suspend fun getUiState(context: Context): WidgetUiState {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        val usageRepository = entryPoint.usageStateRepository()
        val widgetSettingsLoader = entryPoint.widgetSettingsLoader()
        val timeProvider = entryPoint.timeProvider()

        val refreshResult = getSnapshot(usageRepository, timeProvider)
        val snapshot = refreshResult.snapshot
        val refreshFailed =
            when (refreshResult) {
                is com.example.minutewatcher.core.domain.RefreshResult.Success -> false
                is com.example.minutewatcher.core.domain.RefreshResult.Timeout -> {
                    AppLogger.w("Widget: using stale snapshot, timestamp=${refreshResult.snapshot.timestamp}")
                    true
                }
            }

        val settingsState =
            try {
                val loaded = widgetSettingsLoader.load()
                if (isProbeActive) loaded.copy(widgetLimitExceeded = false) else loaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to load widget settings, using defaults", e)
                WidgetSettingsState(settingsLoadFailed = true)
            }

        return WidgetUiMapper.mapFromSnapshot(
            strings = WidgetStringsFactory.create(context),
            snapshot = snapshot,
            state = settingsState,
            operatorNames = WidgetStringsFactory.createOperatorNames(context, snapshot.simSnapshots),
            refreshFailed = refreshFailed,
        )
    }

    /**
     * Получает snapshot для виджета.
     */
    private suspend fun getSnapshot(
        usageRepository: UsageStateRepository,
        timeProvider: TimeProvider,
    ): com.example.minutewatcher.core.domain.RefreshResult {
        val initialSnapshot = usageRepository.observeSnapshot().value
        val now = timeProvider.now()
        val isStale = (now - initialSnapshot.timestamp) > Constants.Data.STALE_SNAPSHOT_THRESHOLD_MS

        if (initialSnapshot.timestamp != 0L && !isStale) {
            return com.example.minutewatcher.core.domain.RefreshResult
                .Success(initialSnapshot)
        }

        return try {
            usageRepository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Widget refresh failed", e)
            com.example.minutewatcher.core.domain.RefreshResult
                .Timeout(initialSnapshot)
        }
    }
}
