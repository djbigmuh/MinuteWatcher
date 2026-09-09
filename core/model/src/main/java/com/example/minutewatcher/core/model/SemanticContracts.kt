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
)

/**
 * Описание возможностей приложения.
 * Зарезервировано для будущей расширяемости и взаимодействия с внешними потребителями данных (например, AI-агентами).
 */
data class AppCapability(
    val type: String,
    val isSupported: Boolean,
    val description: String,
)

/**
 * Версия семантической схемы.
 * Используется для обеспечения обратной совместимости при обновлении протоколов обмена данными.
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
    val suppressWidgetUpdate: Boolean = false,
)
