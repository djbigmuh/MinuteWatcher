package com.github.djbigmuh.minutewatcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class ManifestPhoneStateReceiverTest {
    @Test
    fun manifestContainsCorrectPhoneStateAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, UsageMonitorReceiver::class.java)

        // Verify receiver is declared
        try {
            context.packageManager.getReceiverInfo(component, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw AssertionError("UsageMonitorReceiver not found in manifest", e)
        }

        // TelephonyManager.ACTION_PHONE_STATE_CHANGED = "android.intent.action.PHONE_STATE"
        val expectedAction = TelephonyManager.ACTION_PHONE_STATE_CHANGED
        val intent = Intent(expectedAction)
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)

        val found =
            receivers.any {
                it.activityInfo.packageName == context.packageName &&
                    it.activityInfo.name == UsageMonitorReceiver::class.java.name
            }

        assertTrue(
            "Manifest for UsageMonitorReceiver must match action $expectedAction, but it doesn't.",
            found,
        )
    }
}
