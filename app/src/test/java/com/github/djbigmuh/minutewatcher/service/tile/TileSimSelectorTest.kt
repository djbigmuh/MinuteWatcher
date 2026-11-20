package com.github.djbigmuh.minutewatcher.service.tile

import com.github.djbigmuh.minutewatcher.core.model.DataSourceType
import com.github.djbigmuh.minutewatcher.core.model.MeasurementUnit
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SemanticEntity
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileSimSelectorTest {
    @Test
    fun `select picks SIM with limit over SIM without limit`() {
        // SIM1: limit=0, remaining=0
        val sim1 = createSim(slot = 0, limit = 0, remaining = 0)
        // SIM2: limit=300, remaining=50
        val sim2 = createSim(slot = 1, limit = 300, remaining = 50)

        val result = TileSimSelector.select(listOf(sim1, sim2))
        assertEquals(sim2, result)
    }

    @Test
    fun `select returns null if no SIM has limit`() {
        val sim1 = createSim(slot = 0, limit = 0, remaining = 0)
        val sim2 = createSim(slot = 1, limit = -1, remaining = 0)

        val result = TileSimSelector.select(listOf(sim1, sim2))
        assertNull(result)
    }

    @Test
    fun `select picks minimum remaining among candidates`() {
        val sim1 = createSim(slot = 0, limit = 100, remaining = 50)
        val sim2 = createSim(slot = 1, limit = 100, remaining = 20)
        val sim3 = createSim(slot = 2, limit = 100, remaining = 80)

        val result = TileSimSelector.select(listOf(sim1, sim2, sim3))
        assertEquals(sim2, result)
    }

    @Test
    fun `select uses slot as tie-break`() {
        val sim1 = createSim(slot = 1, limit = 100, remaining = 50)
        val sim2 = createSim(slot = 0, limit = 100, remaining = 50)

        val result = TileSimSelector.select(listOf(sim1, sim2))
        assertEquals(sim2, result)
    }

    @Test
    fun `select returns null for empty list`() {
        val result = TileSimSelector.select(emptyList())
        assertNull(result)
    }

    private fun createSim(
        slot: Int,
        limit: Int,
        remaining: Int,
    ) = SimSnapshot(
        slot = slot,
        identity = "sim_$slot",
        operatorName = "Carrier $slot",
        entities =
            mapOf(
                PackageType.MINUTES to
                    SemanticEntity(
                        id = "entity_$slot",
                        simSlot = slot,
                        operatorName = "Carrier $slot",
                        packageType = PackageType.MINUTES,
                        limit = limit.toLong(),
                        used = (limit - remaining).toLong(),
                        remaining = remaining.toLong(),
                        unit = MeasurementUnit.MINUTES,
                        periodStart = 0L,
                        periodEnd = 0L,
                        lastUpdateTime = 0L,
                        sourceType = DataSourceType.SYSTEM,
                    ),
            ),
        autoResetDay = 1,
    )
}
