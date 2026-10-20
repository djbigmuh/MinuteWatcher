package host.serv.djbigmuh.minutewatcher.core.data.semantic

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.model.AppCapability
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides capabilities supported by the app.
 *
 * AppSearch indexing uses PlatformStorage on Android 12+ (API 31+) for system search integration,
 * and bundled LocalStorage on Android 8.0–11 (API 26–30) as a private app index.
 * Actual runtime availability is determined at runtime; if indexing fails on device,
 * the status is set to NOT_SUPPORTED, and an error banner is not displayed for this type.
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
