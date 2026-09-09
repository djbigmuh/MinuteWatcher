package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SnapshotUtilsTest {
    @Test
    fun `hash changes when remaining changes`() {
        val s1 = createSnapshot(remaining = 100, limit = 1000, used = 900)
        val s2 = createSnapshot(remaining = 99, limit = 1000, used = 901)

        assertNotEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash changes when limit changes`() {
        val s1 = createSnapshot(remaining = 90, limit = 100, used = 10)
        val s2 = createSnapshot(remaining = 90, limit = 200, used = 110)

        assertNotEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash changes when used changes`() {
        val s1 = createSnapshot(remaining = 90, limit = 100, used = 10)
        val s2 = createSnapshot(remaining = 90, limit = 100, used = 11)

        assertNotEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash changes when periodEnd changes`() {
        val s1 = createSnapshot(remaining = 90, periodEnd = 1000L)
        val s2 = createSnapshot(remaining = 90, periodEnd = 2000L)

        assertNotEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash remains same when only lastUpdateTime changes`() {
        val s1 = createSnapshot(remaining = 90, lastUpdateTime = 1000L)
        val s2 = createSnapshot(remaining = 90, lastUpdateTime = 2000L)

        assertEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash remains same when only operatorName changes`() {
        val s1 = createSnapshot(remaining = 90, operatorName = "Operator A")
        val s2 = createSnapshot(remaining = 90, operatorName = "Operator B")

        assertEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash remains same when only global timestamp changes`() {
        val s1 = createSnapshot(remaining = 90, timestamp = 1000L)
        val s2 = createSnapshot(remaining = 90, timestamp = 2000L)

        assertEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash is stable regardless of simSnapshots order`() {
        val sim0 = createSimSnapshot(slot = 0, remaining = 100)
        val sim1 = createSimSnapshot(slot = 1, remaining = 200)

        val s1 =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0,
                simSnapshots = listOf(sim0, sim1),
                capabilities = emptyList(),
            )
        val s2 =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0,
                simSnapshots = listOf(sim1, sim0),
                capabilities = emptyList(),
            )

        assertEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    @Test
    fun `hash is stable regardless of entities map iteration order`() {
        val e1 = createEntity(id = "min", type = PackageType.MINUTES)
        val e2 = createEntity(id = "data", type = PackageType.DATA)

        // LinkedHashMap preserves insertion order
        val map1 = linkedMapOf(PackageType.MINUTES to e1, PackageType.DATA to e2)
        val map2 = linkedMapOf(PackageType.DATA to e2, PackageType.MINUTES to e1)

        val sim1 = SimSnapshot(0, "ABC", "ABC", map1, autoResetDay = 1)
        val sim2 = SimSnapshot(0, "ABC", "ABC", map2, autoResetDay = 1)

        val s1 =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0,
                simSnapshots = listOf(sim1),
                capabilities = emptyList(),
            )
        val s2 =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0,
                simSnapshots = listOf(sim2),
                capabilities = emptyList(),
            )

        assertEquals(
            "Hash should be same regardless of map insertion order",
            SnapshotUtils.calculateContentHash(s1),
            SnapshotUtils.calculateContentHash(s2),
        )
    }

    @Test
    fun `hash changes when autoResetDay changes`() {
        val s1 = createSnapshot(remaining = 90)
        // Default autoResetDay is 1 in createSnapshot helper

        val entity = s1.simSnapshots[0].entities[PackageType.MINUTES]!!
        val sim2 = SimSnapshot(0, "ABC", "ABC", mapOf(PackageType.MINUTES to entity), autoResetDay = 2)
        val s2 = s1.copy(simSnapshots = listOf(sim2))

        assertNotEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    private fun createEntity(
        id: String,
        type: PackageType,
    ): SemanticEntity =
        SemanticEntity(
            id = id,
            simSlot = 0,
            operatorName = "ABC",
            packageType = type,
            limit = 1000,
            used = 0,
            remaining = 100,
            unit = MeasurementUnit.MINUTES,
            periodStart = 0,
            periodEnd = 0,
            lastUpdateTime = 0,
            sourceType = DataSourceType.SYSTEM,
        )

    private fun createSimSnapshot(
        slot: Int,
        remaining: Long,
        operatorName: String = "ABC",
    ): SimSnapshot {
        val entity =
            SemanticEntity(
                id = "test_$slot",
                simSlot = slot,
                operatorName = operatorName,
                packageType = PackageType.MINUTES,
                limit = 1000,
                used = 0,
                remaining = remaining,
                unit = MeasurementUnit.MINUTES,
                periodStart = 0,
                periodEnd = 0,
                lastUpdateTime = 0,
                sourceType = DataSourceType.SYSTEM,
            )
        return SimSnapshot(slot, "ABC", operatorName, mapOf(PackageType.MINUTES to entity), autoResetDay = 1)
    }

    private fun createSnapshot(
        remaining: Long,
        limit: Long = 1000,
        used: Long = 0,
        periodEnd: Long = 0,
        lastUpdateTime: Long = 0,
        timestamp: Long = 0,
        operatorName: String = "ABC",
    ): GlobalSnapshot {
        val entity =
            SemanticEntity(
                id = "test",
                simSlot = 0,
                operatorName = operatorName,
                packageType = PackageType.MINUTES,
                limit = limit,
                used = used,
                remaining = remaining,
                unit = MeasurementUnit.MINUTES,
                periodStart = 0,
                periodEnd = periodEnd,
                lastUpdateTime = lastUpdateTime,
                sourceType = DataSourceType.SYSTEM,
            )
        val sim = SimSnapshot(0, "ABC", operatorName, mapOf(PackageType.MINUTES to entity), autoResetDay = 1)
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timestamp,
            simSnapshots = listOf(sim),
            capabilities = emptyList(),
        )
    }
}
