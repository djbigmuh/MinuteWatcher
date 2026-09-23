package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SimIdentityDiscoveryNoTelephonyTest {
    @Test
    fun `discoverSims returns Unsupported when subscriptionManager is null`() {
        val permissionManager: PermissionManager = mock()
        val notifier: PermissionStateNotifier = mock()
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        val deps =
            SimDiscoveryDependencies(
                context = mock(),
                subscriptionManager = null,
                permissionManager = permissionManager,
                permissionStateNotifier = notifier,
            )

        val discovery = SimIdentityDiscovery(deps)
        val result = discovery.discoverSims("seed")

        assertEquals(SimDiscoveryResult.Unsupported, result)
    }

    @Test
    fun `observeSubscriptionChanges emits Unit once and completes when subscriptionManager is null`() =
        runTest {
            val permissionManager: PermissionManager = mock()
            val notifier: PermissionStateNotifier = mock()

            val deps =
                SimDiscoveryDependencies(
                    context = mock(),
                    subscriptionManager = null,
                    permissionManager = permissionManager,
                    permissionStateNotifier = notifier,
                )

            val discovery = SimIdentityDiscovery(deps)
            val emissions = discovery.observeSubscriptionChanges().toList()

            assertEquals(listOf(Unit), emissions)
        }
}
