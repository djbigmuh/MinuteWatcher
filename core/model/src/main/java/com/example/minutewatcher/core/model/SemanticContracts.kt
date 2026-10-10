package com.example.minutewatcher.core.model

/**
 * Service package types.
 */
enum class PackageType {
    MINUTES,
    DATA,
}

/**
 * Measurement units.
 */
enum class MeasurementUnit {
    MINUTES,
    BYTES,
}

/**
 * Data source types.
 */
enum class DataSourceType {
    SYSTEM,
    MANUAL,
}

/**
 * Semantic entity of a service package.
 */
data class SemanticEntity(
    val id: String,
    val simSlot: Int,
    val operatorName: String,
    val packageType: PackageType,
    val limit: Long,
    val used: Long,
    val remaining: Long,
    val unit: MeasurementUnit,
    val periodStart: Long,
    val periodEnd: Long,
    val lastUpdateTime: Long,
    val sourceType: DataSourceType,
)

/**
 * Description of application capabilities.
 * Reserved for future extensibility and interaction with external data consumers (e.g., AI agents).
 */
data class AppCapability(
    val type: String,
    val isSupported: Boolean,
    val description: String,
)

/**
 * Version of the semantic schema.
 * Used to ensure backward compatibility when updating data exchange protocols.
 */
data class SchemaVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Full snapshot of a SIM card's state.
 */
data class SimSnapshot(
    val slot: Int,
    val identity: String,
    val operatorName: String,
    val entities: Map<PackageType, SemanticEntity>,
    val autoResetDay: Int,
    /** Whether automatic statistics reset is enabled for this SIM. */
    val autoReset: Boolean = false,
)

/**
 * Global snapshot of the application state.
 */
data class GlobalSnapshot(
    val version: SchemaVersion,
    val timestamp: Long,
    val simSnapshots: List<SimSnapshot>,
    val capabilities: List<AppCapability>,
    val isManualUpdate: Boolean = false,
    val suppressWidgetUpdate: Boolean = false,
    /** Monotonic assembly counter. NOT part of content-hash and NOT indexed. */
    val revision: Long = 0L,
)
