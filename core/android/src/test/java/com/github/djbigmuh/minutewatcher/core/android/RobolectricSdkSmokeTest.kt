package com.github.djbigmuh.minutewatcher.core.android

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RobolectricSdkSmokeTest {
    private fun checkSdk(
        sdkInt: Int,
        coordinates: String,
    ) {
        val dependencyDir = System.getProperty("robolectric.dependency.dir")
        val isOffline = System.getProperty("robolectric.offline") == "true"
        require(isOffline) { "Robolectric must be configured in offline mode" }

        if (dependencyDir != null) {
            val dir = File(dependencyDir)
            val found = dir.exists() && dir.listFiles()?.any { it.name.contains("android-all-instrumented") } == true
            if (!found) {
                throw AssertionError(
                    "Missing Robolectric artifact '$coordinates' for SDK $sdkInt in dependency dir: $dependencyDir",
                )
            }
        }
        ApplicationProvider.getApplicationContext<Application>()
        assertEquals(sdkInt, Build.VERSION.SDK_INT)
    }

    @Test
    @Config(sdk = [28])
    fun `verify SDK 28`() {
        checkSdk(28, "org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
    }

    @Test
    @Config(sdk = [29])
    fun `verify SDK 29`() {
        checkSdk(29, "org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    }

    @Test
    @Config(sdk = [30])
    fun `verify SDK 30`() {
        checkSdk(30, "org.robolectric:android-all-instrumented:11-robolectric-6757853-i7")
    }

    @Test
    @Config(sdk = [31])
    fun `verify SDK 31`() {
        checkSdk(31, "org.robolectric:android-all-instrumented:12-robolectric-7732740-i7")
    }

    @Test
    @Config(sdk = [34])
    fun `verify SDK 34`() {
        checkSdk(34, "org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    }

    @Test
    @Config(sdk = [35])
    fun `verify SDK 35`() {
        checkSdk(35, "org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    }

    @Test
    @Config(sdk = [36])
    fun `verify SDK 36`() {
        checkSdk(36, "org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    }

    @Test
    @Config(sdk = [37])
    fun `verify SDK 37`() {
        checkSdk(37, "org.robolectric:android-all-instrumented:17-robolectric-15733970-i7")
    }
}
