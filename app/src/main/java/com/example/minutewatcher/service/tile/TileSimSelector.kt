package com.example.minutewatcher.service.tile

import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimSnapshot

/**
 * Logic for selecting the most relevant SIM to display in the Quick Settings Tile.
 */
internal object TileSimSelector {
    /**
     * Selects a SIM from the list of snapshots.
     *
     * Rule:
     * 1. Candidates are SIMs with a defined minutes limit (limit > 0).
     *    Note: remaining == 0 when limit == 0 means "no limit set", not "exhausted".
     * 2. From candidates, picks the one with minimum remaining minutes.
     * 3. Tie-break: smaller slot index.
     * 4. If no candidates exist, returns null.
     */
    fun select(sims: List<SimSnapshot>): SimSnapshot? {
        val candidates =
            sims.filter {
                val minutes = it.entities[PackageType.MINUTES]
                minutes != null && minutes.limit > 0
            }

        return candidates.minWithOrNull(
            compareBy<SimSnapshot> {
                it.entities[PackageType.MINUTES]?.remaining ?: Long.MAX_VALUE
            }.thenBy { it.slot },
        )
    }
}
