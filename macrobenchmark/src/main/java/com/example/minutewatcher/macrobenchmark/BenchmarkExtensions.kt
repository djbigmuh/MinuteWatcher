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
    // API 33+ required for POST_NOTIFICATIONS runtime permission.
    // See: https://developer.android.com/training/notify-user/runtime-permissions
    // minSdk is 26, so this branch is reachable and required.
    if (Build.VERSION.SDK_INT >= API_LEVEL_NOTIFICATIONS) {
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }
}

private const val API_LEVEL_NOTIFICATIONS = 33

fun MacrobenchmarkScope.populateBenchmarkData(simCount: Int) {
    // -W waits for the broadcast receiver to return, but it does NOT wait for
    // background work (BenchmarkPopulateWorker) to finish.
    device.executeShellCommand(
        "am broadcast -W -a com.example.minutewatcher.benchmark.POPULATE_DATA " +
            "-n com.example.minutewatcher/com.example.minutewatcher.benchmark.BenchmarkReceiver " +
            "--ei sim_count $simCount",
    )
}
