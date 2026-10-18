package host.serv.djbigmuh.minutewatcher.service.tile

import android.service.quicksettings.Tile
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageTileStateTest {
    @Test
    fun `tile state constant maps active content to STATE_ACTIVE and inactive to STATE_INACTIVE without unavailable`() {
        val activeContent =
            TileContentResolver.resolve(
                sims =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "id_0",
                            operatorName = "MTS",
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "minutes_0",
                                            simSlot = 0,
                                            operatorName = "MTS",
                                            packageType = PackageType.MINUTES,
                                            limit = 100L,
                                            used = 50L,
                                            remaining = 50L,
                                            unit = MeasurementUnit.MINUTES,
                                            periodStart = 0L,
                                            periodEnd = 0L,
                                            lastUpdateTime = 0L,
                                            sourceType = DataSourceType.SYSTEM,
                                        ),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                noSimText = "No SIM",
                noDataText = "No Data",
                supportsSubtitle = true,
                operatorNameOf = { "MTS" },
                remainingTextOf = { "50 min" },
                formatOperatorValue = { op, v -> "$op: $v" },
            )
        assertEquals(Tile.STATE_ACTIVE, tileStateConstant(activeContent.isActive))

        val inactiveNoLimitContent =
            TileContentResolver.resolve(
                sims =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "id_0",
                            operatorName = "MTS",
                            entities = emptyMap(),
                            autoResetDay = 1,
                        ),
                    ),
                noSimText = "No SIM",
                noDataText = "No Data",
                supportsSubtitle = true,
                operatorNameOf = { "MTS" },
                remainingTextOf = { "0 min" },
                formatOperatorValue = { op, v -> "$op: $v" },
            )
        assertEquals(Tile.STATE_INACTIVE, tileStateConstant(inactiveNoLimitContent.isActive))

        val emptySimsContent =
            TileContentResolver.resolve(
                sims = emptyList(),
                noSimText = "No SIM",
                noDataText = "No Data",
                supportsSubtitle = true,
                operatorNameOf = { "" },
                remainingTextOf = { "" },
                formatOperatorValue = { op, v -> "$op: $v" },
            )
        assertEquals(Tile.STATE_INACTIVE, tileStateConstant(emptySimsContent.isActive))

        assertNotEquals(Tile.STATE_UNAVAILABLE, tileStateConstant(true))
        assertNotEquals(Tile.STATE_UNAVAILABLE, tileStateConstant(false))
    }
}
