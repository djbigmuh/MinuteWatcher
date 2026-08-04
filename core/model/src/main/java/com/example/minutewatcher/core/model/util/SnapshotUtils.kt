package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.GlobalSnapshot

/**
 * Utility for GlobalSnapshot processing.
 */
object SnapshotUtils {
    /**
     * Calculates a content-based hash to detect meaningful changes,
     * ignoring timestamps and transient fields.
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
                            sim.entities.values.map { e ->
                                // Explicitly list fields to exclude lastUpdateTime
                                listOf(
                                    e.id,
                                    e.simSlot,
                                    e.operatorName,
                                    e.packageType,
                                    e.limit,
                                    e.used,
                                    e.remaining,
                                    e.unit,
                                    e.periodStart,
                                    e.periodEnd,
                                    e.sourceType,
                                )
                            }
                    )
                }

        result = 31 * result + content.hashCode()
        return result
    }
}
