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
    fun `hash remains same when only global timestamp changes`() {
        val s1 = createSnapshot(remaining = 90, timestamp = 1000L)
        val s2 = createSnapshot(remaining = 90, timestamp = 2000L)

        assertEquals(SnapshotUtils.calculateContentHash(s1), SnapshotUtils.calculateContentHash(s2))
    }

    private fun createSnapshot(
        remaining: Long,
        limit: Long = 1000,
        used: Long = 0,
        periodEnd: Long = 0,
        lastUpdateTime: Long = 0,
        timestamp: Long = 0,
    ): GlobalSnapshot {
        val entity =
            SemanticEntity(
                id = "test",
                simSlot = 0,
                operatorName = "ABC",
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
        val sim = SimSnapshot(0, "ABC", "ABC", true, mapOf(PackageType.MINUTES to entity), autoResetDay = 1)
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timestamp,
            simSnapshots = listOf(sim),
            capabilities = emptyList(),
        )
    }
}
