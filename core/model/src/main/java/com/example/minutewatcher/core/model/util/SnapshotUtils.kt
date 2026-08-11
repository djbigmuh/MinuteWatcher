package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.GlobalSnapshot

/**
 * Utility for GlobalSnapshot processing.
 */
object SnapshotUtils {
    /**
     * Вычисляет хэш содержимого снапшота для обнаружения значимых изменений данных.
     *
     * В хэш включаются только поля, влияющие на визуальное представление или логику работы виджетов:
     * - Лимиты, использованные и оставшиеся значения (минуты/байты).
     * - Периоды действия пакетов.
     * - Тип источника данных (системный/ручной).
     * - Настройки автосброса (день месяца).
     *
     * Исключаются:
     * - Временные метки (lastUpdateTime, timestamp) — чтобы избежать бесконечных циклов обновления.
     * - Имя оператора — чтобы избежать лишних перерисовок виджета при обновлении метаданных,
     *   если числовые показатели не изменились.
     */
    fun calculateContentHash(snapshot: GlobalSnapshot): Int {
        var result = snapshot.version.hashCode()
        result = 31 * result + snapshot.capabilities.hashCode()

        val content =
            snapshot.simSnapshots
                .filter { it.isActive }
                .sortedBy { it.slot }
                .map { sim ->
                    sim.slot to (
                        sim.autoResetDay to
                            sim.entities.values
                                .filterNotNull()
                                .sortedBy { it.packageType }
                                .map { entity ->
                                    // Исключаем operatorName и lastUpdateTime для стабильности хэша
                                    listOf(
                                        entity.id,
                                        entity.simSlot,
                                        entity.packageType,
                                        entity.limit,
                                        entity.used,
                                        entity.remaining,
                                        entity.unit,
                                        entity.periodStart,
                                        entity.periodEnd,
                                        entity.sourceType,
                                    )
                                }
                    )
                }

        result = 31 * result + content.hashCode()
        return result
    }
}
