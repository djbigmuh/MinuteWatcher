package com.example.minutewatcher.feature.semantic.search

import com.example.minutewatcher.core.model.GlobalSnapshot
import java.util.Objects

/**
 * Calculates a content-based hash for a GlobalSnapshot to detect meaningful changes.
 */
internal object SnapshotHashCalculator {
    fun calculate(snapshot: GlobalSnapshot): Int {
        // Only hash components that change the indexed content
        val entityStates =
            snapshot.simSnapshots
                .filter { it.isActive }
                .map { sim ->
                    sim.slot to
                        sim.entities.mapValues { (_, e) ->
                            Triple(e.operatorName, e.remaining, e.unit)
                        }
                }
        return Objects.hash(entityStates)
    }
}
