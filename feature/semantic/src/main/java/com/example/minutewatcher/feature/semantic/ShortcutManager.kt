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

        @OptIn(FlowPreview::class)
        @Suppress("InstanceOfCheckForException")
        fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            lastSnapshotFlow = snapshotFlow
            job?.cancel()
            scope.launch {
                job?.join()
                job =
                    launch {
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
                                    // Эвристика определения NOT_SUPPORTED: проверяем тип исключения и сообщение.
                                    // **Строгое соблюдение API:** На AOSP ShortcutManagerCompat бросает
                                    // UnsupportedOperationException при отсутствии поддержки ярлыков.
                                    // Проверка по сообщению "Shortcut" — последний рубеж для кастомных прошивок,
                                    // которые бросают иные типы исключений. Если прошивка не соответствует
                                    // документации Google — это проблема кастомной прошивки.
                                    val type =
                                        if (e is UnsupportedOperationException ||
                                            e.message?.contains("Shortcut") == true
                                        ) {
                                            TrackingStatus.ErrorType.NOT_SUPPORTED
                                        } else {
                                            TrackingStatus.ErrorType.UNKNOWN
                                        }
                                    _status.value = TrackingStatus.Error(type, e.message)
                                }
                            }
                    }
            }
        }

        override suspend fun recover() {
            lastSnapshotFlow?.let { initialize(it) }
        }

        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }

        /**
         * Updates shortcuts synchronously. Returns true if successful.
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
            } catch (e: Exception) {
                AppLogger.e("Failed to update dynamic shortcuts", e)
                // Эвристика определения NOT_SUPPORTED: проверяем тип исключения и сообщение.
                // **Строгое соблюдение API:** На AOSP ShortcutManagerCompat бросает
                // UnsupportedOperationException при отсутствии поддержки ярлыков.
                // Проверка по сообщению "Shortcut" — последний рубеж для кастомных прошивок,
                // которые бросают иные типы исключений. Если прошивка не соответствует
                // документации Google — это проблема кастомной прошивки.
                val type =
                    if (e is UnsupportedOperationException || e.message?.contains("Shortcut") == true) {
                        TrackingStatus.ErrorType.NOT_SUPPORTED
                    } else {
                        TrackingStatus.ErrorType.UNKNOWN
                    }
                _status.value = TrackingStatus.Error(type, e.message)
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

        private fun createMinutesShortcut(sim: SimSnapshot): ShortcutInfoCompat? {
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

        private fun createTrafficShortcut(sim: SimSnapshot): ShortcutInfoCompat? {
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
                    putExtra(IntentExtras.SIM_SLOT, slot)
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
