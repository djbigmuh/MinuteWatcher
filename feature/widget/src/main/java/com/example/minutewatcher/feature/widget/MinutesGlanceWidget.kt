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
import com.example.minutewatcher.core.android.util.AppLocaleContexts
import com.example.minutewatcher.core.android.util.WidgetStringsFactory
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.RefreshResult
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
 * Main class of MinuteWatcher widget.
 * Renders SIM card state on the home screen.
 *
 * @param forceFullRender If true, forces the full layout rendering by ignoring
 * the global `widgetLimitExceeded` flag during this specific render pass.
 * This is used for probe updates to see if the full layout can now fit.
 *
 * **Note:** Since Glance `updateAll` updates all instances of the widget,
 * calling it with a specific `forceFullRender` value will affect all
 * currently placed widgets on the home screen for that render pass.
 *
 * Strict API compliance: all components use Glance API
 * according to documentation. On AOSP Glance ensures
 * stable rendering; deviations are possible only on custom firmwares.
 */
class MinutesGlanceWidget(
    internal val forceFullRender: Boolean = false,
) : GlanceAppWidget() {
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
            // Using refresh_error as a general fallback message.
            // This is intentional as most provideGlance failures are related to data access.
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

        val refreshResult = getSnapshot(usageRepository, timeProvider)
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

        val settingsState =
            try {
                val loaded = widgetSettingsLoader.load()
                if (forceFullRender) loaded.copy(widgetLimitExceeded = false) else loaded
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
     * Gets snapshot for widget.
     */
    internal suspend fun getSnapshot(
        usageRepository: UsageStateRepository,
        timeProvider: TimeProvider,
    ): RefreshResult {
        val initialSnapshot = usageRepository.observeSnapshot().value
        val now = timeProvider.now()
        val isStale = (now - initialSnapshot.timestamp) > Constants.Data.STALE_SNAPSHOT_THRESHOLD_MS

        if ((initialSnapshot.timestamp != 0L) && !isStale) {
            return RefreshResult
                .Success(initialSnapshot)
        }

        return try {
            usageRepository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Widget refresh failed", e)
            RefreshResult.Failure(initialSnapshot, e)
        }
    }
}
