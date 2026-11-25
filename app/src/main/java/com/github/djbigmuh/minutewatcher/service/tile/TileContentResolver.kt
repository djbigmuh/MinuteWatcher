package com.github.djbigmuh.minutewatcher.service.tile

import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot

/**
 * Presentation values resolved for the Quick Settings tile.
 *
 * All text fields are always populated in every branch so that applying
 * the content to a [android.service.quicksettings.Tile] never leaves a
 * stale value from a previous tile state.
 *
 * @property label Tile label text.
 * @property subtitle Tile subtitle text (applied on API 29+ only).
 * @property stateDescription Tile state description text (applied on API 30+ only).
 * @property contentDescription Tile content description text for accessibility.
 * @property isActive True when the tile shows remaining minutes of a SIM with a configured limit.
 */
internal data class TileContent(
    val label: String,
    val subtitle: String,
    val stateDescription: String,
    val contentDescription: String,
    val isActive: Boolean,
)

/**
 * Pure resolver for Quick Settings tile presentation values.
 *
 * Branches:
 * - No SIMs in the snapshot: every field carries the "no SIM" text, tile is inactive.
 * - Selected SIM has a minutes limit (limit > 0): subtitle, state description and
 *   content description carry the remaining-minutes text; label is the operator name,
 *   or a compact "operator: remaining" line when `supportsSubtitle` is false; tile is active.
 * - Selected SIM has no minutes limit: subtitle and state description carry the
 *   "no data" text, content description is "operator: no data"; tile is inactive.
 *
 * SIM selection is delegated to [TileSimSelector]; note that remaining == 0 with
 * limit == 0 means "no limit set", not "exhausted".
 *
 * @param sims SIM snapshots from the current global snapshot.
 * @param noSimText Localized "SIM cards not found" text.
 * @param noDataText Localized "no data" text.
 * @param supportsSubtitle True when the platform supports Tile subtitle (API 29+).
 * @param operatorNameOf Returns the localized short operator name for a SIM snapshot.
 * @param remainingTextOf Returns the localized remaining-minutes text (`remainingTextOf`) for a SIM snapshot.
 * @param formatOperatorValue Formats an "operator: value" pair via the localized format string.
 * @return Fully populated [TileContent].
 */
internal object TileContentResolver {
    fun resolve(
        sims: List<SimSnapshot>,
        noSimText: String,
        noDataText: String,
        supportsSubtitle: Boolean,
        operatorNameOf: (SimSnapshot) -> String,
        remainingTextOf: (SimSnapshot) -> String,
        formatOperatorValue: (String, String) -> String,
    ): TileContent {
        if (sims.isEmpty()) {
            return TileContent(
                label = noSimText,
                subtitle = noSimText,
                stateDescription = noSimText,
                contentDescription = noSimText,
                isActive = false,
            )
        }

        val selected = TileSimSelector.select(sims) ?: sims.minByOrNull { it.slot } ?: sims.first()
        val operatorName = operatorNameOf(selected)
        val minutes = selected.entities[PackageType.MINUTES]

        return if (minutes != null && minutes.limit > 0) {
            val remainingText = remainingTextOf(selected)
            TileContent(
                label = if (supportsSubtitle) operatorName else formatOperatorValue(operatorName, remainingText),
                subtitle = remainingText,
                stateDescription = remainingText,
                contentDescription = formatOperatorValue(operatorName, remainingText),
                isActive = true,
            )
        } else {
            TileContent(
                label = operatorName,
                subtitle = noDataText,
                stateDescription = noDataText,
                contentDescription = formatOperatorValue(operatorName, noDataText),
                isActive = false,
            )
        }
    }
}
