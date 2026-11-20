package com.github.djbigmuh.minutewatcher.core.data.testing

import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider

const val DEFAULT_TEST_TIMESTAMP = 1_000_000L
const val DEFAULT_TEST_REALTIME = 500_000L
const val DEFAULT_TEST_UPTIME = 250_000L
const val FUTURE_TEST_TIMESTAMP = 2_000_000_000_000L
const val RESET_TEST_TIMESTAMP = 5_000_000L

fun createFixedTimeProvider(
    currentTime: Long = DEFAULT_TEST_TIMESTAMP,
    currentRealtime: Long = DEFAULT_TEST_REALTIME,
    currentUptime: Long = DEFAULT_TEST_UPTIME,
): FixedTimeProvider = FixedTimeProvider(currentTime, currentRealtime, currentUptime)
