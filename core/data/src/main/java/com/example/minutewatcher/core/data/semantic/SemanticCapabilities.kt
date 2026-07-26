package com.example.minutewatcher.core.data.semantic

import com.example.minutewatcher.core.model.AppCapability
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticCapabilities
    @Inject
    constructor() {
        fun getCapabilities(): List<AppCapability> =
            listOf(
                AppCapability("MINUTES_TRACKING", true, "Support for voice calls tracking"),
                AppCapability("DATA_TRACKING", true, "Support for mobile data usage tracking"),
                AppCapability("DUAL_SIM", true, "Support for multiple SIM cards"),
                AppCapability("APP_SEARCH", true, "System search indexing via AppSearch"),
                AppCapability("DYNAMIC_SHORTCUTS", true, "Dynamic shortcuts with usage info"),
                AppCapability("QUICK_SETTINGS_TILE", true, "Quick Settings Tile for rapid check"),
            )
    }
