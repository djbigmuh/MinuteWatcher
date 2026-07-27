package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerTest {
    private val context: Context = mock()
    private lateinit var manager: SemanticSearchManager

    @Before
    fun setup() {
        // We'll initialize manager inside tests to use the runTest's scope
    }

    @Test
    fun `initialize transitions status to Active`() =
        runTest {
            manager = SemanticSearchManager(context, backgroundScope)
            val flow = MutableStateFlow(createSnapshot())
            manager.initialize(flow)
            runCurrent()

            assertEquals(TrackingStatus.Active, manager.status.first())
        }

    @Test
    fun calculateContentHash_sameContentDifferentTimestamp_returnsSameHash() {
        val snapshot1 = createSnapshot(timestamp = 1000L, remaining = 100)
        val snapshot2 = createSnapshot(timestamp = 2000L, remaining = 100)

        val hash1 = SnapshotHashCalculator.calculate(snapshot1)
        val hash2 = SnapshotHashCalculator.calculate(snapshot2)

        assertEquals("Hash should be the same if core content hasn't changed", hash1, hash2)
    }

    @Test
    fun calculateContentHash_differentContent_returnsDifferentHash() {
        val snapshot1 = createSnapshot(remaining = 100)
        val snapshot2 = createSnapshot(remaining = 200)

        val hash1 = SnapshotHashCalculator.calculate(snapshot1)
        val hash2 = SnapshotHashCalculator.calculate(snapshot2)

        assertNotEquals("Hash should differ when remaining value changes", hash1, hash2)
    }

    private fun createSnapshot(
        timestamp: Long = 0L,
        remaining: Long = 0L,
    ): GlobalSnapshot {
        val entity =
            SemanticEntity(
                id = "id",
                simSlot = 0,
                operatorName = "MTS",
                packageType = PackageType.MINUTES,
                limit = 1000,
                used = 1000 - remaining,
                remaining = remaining,
                unit = MeasurementUnit.MINUTES,
                periodStart = 0,
                periodEnd = 0,
                lastUpdateTime = 0,
                sourceType = DataSourceType.SYSTEM,
            )
        val simSnapshot =
            SimSnapshot(
                slot = 0,
                operator = "MTS",
                isActive = true,
                entities = mapOf(PackageType.MINUTES to entity),
            )
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timestamp,
            simSnapshots = listOf(simSnapshot),
            capabilities = emptyList(),
        )
    }
}
