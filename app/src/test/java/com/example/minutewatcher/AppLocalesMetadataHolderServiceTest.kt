package com.example.minutewatcher

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppLocalesMetadataHolderServiceTest {
    @Test
    fun manifestDeclaresAppLocalesMetadataHolderServiceWithAutoStoreLocales() {
        val context = RuntimeEnvironment.getApplication()
        val componentName =
            ComponentName(
                context,
                "androidx.appcompat.app.AppLocalesMetadataHolderService",
            )

        val flags =
            PackageManager.ComponentInfoFlags.of(
                (PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
            )

        val serviceInfo =
            try {
                context.packageManager.getServiceInfo(componentName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                throw AssertionError("AppLocalesMetadataHolderService not found in manifest", e)
            }

        assertNotNull("ServiceInfo must not be null", serviceInfo)
        assertFalse("Service must be disabled (android:enabled=\"false\")", serviceInfo.enabled)
        assertFalse("Service must not be exported (android:exported=\"false\")", serviceInfo.exported)

        val metaData = serviceInfo.metaData
        assertNotNull("Meta-data must be present on AppLocalesMetadataHolderService", metaData)
        val autoStoreLocales = metaData?.getBoolean("autoStoreLocales", false) ?: false
        assertEquals("meta-data autoStoreLocales must be true", true, autoStoreLocales)
    }
}
