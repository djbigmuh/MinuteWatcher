package com.example.minutewatcher.core.data.semantic

import android.content.Context
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.model.AppCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides capabilities supported by the app.
 *
 * AppSearch indexing uses the bundled LocalStorage backend which is available
 * across all supported API levels. Actual runtime availability is handled at runtime;
 * if indexing fails, the device is assigned NOT_SUPPORTED error status.
 */
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
                AppCapability("APP_SEARCH", true, "System search indexing via AppSearch"),
                AppCapability("DYNAMIC_SHORTCUTS", true, "Dynamic shortcuts with usage info"),
                AppCapability("QUICK_SETTINGS_TILE", true, "Quick Settings Tile for rapid check"),
            )
        }

        private companion object {
            private const val FEATURE_TELEPHONY_MULTISIM = "android.hardware.telephony.multisim"
        }
    }
