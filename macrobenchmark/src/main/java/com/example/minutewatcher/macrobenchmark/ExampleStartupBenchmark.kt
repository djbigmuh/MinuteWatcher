package com.example.minutewatcher.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
                val intent = Intent("com.example.minutewatcher.benchmark.POPULATE_DATA")
                intent.setPackage("com.example.minutewatcher")
                intent.putExtra("sim_count", 2)
                InstrumentationRegistry.getInstrumentation().context.sendBroadcast(intent)
                // Small delay to ensure DB write is done
                Thread.sleep(1000)
            }
        ) {
            pressHome()
            startActivityAndWait()
        }
}
