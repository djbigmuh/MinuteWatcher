package com.example.minutewatcher.core.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.android.util.SimUtils
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller that keeps the persistent Always-on Display summary notification in sync with usage data and settings.
 *
 * Safe to call from any coroutine context. Constructor is side-effect free; start observation explicitly via [start].
 */
@OptIn(FlowPreview::class)
@Singleton
class AodSummaryController
    @Inject
    constructor(
        private val usageStateRepository: UsageStateRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val permissionRepository: PermissionRepository,
        private val notificationHelper: NotificationHelper,
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val started = AtomicBoolean(false)

        @VisibleForTesting
        internal var observationJob: Job? = null

        private var isPosted = false
        private var lastPublishedText: String? = null

        /**
         * Starts observing usage snapshot, app settings, and notification permissions.
         *
         * Thread-safe and idempotent. Consecutive calls are no-ops.
         */
        fun start() {
            if (!started.compareAndSet(false, true)) return

            observationJob =
                scope.launch(ioDispatcher) {
                    combine(
                        usageStateRepository.observeSnapshot(),
                        appSettingsRepository.observeAppSettings(),
                        permissionRepository.notificationPermissionGranted,
                    ) { snapshot, settings, permissionGranted ->
                        Triple(snapshot, settings, permissionGranted)
                    }.debounce(Constants.Flow.DEBOUNCE_MS)
                        .collect { (snapshot, settings, permissionGranted) ->
                            processUpdate(snapshot, settings, permissionGranted)
                        }
                }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun processUpdate(
            snapshot: GlobalSnapshot,
            settings: AppSettings,
            permissionGranted: Boolean,
        ) {
            try {
                updateSummaryState(snapshot, settings, permissionGranted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("AodSummaryController: error processing update", e)
            }
        }

        private fun updateSummaryState(
            snapshot: GlobalSnapshot,
            settings: AppSettings,
            permissionGranted: Boolean,
        ) {
            val isAvailable =
                settings.aodSummaryEnabled &&
                    permissionGranted &&
                    notificationHelper.isAodSummaryAvailable() &&
                    snapshot.timestamp != Constants.Data.EMPTY_TIMESTAMP

            val simLines = if (isAvailable) buildSimLines(snapshot) else emptyList()

            if (simLines.isEmpty()) {
                val reason =
                    when {
                        !settings.aodSummaryEnabled -> "setting disabled"
                        !permissionGranted || !notificationHelper.isAodSummaryAvailable() ->
                            "permission or channel unavailable"
                        snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP -> "empty snapshot timestamp"
                        else -> "no active SIM lines with limits"
                    }
                cancelSummaryIfPosted(reason)
            } else {
                val expandedText = simLines.joinToString("\n")
                val active = notificationHelper.isAodSummaryNotificationActive()
                if (!isPosted || !active || expandedText != lastPublishedText) {
                    val appLocaleContext =
                        com.example.minutewatcher.core.android.util.AppLocaleContexts
                            .forAppLocale(context)
                    val contentLine = simLines.first()
                    val title = appLocaleContext.getString(R.string.app_name)

                    val posted = notificationHelper.sendAodSummaryNotification(title, contentLine, expandedText)
                    if (posted) {
                        isPosted = true
                        lastPublishedText = expandedText
                        AppLogger.d("AOD summary notification posted successfully")
                    } else {
                        AppLogger.w("Failed to post AOD summary notification; will retry on next update")
                    }
                }
            }
        }

        private fun buildSimLines(snapshot: GlobalSnapshot): List<String> {
            val appLocaleContext =
                com.example.minutewatcher.core.android.util.AppLocaleContexts
                    .forAppLocale(context)
            val locale = FormatUtils.getLocale(appLocaleContext)
            val gbAbbr = appLocaleContext.getString(R.string.gb_abbr)

            return snapshot.simSnapshots
                .sortedBy { it.slot }
                .mapNotNull { sim ->
                    val parts = mutableListOf<String>()

                    val minutesEntity = sim.entities[PackageType.MINUTES]
                    if (minutesEntity != null && minutesEntity.limit > 0) {
                        parts.add(FormatUtils.formatMinutesPlural(appLocaleContext, minutesEntity.remaining.toInt()))
                    }

                    val dataEntity = sim.entities[PackageType.DATA]
                    if (dataEntity != null && dataEntity.limit > 0) {
                        val gbVal = (dataEntity.remaining.toDouble() / Constants.BYTES_PER_GB).toFloat()
                        parts.add("${FormatUtils.formatTrafficGb(locale, gbVal)} $gbAbbr")
                    }

                    if (parts.isEmpty()) {
                        null
                    } else {
                        val operatorName = SimUtils.getShortOperatorName(appLocaleContext, sim.operatorName, sim.slot)
                        "$operatorName$SIM_PARTS_SEPARATOR${parts.joinToString(SIM_PARTS_SEPARATOR)}"
                    }
                }
        }

        private fun cancelSummaryIfPosted(reason: String) {
            if (isPosted || notificationHelper.isAodSummaryNotificationActive()) {
                notificationHelper.cancelAodSummaryNotification()
                isPosted = false
                lastPublishedText = null
                AppLogger.d("AOD summary notification cancelled: $reason")
            }
        }

        companion object {
            private const val SIM_PARTS_SEPARATOR = " • "
        }
    }
