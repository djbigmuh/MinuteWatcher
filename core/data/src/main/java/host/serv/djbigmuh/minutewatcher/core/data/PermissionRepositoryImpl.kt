package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [PermissionRepository] that observes permission states.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class PermissionRepositoryImpl
@Inject
constructor(
    private val permissionManager: PermissionManager,
    private val notifier: PermissionStateNotifier,
    @ApplicationScope private val scope: CoroutineScope,
) : PermissionRepository {
    /**
     * Flow indicating if READ_PHONE_STATE permission is granted.
     *
     * Safe to call from any coroutine context.
     */
    override val phoneStatePermissionGranted: StateFlow<Boolean> =
        buildPermissionFlow(
            initialValue = permissionManager.hasPhoneStatePermission(),
            check = { permissionManager.hasPhoneStatePermission() },
        )

    /**
     * Flow indicating if PACKAGE_USAGE_STATS permission is granted.
     *
     * Safe to call from any coroutine context.
     */
    override val usageStatsPermissionGranted: StateFlow<Boolean> =
        buildPermissionFlow(
            initialValue = permissionManager.hasUsageStatsPermission(),
            check = { permissionManager.hasUsageStatsPermission() },
        )

    /**
     * Flow indicating if POST_NOTIFICATIONS permission is granted.
     *
     * Safe to call from any coroutine context.
     */
    override val notificationPermissionGranted: StateFlow<Boolean> =
        buildPermissionFlow(
            initialValue = permissionManager.hasNotificationPermission(),
            check = { permissionManager.hasNotificationPermission() },
        )

    private fun buildPermissionFlow(
        initialValue: Boolean,
        check: () -> Boolean,
    ): StateFlow<Boolean> =
        notifier.changes
            .map { check() }
            .stateIn(
                scope = scope,
                // Eagerly: flow is critical for UI correctness, stopping is unacceptable.
                started = SharingStarted.Eagerly,
                initialValue = initialValue,
            )
}
