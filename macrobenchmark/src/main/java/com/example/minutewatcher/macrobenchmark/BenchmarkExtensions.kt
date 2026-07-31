package com.example.minutewatcher.macrobenchmark

import android.os.Build
import androidx.benchmark.macro.MacrobenchmarkScope

fun MacrobenchmarkScope.grantBenchmarkPermissions() {
    val pkg = packageName

    // Grant Phone State permission
    device.executeShellCommand("pm grant $pkg android.permission.READ_PHONE_STATE")

    // Grant Usage Stats via AppOps
    device.executeShellCommand("appops set $pkg GET_USAGE_STATS allow")

    // Grant Notifications for API 33+
    if (Build.VERSION.SDK_INT >= 33) {
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }
}

fun MacrobenchmarkScope.populateBenchmarkData(simCount: Int) {
    // -W waits for the broadcast to finish, which is supported because of runBlocking in BenchmarkReceiver
    device.executeShellCommand(
        "am broadcast -W -a com.example.minutewatcher.benchmark.POPULATE_DATA " +
            "-n com.example.minutewatcher/com.example.minutewatcher.benchmark.BenchmarkReceiver " +
            "--ei sim_count $simCount",
    )
}
