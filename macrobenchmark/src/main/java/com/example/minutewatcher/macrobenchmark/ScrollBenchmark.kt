package com.example.minutewatcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollSimList() =
        benchmarkRule.measureRepeated(
            packageName = "com.example.minutewatcher",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = {
                grantBenchmarkPermissions()
                populateBenchmarkData(SIM_COUNT)
            },
        ) {
            startActivityAndWait()

            // Wait for mock data to be displayed
            assertTrue(
                "Mock data not displayed",
                device.wait(Until.hasObject(By.textContains("Mock Operator")), WAIT_TIMEOUT),
            )

            // Find the list and scroll
            val list = device.wait(Until.findObject(By.scrollable(true)), WAIT_TIMEOUT)
            assertNotNull("Scrollable list not found. Ensure more than 3-4 items are displayed.", list)

            list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)

            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }

    companion object {
        private const val SIM_COUNT = 10
        private const val GESTURE_MARGIN_DIVISOR = 5
        private const val ITERATIONS = 5
        private const val WAIT_TIMEOUT = 5000L
    }
}
