package com.github.djbigmuh.minutewatcher.core.model

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
 *
 * @property id Unique identifier of the entity.
 * @property simSlot SIM slot index.
 * @property operatorName Operator name string.
 * @property packageType Service package type.
 * @property limit Service limit value.
 * @property used Used service amount.
 * @property remaining Remaining service amount.
 * @property unit Measurement unit for the service.
 * @property periodStart Start timestamp of the billing period.
 * @property periodEnd End timestamp of the billing period.
 * @property lastUpdateTime Timestamp of the last update.
 * @property sourceType Data source type.
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
 *
 * @property type Capability type identifier.
 * @property isSupported Whether the capability is supported on device.
 * @property description Human-readable description of the capability.
 */
data class AppCapability(
    val type: String,
    val isSupported: Boolean,
    val description: String,
)

/**
 * Version of the semantic schema.
 *
 * @property major Major version number.
 * @property minor Minor version number.
 * @property patch Patch version number.
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
 *
 * @property slot SIM slot index.
 * @property identity Stable SIM identity.
 * @property operatorName Operator name string.
 * @property entities Map of package types to semantic entities.
 * @property autoResetDay Auto-reset day of month.
 * @property autoReset Whether automatic statistics reset is enabled for this SIM.
 */
data class SimSnapshot(
    val slot: Int,
    val identity: String,
    val operatorName: String,
    val entities: Map<PackageType, SemanticEntity>,
    val autoResetDay: Int,
    /** Whether automatic statistics reset is enabled for this SIM. */
    val autoReset: Boolean = false,
    val brandKey: String? = null,
)

/**
 * Global snapshot of the application state.
 *
 * @property version Semantic schema version.
 * @property timestamp Snapshot creation timestamp.
 * @property simSnapshots List of SIM card snapshots.
 * @property capabilities List of application capabilities.
 * @property isManualUpdate Whether this is a manual update snapshot.
 * @property suppressWidgetUpdate Whether widget updates are suppressed.
 * @property revision Monotonic assembly counter.
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
