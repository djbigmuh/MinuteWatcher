package com.example.minutewatcher.feature.semantic.search

import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SnapshotHashCalculatorTest {

    @Test
    fun `hash changes when remaining changes`() {
        val s1 = createSnapshot(remaining = 100, limit = 1000, used = 900)
        val s2 = createSnapshot(remaining = 99, limit = 1000, used = 901)

        assertNotEquals(SnapshotHashCalculator.calculate(s1), SnapshotHashCalculator.calculate(s2))
    }

    @Test
    fun `hash should change when limit changes even if remaining is same`() {
        val s1 = createSnapshot(remaining = 90, limit = 100, used = 10)
        val s2 = createSnapshot(remaining = 90, limit = 200, used = 110)

        // Currently this fails (hashes are equal)
        assertNotEquals("Hash should change when limit changes", 
            SnapshotHashCalculator.calculate(s1), SnapshotHashCalculator.calculate(s2))
    }

    @Test
    fun `hash should change when used changes even if remaining is same`() {
        // This is practically impossible if remaining = limit - used, but good to test for robustness
        val s1 = createSnapshot(remaining = 90, limit = 100, used = 10)
        val s2 = createSnapshot(remaining = 90, limit = 100, used = 11)

        assertNotEquals("Hash should change when used changes", 
            SnapshotHashCalculator.calculate(s1), SnapshotHashCalculator.calculate(s2))
    }

    @Test
    fun `hash should change when periodEnd changes`() {
        val s1 = createSnapshot(remaining = 90, periodEnd = 1000L)
        val s2 = createSnapshot(remaining = 90, periodEnd = 2000L)

        assertNotEquals("Hash should change when periodEnd changes", 
            SnapshotHashCalculator.calculate(s1), SnapshotHashCalculator.calculate(s2))
    }

    private fun createSnapshot(
        remaining: Long,
        limit: Long = 1000,
        used: Long = 0,
        periodEnd: Long = 0
    ): GlobalSnapshot {
        val entity = SemanticEntity(
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
            lastUpdateTime = 0,
            sourceType = DataSourceType.SYSTEM,
        )
        val sim = SimSnapshot(0, "ABC", true, mapOf(PackageType.MINUTES to entity))
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 0,
            simSnapshots = listOf(sim),
            capabilities = emptyList()
        )
    }
}
