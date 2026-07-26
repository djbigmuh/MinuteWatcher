package com.example.minutewatcher.core.model

/**
 * Типы пакетов услуг.
 */
enum class PackageType {
    MINUTES,
    DATA,
}

/**
 * Единицы измерения.
 */
enum class MeasurementUnit {
    MINUTES,
    BYTES,
}

/**
 * Источники происхождения данных.
 */
enum class DataSourceType {
    SYSTEM,
    MANUAL,
}

/**
 * Семантическая сущность пакета услуг.
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
    val isStale: Boolean,
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Описание возможностей приложения.
 */
data class AppCapability(
    val type: String,
    val isSupported: Boolean,
    val description: String,
)

/**
 * Версия семантической схемы.
 */
data class SchemaVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Полный снимок состояния SIM-карты.
 */
data class SimSnapshot(
    val slot: Int,
    val operator: String,
    val isActive: Boolean,
    val entities: Map<PackageType, SemanticEntity>,
)

/**
 * Глобальный снимок состояния приложения.
 */
data class GlobalSnapshot(
    val version: SchemaVersion,
    val timestamp: Long,
    val simSnapshots: List<SimSnapshot>,
    val capabilities: List<AppCapability>,
    val isManualUpdate: Boolean = false,
)
