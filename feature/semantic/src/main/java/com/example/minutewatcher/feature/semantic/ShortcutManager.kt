package com.example.minutewatcher.feature.semantic

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.minutewatcher.core.android.util.SimUtils
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FlowObservationScope
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.IntentExtras
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.TrackingStatusMachine
import com.example.minutewatcher.core.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * ShortcutManager manages dynamic shortcuts for SIM card usage data.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException].
 * Handles [UnsupportedOperationException] for devices not supporting shortcuts.
 *
 * @property context Application context.
 * @property navigationProvider Provider for main activity class name.
 * @property ioDispatcher Dispatcher for I/O bound operations.
 */
@Suppress("TooManyFunctions")
@Singleton
class ShortcutManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope scope: CoroutineScope,
        private val navigationProvider: NavigationProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val shortcutApplier: ShortcutApplier,
    ) : TrackingStatusProvider {
        override val key: TrackingKey = TrackingKey.SHORTCUTS

        private val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        private val initMutex = Mutex()
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null
        private val observationScope = FlowObservationScope<GlobalSnapshot>(scope, "ShortcutManager")

        /** Current observation job, if any. */
        @VisibleForTesting
        internal val job: Job? get() = observationScope.currentJob

        /**
         * Updates the tracker status using canonical prioritized rules.
         */
        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            statusMachine.transition(newStatus, force)
        }

        /**
         * Initializes the shortcut manager by observing the snapshot flow.
         *
         * @param snapshotFlow Flow of global usage snapshots.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [CancellationException].
         */
        @OptIn(FlowPreview::class)
        suspend fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            setupObservation(snapshotFlow)
        }

        @OptIn(FlowPreview::class)
        @VisibleForTesting
        internal suspend fun setupObservation(snapshotFlow: StateFlow<GlobalSnapshot>) {
            initMutex.withLock {
                lastSnapshotFlow = snapshotFlow
                observationScope.observe(
                    snapshotFlow.debounce { snapshot ->
                        if (snapshot.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS
                    },
                ) { snapshot ->
                    try {
                        transitionStatus(TrackingStatus.Active)
                        withContext(ioDispatcher) {
                            updateShortcuts(snapshot)
                        }
                        transitionStatus(TrackingStatus.Active)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught")
                        e: Exception,
                    ) {
                        AppLogger.e("Failed to update dynamic shortcuts", e)
                        val type = classifyShortcutError(e)
                        transitionStatus(TrackingStatus.Error(type, e.message))
                    }
                }
            }
        }

        /**
         * Recovers the shortcut manager state by re-initializing with the last known flow.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        override suspend fun recover() {
            val flow =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    initMutex.withLock { lastSnapshotFlow }
                }
            flow?.let { setupObservation(it) }
        }

        /**
         * Updates shortcuts synchronously.
         *
         * @param snapshot Global usage snapshot to apply.
         * @return True if successful.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [CancellationException].
         * **Contract:** Snapshots with [Constants.Data.EMPTY_TIMESTAMP] are ignored to avoid
         * removing all dynamic shortcuts during cold start.
         */
        suspend fun updateShortcutsSync(snapshot: GlobalSnapshot): Boolean =
            try {
                if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
                    AppLogger.d("ShortcutManager: snapshot not loaded, skipping shortcut update (no removal)")
                    return true
                }

                withContext(ioDispatcher) {
                    val shortcuts = createShortcuts(snapshot)
                    applyShortcuts(shortcuts)
                }
                transitionStatus(TrackingStatus.Active)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                AppLogger.e("Failed to update dynamic shortcuts", e)
                val type = classifyShortcutError(e)
                transitionStatus(TrackingStatus.Error(type, e.message))
                false
            }

        /**
         * Классифицирует ошибку работы с ярлыками.
         */
        private fun classifyShortcutError(e: Exception): TrackingStatus.ErrorType =
            when (e) {
                is UnsupportedOperationException -> {
                    TrackingStatus.ErrorType.NOT_SUPPORTED
                }

                is SecurityException -> {
                    TrackingStatus.ErrorType.PERMISSION_MISSING
                }

                else -> {
                    TrackingStatus.ErrorType.UNKNOWN
                }
            }

        private fun updateShortcuts(snapshot: GlobalSnapshot) {
            if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
                AppLogger.d("ShortcutManager: snapshot not loaded, skipping shortcut update (no removal)")
                return
            }
            val shortcuts = createShortcuts(snapshot)
            applyShortcuts(shortcuts)
        }

        private fun createShortcuts(snapshot: GlobalSnapshot): List<ShortcutInfoCompat> {
            val shortcuts = mutableListOf<ShortcutInfoCompat>()

            snapshot.simSnapshots
                .filter { it.slot in SimConstants.SUPPORTED_SLOT_INDICES }
                .forEach { sim ->
                    createMinutesShortcut(sim)?.let { shortcuts.add(it) }
                    createTrafficShortcut(sim)?.let { shortcuts.add(it) }
                }

            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            return shortcuts.take(max.coerceAtLeast(0))
        }

        private fun createMinutesShortcut(sim: SimSnapshot): ShortcutInfoCompat? {
            val entity = sim.entities[PackageType.MINUTES]
            if ((entity == null) || (entity.limit <= 0)) return null

            val slot = sim.slot
            val operator = SimUtils.getShortOperatorName(context, sim.operatorName)
            val shortLabel =
                context.getString(
                    R.string.shortcut_minutes_short,
                    entity.remaining,
                    context.getString(R.string.minutes_abbr).trim(),
                )
            val longLabel =
                context.getString(
                    R.string.shortcut_long_format,
                    operator,
                    context.getString(R.string.remaining_text).trim(),
                    shortLabel,
                )

            return ShortcutInfoCompat
                .Builder(context, "minutes_shortcut_$slot")
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                .setIntent(createShortcutIntent(slot))
                .build()
        }

        private fun createTrafficShortcut(sim: SimSnapshot): ShortcutInfoCompat? {
            val entity = sim.entities[PackageType.DATA]
            if (entity == null || (entity.limit <= 0)) return null

            val slot = sim.slot
            val operator = SimUtils.getShortOperatorName(context, sim.operatorName)
            val trafficGb = (entity.remaining.toDouble() / Constants.BYTES_PER_GB).toFloat()
            val shortLabel =
                context.getString(
                    R.string.shortcut_traffic_short,
                    trafficGb,
                    context.getString(R.string.gb_abbr).trim(),
                )
            val longLabel =
                context.getString(
                    R.string.shortcut_long_format,
                    operator,
                    context.getString(R.string.remaining_text).trim(),
                    shortLabel,
                )

            return ShortcutInfoCompat
                .Builder(context, "traffic_shortcut_$slot")
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                .setIntent(createShortcutIntent(slot))
                .build()
        }

        private fun createShortcutIntent(slot: Int): Intent =
            Intent()
                .setClassName(
                    context.packageName,
                    navigationProvider.mainActivityClassName,
                ).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(IntentExtras.SIM_SLOT, slot)
                }

        private fun applyShortcuts(shortcuts: List<ShortcutInfoCompat>) {
            shortcutApplier.apply(context, shortcuts)
        }
    }
