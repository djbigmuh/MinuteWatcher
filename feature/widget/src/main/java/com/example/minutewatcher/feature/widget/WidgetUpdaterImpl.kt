package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
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
    ) : WidgetUpdater {
        override fun updateAll() {
            scope.launch {
                try {
                    MinutesGlanceWidget().updateAll(context)
                } catch (e: IllegalArgumentException) {
                    AppLogger.e("Widget update failed: invalid arguments (possible PendingIntent issue)", e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Unexpected error updating widgets", e)
                }
            }
        }
    }
