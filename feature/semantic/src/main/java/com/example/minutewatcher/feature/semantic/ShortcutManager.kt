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
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class ShortcutManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val navigationProvider: NavigationProvider,
        private val timeProvider: TimeProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TrackingStatusProvider {
        override val key: String = "shortcuts"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null
        private var lastUpdateTime = 0L
        private var job: Job? = null

        /**
         * Initializes the manager to observe snapshot changes and update dynamic shortcuts.
         *
         * Short-lived changes are debounced by 30 seconds to prevent excessive updates,
         * while the first update and manual updates are processed immediately.
         *
         * @param snapshotFlow The flow of global snapshots to monitor.
         */
        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job?.cancel()
            job =
                scope.launch {
                    _status.value = TrackingStatus.Active
                    var isFirst = true
                    snapshotFlow.collectLatest { snapshot ->
                        // Debounce logic for automatic updates
                        if (!isFirst && !snapshot.isManualUpdate) {
                            val timeSinceLast = timeProvider.now() - lastUpdateTime
                            if (timeSinceLast < 30_000L) {
                                delay((30_000L - timeSinceLast).milliseconds)
                            }
                        }
                        isFirst = false

                        try {
                            withContext(ioDispatcher) {
                                updateShortcuts(snapshot)
                            }
                            lastUpdateTime = timeProvider.now()
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

        private fun updateShortcuts(snapshot: GlobalSnapshot) {
            val shortcuts = mutableListOf<ShortcutInfoCompat>()

            // limit shortcuts to supported SIMs
            snapshot.simSnapshots
                .filter { it.isActive && (it.slot in SimConstants.SUPPORTED_SLOT_INDICES) }
                .forEach { sim ->
                    val slot = sim.slot
                    val operator = SimUtils.getShortOperatorName(context, sim.operator)
                    val minutes = sim.entities[PackageType.MINUTES]
                    val traffic = sim.entities[PackageType.DATA]

                    minutes?.let {
                        if (it.limit > 0) { // Only show shortcut if limit is defined
                            val label = "${it.remaining}${context.getString(R.string.minutes_abbr)}"
                            val longLabel = "$operator: ${context.getString(R.string.remaining_text)}$label"
                            val shortcut =
                                ShortcutInfoCompat
                                    .Builder(context, "minutes_shortcut_$slot")
                                    .setShortLabel(label)
                                    .setLongLabel(longLabel)
                                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                                    .setIntent(
                                        Intent()
                                            .setClassName(
                                                context.packageName,
                                                navigationProvider.mainActivityClassName,
                                            ).apply {
                                                action = Intent.ACTION_VIEW
                                                putExtra("sim_slot", slot)
                                            },
                                    ).build()
                            shortcuts.add(shortcut)
                        }
                    }

                    traffic?.let {
                        if (it.limit > 0) {
                            val trafficGb =
                                it.remaining.toFloat() / com.example.minutewatcher.core.model.Constants.BYTES_PER_GB
                            val label =
                                String.format(
                                    Locale.getDefault(),
                                    "%.1f%s",
                                    trafficGb,
                                    context.getString(R.string.gb_abbr),
                                )
                            val longLabel = "$operator: ${context.getString(R.string.remaining_text)}$label"
                            val shortcut =
                                ShortcutInfoCompat
                                    .Builder(context, "traffic_shortcut_$slot")
                                    .setShortLabel(label)
                                    .setLongLabel(longLabel)
                                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                                    .setIntent(
                                        Intent()
                                            .setClassName(
                                                context.packageName,
                                                navigationProvider.mainActivityClassName,
                                            ).apply {
                                                action = Intent.ACTION_VIEW
                                                putExtra("sim_slot", slot)
                                            },
                                    ).build()
                            shortcuts.add(shortcut)
                        }
                    }
                }

            if (shortcuts.isNotEmpty()) {
                // Max 5 dynamic shortcuts are allowed by Android system
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.take(MAX_DYNAMIC_SHORTCUTS))
            } else {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            }
        }

        companion object {
            private const val MAX_DYNAMIC_SHORTCUTS = 5
        }
    }
