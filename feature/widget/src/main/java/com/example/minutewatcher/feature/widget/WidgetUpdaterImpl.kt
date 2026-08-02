package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdaterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val appSettingsRepository: AppSettingsRepository,
    ) : WidgetUpdater {
        private val widget = MinutesGlanceWidget()

        override fun updateAll() {
            scope.launch {
                try {
                    widget.updateAll(context)
                    appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = false) }
                } catch (e: IllegalArgumentException) {
                    AppLogger.e("Glance internal error during updateAll (possible RemoteViews limit exceeded)", e)
                    appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Unexpected error updating widgets", e)
                }
            }
        }
    }
