package com.example.minutewatcher.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startup() =
        benchmarkRule.measureRepeated(
            packageName = "com.example.minutewatcher",
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }

    @Test
    fun startupWithSims() =
        benchmarkRule.measureRepeated(
            packageName = "com.example.minutewatcher",
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = {
                // Populate data before benchmark
                device.executeShellCommand(
                    "am broadcast -a com.example.minutewatcher.benchmark.POPULATE_DATA " +
                        "-n com.example.minutewatcher/com.example.minutewatcher.benchmark.BenchmarkReceiver " +
                        "--ei sim_count 2"
                )
            }
        ) {
            pressHome()
            startActivityAndWait()
            // Ensure mock data is displayed before concluding the measurement
            device.wait(Until.hasObject(By.textContains("Mock Operator")), 5000)
        }
}
