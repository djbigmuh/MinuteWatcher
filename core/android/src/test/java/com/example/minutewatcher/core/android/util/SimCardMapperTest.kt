package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.model.SimUsage
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
        val usage = SimUsage(
            identity = "test_id",
            slotIndex = 0,
            subscriptionId = 1,
            operator = "TestOp",
            minutesUsed = 50,
            minutesLimit = 100,
            manualAdjustment = 10,
            trafficUsed = 512f, // 0.5 GB
            trafficLimitMb = 1024, // 1 GB
            trafficAdjustment = 0.5f, // +0.5 GB
            autoReset = false,
            autoResetDay = 1,
            lastResetDate = 0L,
            rolloverEnabled = false
        )

        // Act
        val model = SimCardMapper.map(context, 0, usage)

        // Assert
        assertEquals(1.5f, model.effectiveTrafficLimitGb, 0.001f)
        assertEquals(0.5f, model.trafficAdjustmentGb, 0.001f)
        // (1.0 + 0.5) - 0.5 = 1.0 GB remaining
        assertEquals(1.0f, model.trafficRemainingGb, 0.001f)
    }
}
