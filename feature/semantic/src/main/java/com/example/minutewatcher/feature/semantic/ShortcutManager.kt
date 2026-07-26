package com.example.minutewatcher.feature.semantic

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class ShortcutManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AutoCloseable {
        private var job: Job? = null

        fun initialize(snapshotFlow: kotlinx.coroutines.flow.StateFlow<GlobalSnapshot>) {
            job?.cancel()
            job =
                scope.launch {
                    snapshotFlow
                        .debounce(30_000L)
                        .collectLatest { snapshot ->
                            try {
                                updateShortcuts(snapshot)
                            } catch (e: Exception) {
                                AppLogger.e("Failed to update dynamic shortcuts", e)
                            }
                        }
                }
        }

        override fun close() {
            job?.cancel()
            job = null
        }

        private fun updateShortcuts(snapshot: GlobalSnapshot) {
            val shortcuts = mutableListOf<ShortcutInfoCompat>()

            // limit shortcuts to supported SIMs
            snapshot.simSnapshots.filter { it.isActive }.take(SimConstants.MAX_SUPPORTED_SIM_SLOTS).forEach { sim ->
                val slot = sim.slot
                val operator = sim.operator
                val minutes = sim.entities[PackageType.MINUTES]
                val traffic = sim.entities[PackageType.DATA]

                minutes?.let {
                    if (it.limit > 0) { // Only show shortcut if limit is defined
                        val label = "${it.remaining}${context.getString(R.string.minutes_abbr)}"
                        val longLabel = "$operator: ${context.getString(R.string.remaining_text)} $label"
                        val shortcut =
                            ShortcutInfoCompat
                                .Builder(context, "minutes_shortcut_$slot")
                                .setShortLabel(label)
                                .setLongLabel(longLabel)
                                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                                .setIntent(
                                    Intent().setClassName(context.packageName, "com.example.minutewatcher.MainActivity").apply {
                                        action = Intent.ACTION_VIEW
                                        putExtra("sim_slot", slot)
                                    },
                                ).build()
                        shortcuts.add(shortcut)
                    }
                }

                traffic?.let {
                    if (it.limit > 0) {
                        val trafficGb = it.remaining.toFloat() / (1024 * 1024 * 1024)
                        val label = String.format(Locale.getDefault(), "%.1f%s", trafficGb, context.getString(R.string.gb_abbr))
                        val longLabel = "$operator: ${context.getString(R.string.remaining_text)} $label"
                        val shortcut =
                            ShortcutInfoCompat
                                .Builder(context, "traffic_shortcut_$slot")
                                .setShortLabel(label)
                                .setLongLabel(longLabel)
                                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                                .setIntent(
                                    Intent().setClassName(context.packageName, "com.example.minutewatcher.MainActivity").apply {
                                        action = Intent.ACTION_VIEW
                                        putExtra("sim_slot", slot)
                                    },
                                ).build()
                        shortcuts.add(shortcut)
                    }
                }
            }

            if (shortcuts.isNotEmpty()) {
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.take(5))
            } else {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            }
        }
    }
