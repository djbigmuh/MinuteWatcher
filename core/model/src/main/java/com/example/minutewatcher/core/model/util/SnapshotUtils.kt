package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.GlobalSnapshot

/**
 * Utility for GlobalSnapshot processing.
 */
object SnapshotUtils {
    /**
     * Calculates the content hash of a snapshot to detect significant data changes.
     *
     * The hash includes only fields that affect visual presentation or widget logic:
     * - Limits, used, and remaining values (minutes/bytes).
     * - Package validity periods.
     * - Data source type (system/manual).
     * - Auto-reset settings (day of month).
     *
     * Excluded:
     * - Timestamps (lastUpdateTime, timestamp) — to avoid infinite update loops.
     * - Operator name — to avoid unnecessary widget redraws during metadata updates,
     *   if numeric indicators have not changed.
     * - [GlobalSnapshot.revision] is intentionally excluded as it's a technical counter for refresh().
     */
    fun calculateContentHash(snapshot: GlobalSnapshot): Int {
        var result = snapshot.version.hashCode()
        result = HASH_MULTIPLIER * result + snapshot.capabilities.hashCode()

        val content =
            snapshot.simSnapshots
                .sortedBy { it.slot }
                .map { sim ->
                    sim.slot to (
                        sim.autoResetDay to
                            sim.entities.values
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

        result = HASH_MULTIPLIER * result + content.hashCode()
        return result
    }

    /**
     * Standard prime multiplier used for hashing algorithms.
     */
    private const val HASH_MULTIPLIER = 31
}
