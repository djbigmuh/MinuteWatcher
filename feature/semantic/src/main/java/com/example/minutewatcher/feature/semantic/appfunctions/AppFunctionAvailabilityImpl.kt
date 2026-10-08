package com.example.minutewatcher.feature.semantic.appfunctions

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppFunctionAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AppFunctionAvailabilityImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : AppFunctionAvailability {
    override suspend fun setAssistantAccessEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return
        }
        try {
            setAssistantAccessEnabledInternal(enabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("AppFunctionAvailabilityImpl: Failed to set assistant access enabled = $enabled", e)
        }
    }

    @RequiresApi(36)
    private suspend fun setAssistantAccessEnabledInternal(enabled: Boolean) {
        val manager = AppFunctionManager.getInstance(context)
        if (manager == null) {
            AppLogger.w("AppFunctionAvailabilityImpl: AppFunctionManager is null")
            return
        }
        val state =
            if (enabled) {
                AppFunctionManager.APP_FUNCTION_STATE_ENABLED
            } else {
                AppFunctionManager.APP_FUNCTION_STATE_DISABLED
            }
        manager.setAppFunctionEnabled(
            "com.example.minutewatcher.feature.semantic.appfunctions.MinuteWatcherAppFunctionService#getUsageSummary",
            state,
        )
    }
}
