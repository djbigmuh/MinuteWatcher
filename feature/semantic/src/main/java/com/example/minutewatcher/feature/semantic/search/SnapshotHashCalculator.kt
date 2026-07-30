package com.example.minutewatcher.feature.semantic.search

import com.example.minutewatcher.core.model.GlobalSnapshot

/**
 * Calculates a content-based hash for a GlobalSnapshot to detect meaningful changes.
 */
internal object SnapshotHashCalculator {
    fun calculate(snapshot: GlobalSnapshot): Int {
        val items = snapshot.simSnapshots
            .filter { it.isActive }
            .flatMap { sim ->
                sim.entities.values.map { e ->
                    listOf(
                        sim.slot,
                        e.packageType,
                        e.operatorName,
                        e.used,
                        e.limit,
                        e.remaining,
                        e.unit,
                        e.periodEnd,
                        e.sourceType
                    )
                }
            }

        return items.hashCode()
    }
}
