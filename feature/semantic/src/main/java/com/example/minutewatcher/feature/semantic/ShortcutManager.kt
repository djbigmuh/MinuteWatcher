package com.example.minutewatcher.feature.semantic

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.minutewatcher.core.android.util.SimUtils
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.cancelAndJoinWithTimeout
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
import com.example.minutewatcher.core.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
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
 * @property scope Application-wide coroutine scope for async operations.
 * @property navigationProvider Provider for main activity class name.
 * @property ioDispatcher Dispatcher for I/O bound operations.
 */
@Suppress("TooManyFunctions")
@Singleton
class ShortcutManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val navigationProvider: NavigationProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val shortcutApplier: ShortcutApplier,
    ) : TrackingStatusProvider {
    override val key: TrackingKey = TrackingKey.SHORTCUTS

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val initMutex = Mutex()
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null
        private var job: Job? = null

    /**
     * Updates the tracker status using a prioritized transition rule to prevent race conditions.
     * Priority: PERMISSION_MISSING > DATABASE_ERROR > UNKNOWN (Error) > Active = Idle.
     */
    private fun transitionStatus(
        newStatus: TrackingStatus,
        force: Boolean = false,
    ) {
        _status.update { current ->
            val currentPrio = getStatusPriority(current)
            val newPrio = getStatusPriority(newStatus)

            when {
                force -> newStatus
                current == newStatus -> current
                newPrio >= currentPrio -> newStatus
                // Recovery: Success can clear transient UNKNOWN error
                newPrio == TrackingStatus.PRIORITY_HEALTHY &&
                    currentPrio == TrackingStatus.PRIORITY_TRANSIENT_ERROR -> newStatus

                else -> current
            }
        }
    }

    private fun getStatusPriority(status: TrackingStatus): Int =
        when (status) {
            is TrackingStatus.Error ->
                when (status.type) {
                    TrackingStatus.ErrorType.PERMISSION_MISSING -> TrackingStatus.PRIORITY_PERMISSION_MISSING
                    TrackingStatus.ErrorType.DATABASE_ERROR -> TrackingStatus.PRIORITY_DATABASE_ERROR
                    else -> TrackingStatus.PRIORITY_TRANSIENT_ERROR
                }

            TrackingStatus.Active, TrackingStatus.Idle -> TrackingStatus.PRIORITY_HEALTHY
            else -> 0
        }

        /**
         * Инициализирует наблюдение за snapshot-потоком.
         * Идемпотентен: повторный вызов отменяет предыдущее наблюдение
         * и запускает новое. `job?.join()` гарантирует, что старая корутина
         * полностью завершилась до запуска новой.
         *
         * **Строгое соблюдение API:** на AOSP StateFlow эмитит значения
         * последовательно. Debounce гарантирует отсутствие дублирующих индексаций.
         * Если кастомная прошивка эмитит значения вне порядка — это проблема
         * кастомной прошивки.
         *
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
        private suspend fun setupObservation(snapshotFlow: StateFlow<GlobalSnapshot>) {
            initMutex.withLock {
                lastSnapshotFlow = snapshotFlow
                val oldJob = job
                if (oldJob != null) {
                    oldJob.cancelAndJoinWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
                    if (oldJob.isActive) {
                        AppLogger.w(
                            "ShortcutManager: previous observation job did not terminate in time, forcing cancel",
                        )
                        oldJob.cancel()
                        try {
                            oldJob.join()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Джоб завершён принудительно
                        }
                    }
                    job = null
                }
                job =
                    scope.launch {
                        transitionStatus(TrackingStatus.Active)
                        snapshotFlow
                            .debounce { snapshot ->
                                if (snapshot.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS
                            }.collect { snapshot ->
                                try {
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
         */
        @Suppress("InstanceOfCheckForException")
        suspend fun updateShortcutsSync(snapshot: GlobalSnapshot): Boolean =
            try {
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

                is IllegalStateException -> {
                    if (e.message?.contains("context", ignoreCase = true) == true) {
                        TrackingStatus.ErrorType.NOT_SUPPORTED
                    } else {
                        TrackingStatus.ErrorType.UNKNOWN
                    }
                }

                else -> {
                    TrackingStatus.ErrorType.UNKNOWN
                }
            }

        private fun updateShortcuts(snapshot: GlobalSnapshot) {
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

            return shortcuts.take(MAX_DYNAMIC_SHORTCUTS)
        }

        private fun createMinutesShortcut(sim: SimSnapshot): ShortcutInfoCompat? {
            val entity = sim.entities[PackageType.MINUTES]
            if ((entity == null) || (entity.limit <= 0)) return null

            val slot = sim.slot
            val operator = SimUtils.getShortOperatorName(context, sim.operatorName)
            val label = "${entity.remaining} ${context.getString(R.string.minutes_abbr).trim()}"
            val longLabel = "$operator: ${context.getString(R.string.remaining_text).trim()} $label"

            return ShortcutInfoCompat
                .Builder(context, "minutes_shortcut_$slot")
                .setShortLabel(label)
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
            val label =
                String.format(
                    Locale.getDefault(),
                    "%.1f %s",
                    trafficGb,
                    context.getString(R.string.gb_abbr).trim(),
                )
            val longLabel = "$operator: ${context.getString(R.string.remaining_text).trim()} $label"

            return ShortcutInfoCompat
                .Builder(context, "traffic_shortcut_$slot")
                .setShortLabel(label)
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

        companion object {
            private const val MAX_DYNAMIC_SHORTCUTS = 5
        }
    }
