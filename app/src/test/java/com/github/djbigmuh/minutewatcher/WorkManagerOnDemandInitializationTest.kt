package com.github.djbigmuh.minutewatcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkManagerOnDemandInitializationTest {
    @Test
    fun `MinuteWatcherApp implements Configuration Provider`() {
        val app = ApplicationProvider.getApplicationContext<Context>() as? Configuration.Provider
        assertNotNull("MinuteWatcherApp must implement Configuration.Provider", app)
        assertNotNull("workManagerConfiguration must not be null", app?.workManagerConfiguration)
    }

    @Test
    fun `merged manifest removes WorkManagerInitializer from startup provider meta-data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val providerInfo =
            context.packageManager.getProviderInfo(
                ComponentName(context, "androidx.startup.InitializationProvider"),
                PackageManager.GET_META_DATA,
            )
        assertFalse(
            "androidx.work.WorkManagerInitializer must be removed from InitializationProvider meta-data",
            providerInfo.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true,
        )
    }
}
