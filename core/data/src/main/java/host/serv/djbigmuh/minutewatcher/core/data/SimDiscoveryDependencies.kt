package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles dependencies required for SIM discovery and subscription monitoring.
 * This helps reduce the number of parameters in [SimIdentityRepositoryImpl].
 */
@Singleton
class SimDiscoveryDependencies
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        val subscriptionManager: SubscriptionManager?,
        val permissionManager: PermissionManager,
        val permissionStateNotifier: PermissionStateNotifier,
    )
