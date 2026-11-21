package com.github.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
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
import com.github.djbigmuh.minutewatcher.core.android.util.AppLocaleContexts
import com.github.djbigmuh.minutewatcher.core.android.util.WidgetStringsFactory
import com.github.djbigmuh.minutewatcher.core.android.util.WidgetUiMapper
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.NavigationProvider
import com.github.djbigmuh.minutewatcher.core.domain.RefreshResult
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import com.github.djbigmuh.minutewatcher.core.model.WidgetUiState
import com.github.djbigmuh.minutewatcher.core.resources.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Main class of MinuteWatcher widget.
 * Renders SIM card state on the home screen.
 */
class MinutesGlanceWidget : GlanceAppWidget() {
    /**
     * Hilt EntryPoint for widget.
     * Allows obtaining dependencies in widget via EntryPointAccessors.
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
     * Renders widget.
     * Called by system on widget update.
     * Shows FallbackContent on any exception.
     */
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        @Suppress("TooGenericExceptionCaught")
        try {
            val uiState = getUiState(context)
            val navigationProvider =
                @Suppress("TooGenericExceptionCaught")
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
     * Renders fallback widget content.
     */
    @Composable
    private fun FallbackContent(context: Context) {
        val appLocaleContext =
            AppLocaleContexts
                .forAppLocale(context)
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = appLocaleContext.getString(R.string.refresh_error),
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
     * Gets widget state.
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

        return getUiStateInternal(context, usageRepository, widgetSettingsLoader, timeProvider)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun getUiStateInternal(
        context: Context,
        usageRepository: UsageStateRepository,
        widgetSettingsLoader: WidgetSettingsLoader,
        timeProvider: TimeProvider,
    ): WidgetUiState =
        coroutineScope {
            val settingsDeferred =
                async {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        widgetSettingsLoader.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Failed to load widget settings, using defaults", e)
                        WidgetSettingsState(settingsLoadFailed = true)
                    }
                }

            val refreshResultDeferred =
                async {
                    getSnapshot(usageRepository, timeProvider)
                }

            val settingsState = settingsDeferred.await()
            val refreshResult = refreshResultDeferred.await()

            val snapshot = refreshResult.snapshot
            val refreshFailed =
                when (refreshResult) {
                    is RefreshResult.Success -> false
                    is RefreshResult.Timeout -> {
                        AppLogger.w("Widget: using stale snapshot, timestamp=${refreshResult.snapshot.timestamp}")
                        true
                    }

                    is RefreshResult.Failure -> {
                        AppLogger.e(
                            "Widget: refresh failed, using stale snapshot, " +
                                "timestamp=${refreshResult.snapshot.timestamp}",
                            refreshResult.cause,
                        )
                        true
                    }
                }

            WidgetUiMapper.mapFromSnapshot(
                strings = WidgetStringsFactory.create(context),
                snapshot = snapshot,
                state = settingsState,
                operatorNames = WidgetStringsFactory.createOperatorNames(context, snapshot.simSnapshots),
                refreshFailed = refreshFailed,
            )
        }

    /**
     * Gets snapshot for widget.
     */
    internal suspend fun getSnapshot(
        usageRepository: UsageStateRepository,
        timeProvider: TimeProvider,
    ): RefreshResult {
        val initialSnapshot = usageRepository.observeSnapshot().value
        val now = timeProvider.now()
        val isStale =
            (initialSnapshot.timestamp != Constants.Data.EMPTY_TIMESTAMP) &&
                ((now - initialSnapshot.timestamp) > Constants.Data.STALE_SNAPSHOT_THRESHOLD_MS)

        if ((initialSnapshot.timestamp != Constants.Data.EMPTY_TIMESTAMP) && !isStale) {
            return RefreshResult
                .Success(initialSnapshot)
        }

        @Suppress("TooGenericExceptionCaught")
        val res =
            try {
                usageRepository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Widget refresh failed", e)
                RefreshResult.Failure(initialSnapshot, e)
            }
        return res
    }
}
