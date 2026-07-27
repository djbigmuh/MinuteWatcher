package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
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
                    AppLogger.e("Widget update failed: illegal argument (possible PendingIntent issue)", e)
                } catch (e: Exception) {
                    AppLogger.e("Error updating widgets", e)
                }
            }
        }
    }
