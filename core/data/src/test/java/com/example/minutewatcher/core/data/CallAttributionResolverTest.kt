package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallAttributionResolverTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val dataChangeNotifier =
        object : DataChangeNotifier {
            override suspend fun notifyDataChanged(
                updateWidget: Boolean,
                force: Boolean,
            ): Boolean = true
        }

    private lateinit var resolver: CallAttributionResolver

    @Before
    fun setUp() {
        resolver =
            CallAttributionResolver(
                simIdentityRepository = simIdentityRepository,
                permissionManager = permissionManager,
                dataChangeNotifier = dataChangeNotifier,
            )
    }

    @Test
    fun `concurrent attribution failures increment counter atomically without lost updates`() =
        runBlocking {
            val subId = 1
            val coroutineCount = 8
            val iterationsPerCoroutine = 250

            val jobs =
                List(coroutineCount) {
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) {
                            resolver.handleAttributionFailure(
                                subId = subId,
                                updateStatus = {},
                                cleanupAction = {},
                            )
                        }
                    }
                }

            withTimeout(10.seconds) {
                jobs.joinAll()
            }

            assertEquals(coroutineCount * iterationsPerCoroutine, resolver.getFailureCount(subId))
        }
}
