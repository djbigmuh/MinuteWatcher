package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import com.example.minutewatcher.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.kotlin.mock
import java.lang.reflect.Method

class SemanticSearchManagerTest {
    private val context: Context = mock()
    private val scope: CoroutineScope = TestScope()
    private val manager = SemanticSearchManager(context, scope)

    @Test
    fun calculateContentHash_sameContentDifferentTimestamp_returnsSameHash() {
        val snapshot1 = createSnapshot(timestamp = 1000L, remaining = 100)
        val snapshot2 = createSnapshot(timestamp = 2000L, remaining = 100)

        val hash1 = invokeCalculateContentHash(snapshot1)
        val hash2 = invokeCalculateContentHash(snapshot2)

        assertEquals("Hash should be the same if core content hasn't changed", hash1, hash2)
    }

    @Test
    fun calculateContentHash_differentContent_returnsDifferentHash() {
        val snapshot1 = createSnapshot(remaining = 100)
        val snapshot2 = createSnapshot(remaining = 200)

        val hash1 = invokeCalculateContentHash(snapshot1)
        val hash2 = invokeCalculateContentHash(snapshot2)

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

    private fun invokeCalculateContentHash(snapshot: GlobalSnapshot): Int {
        val method: Method = SemanticSearchManager::class.java.getDeclaredMethod("calculateContentHash", GlobalSnapshot::class.java)
        method.isAccessible = true
        return method.invoke(manager, snapshot) as Int
    }
}
