package com.example.minutewatcher.macrobenchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
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
            metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            // Here we could pass an intent that triggers data simulation if we implemented it
            startActivityAndWait()
        }
}
