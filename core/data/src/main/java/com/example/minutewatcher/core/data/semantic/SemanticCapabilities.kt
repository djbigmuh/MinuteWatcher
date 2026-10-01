package com.example.minutewatcher.core.data.semantic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.model.AppCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticCapabilities
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val permissionManager: PermissionManager,
    ) {
        fun getCapabilities(): List<AppCapability> {
            val telephonySupported = permissionManager.isTelephonySupported()
            val multiSimSupported = context.packageManager.hasSystemFeature(FEATURE_TELEPHONY_MULTISIM)

            return listOf(
                AppCapability("MINUTES_TRACKING", telephonySupported, "Support for voice calls tracking"),
                AppCapability("DATA_TRACKING", telephonySupported, "Support for mobile data usage tracking"),
                AppCapability("DUAL_SIM", multiSimSupported, "Support for multiple SIM cards"),
                AppCapability("APP_SEARCH", isAppSearchSupported(), "System search indexing via AppSearch"),
                AppCapability("DYNAMIC_SHORTCUTS", true, "Dynamic shortcuts with usage info"),
                AppCapability("QUICK_SETTINGS_TILE", true, "Quick Settings Tile for rapid check"),
            )
        }

        /**
         * Returns true if AppSearch is likely supported on this device.
         *
         * On API 31+, we check for the presence of the system AppSearch service.
         * On older versions (26+), we rely on bundled LocalStorage which is available on all
         * supported API levels.
         *
         * Suppress WrongConstant as Context.APP_SEARCH_SERVICE is guarded by API 31 check.
         */
        @SuppressLint("WrongConstant")
        private fun isAppSearchSupported(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(Context.APP_SEARCH_SERVICE) != null
            } else {
                // LocalStorage fallback for older versions (26+)
                true
            }

        private companion object {
            private const val FEATURE_TELEPHONY_MULTISIM = "android.hardware.telephony.multisim"
        }
    }
