package com.example.minutewatcher.core.domain

/**
 * Checks availability of Always-on Display summary notification using documented Android APIs.
 *
 * Safe to call from any coroutine context.
 */
interface AodSummaryAvailability {
    /**
     * Checks whether Always-on Display summary notification can be posted and displayed.
     *
     * Evaluates NotificationManager availability, app notification permissions, and
     * channel status using documented system APIs.
     *
     * @return true if AOD summary can be posted and shown, false otherwise.
     */
    suspend fun isAodSummaryAvailable(): Boolean
}
