package host.serv.djbigmuh.minutewatcher.service.tile

import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileContentResolverTest {
    private fun minutesEntity(
        slot: Int,
        limit: Long,
        remaining: Long,
    ) = SemanticEntity(
        id = "sim_${slot}_minutes",
        simSlot = slot,
        operatorName = "Operator $slot",
        packageType = PackageType.MINUTES,
        limit = limit,
        used = 0L,
        remaining = remaining,
        unit = MeasurementUnit.MINUTES,
        periodStart = 0L,
        periodEnd = 0L,
        lastUpdateTime = 0L,
        sourceType = DataSourceType.SYSTEM,
    )

    private fun sim(
        slot: Int,
        limit: Long,
        remaining: Long,
    ) = SimSnapshot(
        slot = slot,
        identity = "identity_$slot",
        operatorName = "Operator $slot",
        entities = mapOf(PackageType.MINUTES to minutesEntity(slot, limit, remaining)),
        autoResetDay = 1,
    )

    private fun resolve(
        sims: List<SimSnapshot>,
        supportsSubtitle: Boolean = true,
    ): TileContent =
        TileContentResolver.resolve(
            sims = sims,
            noSimText = NO_SIM,
            noDataText = NO_DATA,
            supportsSubtitle = supportsSubtitle,
            operatorNameOf = { "OP${it.slot}" },
            remainingTextOf = { "${it.entities[PackageType.MINUTES]?.remaining} min" },
            formatOperatorValue = { operator, value -> "$operator: $value" },
        )

    @Test
    fun `empty snapshot populates every tile text field with no-sim text`() {
        val content = resolve(sims = emptyList())

        assertEquals(NO_SIM, content.label)
        assertEquals(NO_SIM, content.subtitle)
        assertEquals(NO_SIM, content.stateDescription)
        assertEquals(NO_SIM, content.contentDescription)
        assertFalse(content.isActive)
    }

    @Test
    fun `sim without minutes limit populates subtitle and state description with no-data text`() {
        val content = resolve(sims = listOf(sim(slot = 0, limit = 0L, remaining = 0L)))

        assertEquals("OP0", content.label)
        assertEquals(NO_DATA, content.subtitle)
        assertEquals(NO_DATA, content.stateDescription)
        assertEquals("OP0: $NO_DATA", content.contentDescription)
        assertFalse(content.isActive)
    }

    @Test
    fun `sim with minutes limit resolves active content with remaining text in every description field`() {
        val content = resolve(sims = listOf(sim(slot = 0, limit = 500L, remaining = 120L)))

        assertEquals("OP0", content.label)
        assertEquals("120 min", content.subtitle)
        assertEquals("120 min", content.stateDescription)
        assertEquals("OP0: 120 min", content.contentDescription)
        assertTrue(content.isActive)
    }

    @Test
    fun `compact label combines operator and remaining when subtitle is unsupported`() {
        val content =
            resolve(
                sims = listOf(sim(slot = 0, limit = 500L, remaining = 120L)),
                supportsSubtitle = false,
            )

        assertEquals("OP0: 120 min", content.label)
        assertEquals("120 min", content.stateDescription)
        assertTrue(content.isActive)
    }

    @Test
    fun `selection prefers sim with minimum remaining minutes among sims with limits`() {
        val content =
            resolve(
                sims =
                    listOf(
                        sim(slot = 0, limit = 500L, remaining = 300L),
                        sim(slot = 1, limit = 400L, remaining = 40L),
                    ),
            )

        assertEquals("OP1", content.label)
        assertEquals("40 min", content.stateDescription)
        assertEquals("OP1: 40 min", content.contentDescription)
        assertTrue(content.isActive)
    }

    @Test
    fun `fallback without minutes limit selects sim with minimum slot from unordered list`() {
        val content =
            resolve(
                sims =
                    listOf(
                        sim(slot = 2, limit = 0L, remaining = 0L),
                        sim(slot = 0, limit = 0L, remaining = 0L),
                        sim(slot = 1, limit = 0L, remaining = 0L),
                    ),
            )

        assertEquals("OP0", content.label)
        assertEquals(NO_DATA, content.subtitle)
        assertFalse(content.isActive)
    }

    private companion object {
        private const val NO_SIM = "NO_SIM"
        private const val NO_DATA = "NO_DATA"
    }
}
