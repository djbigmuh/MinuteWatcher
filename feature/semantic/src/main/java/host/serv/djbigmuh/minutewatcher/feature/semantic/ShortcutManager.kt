package host.serv.djbigmuh.minutewatcher.feature.semantic

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import host.serv.djbigmuh.minutewatcher.core.android.util.AppLocaleContexts
import host.serv.djbigmuh.minutewatcher.core.android.util.SimUtils
import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.di.IoDispatcher
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.FlowObservationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.ObservationMode
import host.serv.djbigmuh.minutewatcher.core.domain.NavigationProvider
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.Constants.IntentExtras
import host.serv.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatusMachine
import host.serv.djbigmuh.minutewatcher.core.resources.R
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
 * **Contract:** Application is skipped if the shortcut set projection matches the last successfully applied;
 * [recover] resets the remembered projection.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException].
 * Handles [UnsupportedOperationException] for devices not supporting shortcuts.
 *
 * @property context Application context.
 * @property navigationProvider Provider for main activity class name.
 * @property ioDispatcher Dispatcher for I/O bound operations.
 */
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
    private val applyMutex = Mutex()
    private var lastAppliedProjections: List<ShortcutProjection>? = null

    @Volatile
    private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null
    private val observationScope =
        FlowObservationScope<GlobalSnapshot>(scope, "ShortcutManager", ObservationMode.COMPLETE)

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
            if (isCurrentObservationActive(snapshotFlow)) return
            lastSnapshotFlow = snapshotFlow
            observationScope.observe(
                snapshotFlow.debounce { snapshot ->
                    if (snapshot.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS
                },
            ) { snapshot ->
                try {
                    withContext(ioDispatcher) {
                        updateShortcuts(snapshot)
                    }
                    transitionStatus(TrackingStatus.Active, force = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to update dynamic shortcuts", e)
                    val type = classifyShortcutError(e)
                    transitionStatus(TrackingStatus.Error(type, e.message))
                }
            }
        }
    }

    private fun isCurrentObservationActive(snapshotFlow: StateFlow<GlobalSnapshot>): Boolean =
        job?.isActive == true && (lastSnapshotFlow === snapshotFlow)

    /**
     * Recovers the shortcut manager state by re-initializing with the last known flow.
     * Resets the remembered shortcut projection so shortcuts are re-applied.
     *
     * **Thread Safety:** Safe to call from any coroutine context.
     */
    override suspend fun recover() {
        applyMutex.withLock {
            lastAppliedProjections = null
        }
        val flow =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                initMutex.withLock { lastSnapshotFlow }
            }
        flow?.let {
            initMutex.withLock {
                lastSnapshotFlow = null
            }
            setupObservation(it)
        }
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
                applyShortcutsGuarded(shortcuts)
            }
            transitionStatus(TrackingStatus.Active, force = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Failed to update dynamic shortcuts", e)
            val type = classifyShortcutError(e)
            transitionStatus(TrackingStatus.Error(type, e.message))
            false
        }

    /**
     * Classifies a shortcut operation error.
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

    private suspend fun updateShortcuts(snapshot: GlobalSnapshot) {
        if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
            AppLogger.d("ShortcutManager: snapshot not loaded, skipping shortcut update (no removal)")
            return
        }
        val shortcuts = createShortcuts(snapshot)
        applyShortcutsGuarded(shortcuts)
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

        val appLocaleContext =
            AppLocaleContexts
                .forAppLocale(context)
        val slot = sim.slot
        val operator = SimUtils.getShortOperatorName(appLocaleContext, sim.operatorName, slot)
        val shortLabel =
            appLocaleContext.getString(
                R.string.shortcut_minutes_short,
                entity.remaining,
                appLocaleContext.getString(R.string.minutes_abbr).trim(),
            )
        val longLabel =
            appLocaleContext.getString(
                R.string.shortcut_long_format,
                operator,
                appLocaleContext.getString(R.string.remaining_text).trim(),
                shortLabel,
            )

        return ShortcutInfoCompat
            .Builder(context, "minutes_shortcut_$slot")
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_usage))
            .setIntent(createShortcutIntent(slot))
            .build()
    }

    private fun createTrafficShortcut(sim: SimSnapshot): ShortcutInfoCompat? {
        val entity = sim.entities[PackageType.DATA]
        if (entity == null || (entity.limit <= 0)) return null

        val appLocaleContext =
            AppLocaleContexts
                .forAppLocale(context)
        val slot = sim.slot
        val operator = SimUtils.getShortOperatorName(appLocaleContext, sim.operatorName, slot)
        val trafficGb = (entity.remaining.toDouble() / Constants.BYTES_PER_GB).toFloat()
        val shortLabel =
            appLocaleContext.getString(
                R.string.shortcut_traffic_short,
                trafficGb,
                appLocaleContext.getString(R.string.gb_abbr).trim(),
            )
        val longLabel =
            appLocaleContext.getString(
                R.string.shortcut_long_format,
                operator,
                appLocaleContext.getString(R.string.remaining_text).trim(),
                shortLabel,
            )

        return ShortcutInfoCompat
            .Builder(context, "traffic_shortcut_$slot")
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_usage))
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

    private suspend fun applyShortcutsGuarded(shortcuts: List<ShortcutInfoCompat>) {
        val projections = shortcuts.map { ShortcutProjection.from(it) }
        applyMutex.withLock {
            if (projections == lastAppliedProjections) {
                AppLogger.d("ShortcutManager: shortcut projections unchanged, skipping application")
                return
            }
            applyShortcuts(shortcuts)
            lastAppliedProjections = projections
        }
    }

    private fun applyShortcuts(shortcuts: List<ShortcutInfoCompat>) {
        shortcutApplier.apply(context, shortcuts)
    }
}

/**
 * Immutable projection of a shortcut, fully defining its visible content and action
 * (since intent is deterministically built via createShortcutIntent from slot encoded in id).
 */
private data class ShortcutProjection(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
) {
    companion object {
        fun from(shortcut: ShortcutInfoCompat): ShortcutProjection =
            ShortcutProjection(
                id = shortcut.id,
                shortLabel = shortcut.shortLabel.toString(),
                longLabel = shortcut.longLabel.toString(),
            )
    }
}
