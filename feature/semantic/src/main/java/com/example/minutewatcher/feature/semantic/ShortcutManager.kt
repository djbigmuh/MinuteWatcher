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
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimConstants
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
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortcutManager
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

        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null
        private var job: Job? = null

        /**
         * Initializes the manager to observe snapshot changes and update dynamic shortcuts.
         *
         * Short-lived changes are debounced by 30 seconds to prevent excessive updates,
         * while the first update and manual updates are processed immediately.
         *
         * @param snapshotFlow The flow of global snapshots to monitor.
         */
        @OptIn(FlowPreview::class)
        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job?.cancel()
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
                            } catch (e: Exception) {
                                AppLogger.e("Failed to update dynamic shortcuts", e)
                                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                            }
                        }
                }
        }

        override fun recover() {
            lastSnapshotFlow?.let { initialize(it) }
        }

        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }

        /**
         * Updates shortcuts synchronously. Returns true if successful.
         */
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
            } catch (e: Exception) {
                AppLogger.e("Failed to update dynamic shortcuts", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                false
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

        private fun createMinutesShortcut(sim: com.example.minutewatcher.core.model.SimSnapshot): ShortcutInfoCompat? {
            val entity = sim.entities[PackageType.MINUTES]
            if (entity == null || entity.limit <= 0) return null

            val slot = sim.slot
            val operator = SimUtils.getShortOperatorName(context, sim.operator)
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

        private fun createTrafficShortcut(sim: com.example.minutewatcher.core.model.SimSnapshot): ShortcutInfoCompat? {
            val entity = sim.entities[PackageType.DATA]
            if (entity == null || entity.limit <= 0) return null

            val slot = sim.slot
            val operator = SimUtils.getShortOperatorName(context, sim.operator)
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
                    putExtra("sim_slot", slot)
                }

        private fun applyShortcuts(shortcuts: List<ShortcutInfoCompat>) {
            try {
                if (shortcuts.isNotEmpty()) {
                    ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
                } else {
                    ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to update dynamic shortcuts: ${e.javaClass.simpleName}", e)
            }
        }

        companion object {
            private const val MAX_DYNAMIC_SHORTCUTS = 5
        }
    }
