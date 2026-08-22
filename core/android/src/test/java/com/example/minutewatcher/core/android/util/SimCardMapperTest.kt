package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimCardMapperTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `map correctly handles traffic adjustment`() {
        // Arrange
        val usage =
            SimUsage(
                identity = "test_id",
                slotIndex = 0,
                subscriptionId = 1,
                operator = "TestOp",
                minutesUsed = 50,
                minutesLimit = 100,
                manualAdjustment = 10,
                trafficUsedMb = 512f, // 0.5 GB
                trafficLimitMb = 1024, // 1 GB
                trafficAdjustmentGb = 0.5f, // +0.5 GB
                autoReset = false,
                autoResetDay = 1,
                lastResetDate = 0L,
                rolloverEnabled = false,
            )

        // Act
        val model = SimCardMapper.map(context, 0, usage)

        // Assert
        assertEquals(1.5f, model.effectiveTrafficLimitGb, 0.001f)
        assertEquals(0.5f, model.trafficAdjustmentGb, 0.001f)
        // (1.0 + 0.5) - 0.5 = 1.0 GB remaining
        assertEquals(1.0f, model.trafficRemainingGb, 0.001f)
    }

    @Test
    fun `map clamps negative effective limit to zero`() {
        // Arrange
        val usage =
            SimUsage(
                identity = "test_id",
                slotIndex = 0,
                subscriptionId = 1,
                operator = "TestOp",
                minutesUsed = 0,
                minutesLimit = 100,
                manualAdjustment = -200, // Result: -100, should be clamped to 0
                trafficUsedMb = 0f,
                trafficLimitMb = 1024,
                trafficAdjustmentGb = -2f, // Result: -1GB, should be clamped to 0
                autoReset = false,
                autoResetDay = 1,
                lastResetDate = 0L,
                rolloverEnabled = false,
            )

        // Act
        val model = SimCardMapper.map(context, 0, usage)

        // Assert
        assertEquals(0, model.effectiveMinutesLimit)
        assertEquals(0f, model.effectiveTrafficLimitGb, 0.001f)
        assertEquals(0, model.minutesRemaining)
        assertEquals(0f, model.trafficRemainingGb, 0.001f)
    }

    @Test
    fun `concurrent map calls with different identities do not corrupt cache`() {
        runBlocking {
            val slot = 0
            val identities = List(10) { "identity_$it" }

            val jobs =
                identities.map { id ->
                    async(Dispatchers.Default) {
                        val usage =
                            SimUsage(
                                identity = id,
                                slotIndex = slot,
                                subscriptionId = 1,
                                operator = "TestOp",
                                minutesUsed = -1, // trigger log path
                                minutesLimit = 100,
                                manualAdjustment = 0,
                                trafficUsedMb = 0f,
                                trafficLimitMb = 1024,
                                trafficAdjustmentGb = 0f,
                                autoReset = false,
                                autoResetDay = 1,
                                lastResetDate = 0L,
                                rolloverEnabled = false,
                            )
                        SimCardMapper.map(context, slot, usage)
                    }
                }

            jobs.awaitAll()
        }
    }
}
