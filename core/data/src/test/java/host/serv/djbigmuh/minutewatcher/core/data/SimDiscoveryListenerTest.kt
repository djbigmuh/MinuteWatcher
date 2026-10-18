package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimDiscoveryListenerTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val permissionChangesFlow = MutableSharedFlow<Unit>(replay = 1)
    private val mainExecutor: Executor =
        mock {
            on { execute(any()) }.thenAnswer { (it.arguments[0] as Runnable).run() }
        }

    private lateinit var discovery: SimIdentityDiscovery

    @Before
    fun setup() {
        whenever(context.mainExecutor).thenReturn(mainExecutor)
        whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)

        val deps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
        discovery = SimIdentityDiscovery(deps)
    }

    @Test
    fun `observeSubscriptionChanges registers listener once when permission present`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

            val job =
                launch {
                    discovery.observeSubscriptionChanges().collect { }
                }
            runCurrent()

            verify(subscriptionManager, times(1)).addOnSubscriptionsChangedListener(any<Executor>(), any())
            job.cancel()
        }

    @Test
    fun `observeSubscriptionChanges unregisters listener on close`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

            val job =
                launch {
                    discovery.observeSubscriptionChanges().collect { }
                }
            runCurrent()
            verify(subscriptionManager, atLeastOnce()).addOnSubscriptionsChangedListener(any<Executor>(), any())

            job.cancel()
            advanceUntilIdle()

            verify(subscriptionManager).removeOnSubscriptionsChangedListener(any())
        }

    @Test
    fun `observeSubscriptionChanges handles permission changes`() =
        runTest {
            // 1. Initially no permission
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            val job =
                launch {
                    discovery.observeSubscriptionChanges().collect { }
                }
            runCurrent()
            verify(subscriptionManager, never()).addOnSubscriptionsChangedListener(any<Executor>(), any())

            // 2. Permission granted
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            permissionChangesFlow.emit(Unit)
            runCurrent()
            verify(subscriptionManager, times(1)).addOnSubscriptionsChangedListener(any<Executor>(), any())

            // 3. Permission revoked
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            permissionChangesFlow.emit(Unit)
            runCurrent()
            verify(subscriptionManager, times(1)).removeOnSubscriptionsChangedListener(any())

            job.cancel()
        }

    @Test
    fun `observeSubscriptionChanges unregisters even if not previously registered`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val job =
                launch {
                    discovery.observeSubscriptionChanges().collect { }
                }
            runCurrent()
            verify(subscriptionManager, never()).addOnSubscriptionsChangedListener(any<Executor>(), any())

            job.cancel()
            advanceUntilIdle()

            // Unconditional remove in awaitClose
            verify(subscriptionManager).removeOnSubscriptionsChangedListener(any())
        }
}
