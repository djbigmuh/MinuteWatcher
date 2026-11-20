package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.di.IoDispatcher
import com.github.djbigmuh.minutewatcher.core.domain.AodSummaryAvailability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [AodSummaryAvailability] delegating to [NotificationHelper].
 *
 * Executes availability check on [IoDispatcher].
 * Safe to call from any coroutine context.
 */
@Singleton
class AodSummaryAvailabilityImpl
    @Inject
    constructor(
        private val notificationHelper: NotificationHelper,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : AodSummaryAvailability {
        override suspend fun isAodSummaryAvailable(): Boolean =
            withContext(ioDispatcher) {
                notificationHelper.isAodSummaryAvailable()
            }
    }
