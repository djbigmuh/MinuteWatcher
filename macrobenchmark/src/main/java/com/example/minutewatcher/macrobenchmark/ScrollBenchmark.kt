package com.example.minutewatcher.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollSimList() = benchmarkRule.measureRepeated(
        packageName = "com.example.minutewatcher",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            // Populate data with 2 SIMs
            val intent = Intent("com.example.minutewatcher.benchmark.POPULATE_DATA")
            intent.setPackage("com.example.minutewatcher")
            intent.putExtra("sim_count", 2)
            InstrumentationRegistry.getInstrumentation().context.sendBroadcast(intent)
            Thread.sleep(1000)
        }
    ) {
        startActivityAndWait()
        
        // Find the list and scroll
        val list = device.findObject(By.scrollable(true))
        list.setGestureMargin(device.displayWidth / 5)
        
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}
