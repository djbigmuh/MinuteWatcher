package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot

/**
 * Utility for GlobalSnapshot processing.
 */
object SnapshotUtils {
    /**
     * Calculates a content-based hash to detect meaningful changes,
     * ignoring timestamps and transient fields.
     */
    fun calculateContentHash(snapshot: GlobalSnapshot): Int {
        var result = (snapshot.version as Any?).hashCode()
        result = 31 * result + (snapshot.capabilities as Any?).hashCode()

        val content =
            (snapshot.simSnapshots as List<SimSnapshot>?)
                ?.filter { (it as Any?) != null && it.isActive }
                ?.sortedBy { it.slot }
                ?.map { sim ->
                    sim.slot to (
                        sim.autoResetDay to
                            (sim.entities as Map<*, SemanticEntity>?)?.values?.filterNotNull()?.map { entity ->
                                // Explicitly list fields to exclude lastUpdateTime
                                listOf(
                                    entity.id,
                                    entity.simSlot,
                                    entity.operatorName,
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
