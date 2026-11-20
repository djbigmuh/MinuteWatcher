package com.github.djbigmuh.minutewatcher.core.model

/**
 * Unique keys for identifying tracking components and their statuses.
 */
enum class TrackingKey(
    val id: String,
) {
    TRAFFIC("traffic"),
    CALLS("calls"),
    APPSEARCH("appsearch"),
    SHORTCUTS("shortcuts"),
    WIDGET_OBSERVATION("widget_observation"),
    APP_SETTINGS("app_settings"),
    SIM_DISCOVERY("sim_discovery"),
    USAGE_STATE("usage_state"),
}
