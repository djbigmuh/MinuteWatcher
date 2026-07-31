package com.example.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageModelsTest {
    @Test
    fun `MinutesModel handles negative adjustment by clamping effectiveLimit to zero`() {
        val model =
            MinutesModel.calculate(
                used = 10,
                limit = 100,
                manualAdjustment = -200,
            )

        assertEquals(0, model.effectiveLimit)
        assertEquals(0, model.remaining)
    }

    @Test
    fun `MinutesModel handles positive adjustment correctly`() {
        val model =
            MinutesModel.calculate(
                used = 10,
                limit = 0,
                manualAdjustment = 50,
            )

        assertEquals(50, model.effectiveLimit)
        assertEquals(40, model.remaining)
    }

    @Test
    fun `TrafficModel handles negative adjustment by clamping effectiveLimitGb to zero`() {
        val model =
            TrafficModel.calculate(
                usedMb = 512f,
                limitMb = 0,
                manualAdjustmentGb = -5f,
            )

        assertEquals(0f, model.effectiveLimitGb)
        assertEquals(0f, model.remainingGb)
    }

    @Test
    fun `TrafficModel calculates remainingGb correctly with adjustment`() {
        val model =
            TrafficModel.calculate(
                usedMb = 1024f, // 1 GB
                limitMb = 2048, // 2 GB
                manualAdjustmentGb = 1f, // +1 GB
            )

        assertEquals(3f, model.effectiveLimitGb)
        assertEquals(2f, model.remainingGb)
    }
}
