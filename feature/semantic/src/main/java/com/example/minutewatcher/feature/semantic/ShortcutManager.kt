package com.example.minutewatcher.feature.semantic

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.minutewatcher.core.android.util.SimUtils
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.IntentExtras
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimSnapshot
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
open class ShortcutManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val navigationProvider: NavigationProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TrackingStatusProvider {
        override val key: String = "shortcuts"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val initMutex = Mutex()
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null
        private var job: Job? = null

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
            job?.cancel()
            try {
                val completed =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        job?.join()
                    }
                if (completed == null) {
                    AppLogger.w("ShortcutManager: previous observation job did not terminate in time, cancelling")
                    job?.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ShortcutManager: failed to join previous observation job during initialize", e)
            } finally {
                job = null
            }

            initMutex.withLock {
                setupObservation(snapshotFlow)
            }
        }

        @OptIn(FlowPreview::class)
        private fun setupObservation(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job =
                scope.launch {
                    _status.value = TrackingStatus.Active
                    snapshotFlow
                        .debounce { snapshot ->
                            if (snapshot.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS
                        }.collect { snapshot ->
                            try {
                                withContext(ioDispatcher) {
                                    updateShortcuts(snapshot)
                                }
                                _status.value = TrackingStatus.Active
                            } catch (e: CancellationException) {
                                throw e
                            } catch (
                                @Suppress("TooGenericExceptionCaught")
                                e: Exception,
                            ) {
                                AppLogger.e("Failed to update dynamic shortcuts", e)
                                val type = classifyShortcutError(e)
                                _status.value = TrackingStatus.Error(type, e.message)
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
            job?.cancel()
            try {
                val completed =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        job?.join()
                    }
                if (completed == null) {
                    AppLogger.w("ShortcutManager: previous observation job did not terminate in time, cancelling")
                    job?.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ShortcutManager: failed to join previous observation job during recover", e)
            } finally {
                job = null
            }

            initMutex.withLock {
                lastSnapshotFlow?.let { setupObservation(it) }
            }
        }

        /**
         * Sets an error state for the shortcut manager.
         *
         * @param message Error message.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
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
                _status.value = TrackingStatus.Active
                true
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                AppLogger.e("Failed to update dynamic shortcuts", e)
                val type = classifyShortcutError(e)
                _status.value = TrackingStatus.Error(type, e.message)
                false
            }

        /**
         * Классифицирует ошибку работы с ярлыками.
         *
         * **Строгое соблюдение API:** На AOSP [ShortcutManagerCompat] бросает
         * [UnsupportedOperationException] при отсутствии поддержки ярлыков.
         * Если кастомная прошивка бросает иной тип исключения — это проблема кастомной прошивки.
         */
        private fun classifyShortcutError(e: Exception): TrackingStatus.ErrorType =
            if (e is UnsupportedOperationException) {
                TrackingStatus.ErrorType.NOT_SUPPORTED
            } else {
                TrackingStatus.ErrorType.UNKNOWN
            }

        private fun updateShortcuts(snapshot: GlobalSnapshot) {
            val shortcuts = createShortcuts(snapshot)
            applyShortcuts(shortcuts)
        }

        private fun createShortcuts(snapshot: GlobalSnapshot): List<ShortcutInfoCompat> {
            val shortcuts = mutableListOf<ShortcutInfoCompat>()

            snapshot.simSnapshots
                .filter { it.isActive && (it.slot in SimConstants.SUPPORTED_SLOT_INDICES) }
                .forEach { sim ->
                    createMinutesShortcut(sim)?.let { shortcuts.add(it) }
                    createTrafficShortcut(sim)?.let { shortcuts.add(it) }
                }

            return shortcuts.take(MAX_DYNAMIC_SHORTCUTS)
        }

        private fun createMinutesShortcut(sim: SimSnapshot): ShortcutInfoCompat? {
            val entity = sim.entities[PackageType.MINUTES]
            if (entity == null || entity.limit <= 0) return null

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
            if (entity == null || entity.limit <= 0) return null

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

        protected open fun applyShortcuts(shortcuts: List<ShortcutInfoCompat>) {
            try {
                if (shortcuts.isNotEmpty()) {
                    ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
                } else {
                    ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                AppLogger.e("Failed to update dynamic shortcuts: ${e.javaClass.simpleName}", e)
                throw e // Rethrow to be caught by executeSimOperation/updateShortcutsSync
            }
        }

        companion object {
            private const val MAX_DYNAMIC_SHORTCUTS = 5
        }
    }
