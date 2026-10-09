package com.example.minutewatcher.feature.semantic.appfunctions

import android.os.Build
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppFunctionAvailability
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AppFunctionAvailabilityImpl
    @Inject
    constructor(
        private val gateway: AppFunctionStateGateway,
    ) : AppFunctionAvailability {
        /**
         * Sets assistant access enabled status.
         * Below the supported API level, the call is a no-op.
         * Platform errors are logged and not thrown.
         * [CancellationException] is always propagated.
         */
        override suspend fun setAssistantAccessEnabled(enabled: Boolean) {
            if (!isAppFunctionApiAvailable()) {
                return
            }
            try {
                gateway.setAppFunctionEnabled(
                    MinuteWatcherAppFunctionService.FUNCTION_ID_GET_USAGE_SUMMARY,
                    enabled,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(
                    "AppFunctionAvailabilityImpl: Failed to set assistant access enabled = $enabled",
                    e,
                )
            }
        }

        private fun isAppFunctionApiAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
    }
