package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreWriteTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionRepositoryTimeoutTest {
    private class HangingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = MutableSharedFlow()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            awaitCancellation()
        }
    }

    @Test
    fun `getActiveCallSubIds returns null on DataStore timeout`() =
        runTest {
            val repository = CallSessionRepositoryImpl(HangingDataStore())

            val result =
                withTimeout(10.seconds) {
                    repository.getActiveCallSubIds()
                }

            assertNull("Should return null on timeout", result)
        }

    @Test
    fun `saveCallSession throws DataStoreWriteTimeoutException on write timeout`() =
        runTest {
            val repository = CallSessionRepositoryImpl(HangingDataStore())

            val result =
                runCatching {
                    withTimeout(10.seconds) {
                        repository.saveCallSession(
                            subId = 1,
                            startTime = 1000L,
                            elapsedRealtime = 2000L,
                            uptime = 3000L,
                            identity = "sub1",
                        )
                    }
                }

            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreWriteTimeoutException, but was $exception",
                exception is DataStoreWriteTimeoutException,
            )
            assertEquals("DataStore write timeout (saveCallSession)", exception?.message)
        }

    @Test
    fun `clearCallSession throws DataStoreWriteTimeoutException on write timeout`() =
        runTest {
            val repository = CallSessionRepositoryImpl(HangingDataStore())

            val result =
                runCatching {
                    withTimeout(10.seconds) {
                        repository.clearCallSession(subId = 1)
                    }
                }

            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreWriteTimeoutException, but was $exception",
                exception is DataStoreWriteTimeoutException,
            )
            assertEquals("DataStore write timeout (clearCallSession)", exception?.message)
        }

    @Test
    fun `clearAllCallSessions throws DataStoreWriteTimeoutException on write timeout`() =
        runTest {
            val repository = CallSessionRepositoryImpl(HangingDataStore())

            val result =
                runCatching {
                    withTimeout(10.seconds) {
                        repository.clearAllCallSessions()
                    }
                }

            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreWriteTimeoutException, but was $exception",
                exception is DataStoreWriteTimeoutException,
            )
            assertEquals("DataStore write timeout (clearAllCallSessions)", exception?.message)
        }

    @Test
    fun `setLastDataSubId throws DataStoreWriteTimeoutException on write timeout`() =
        runTest {
            val repository = CallSessionRepositoryImpl(HangingDataStore())

            val result =
                runCatching {
                    withTimeout(10.seconds) {
                        repository.setLastDataSubId(subId = 1)
                    }
                }

            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreWriteTimeoutException, but was $exception",
                exception is DataStoreWriteTimeoutException,
            )
            assertEquals("DataStore write timeout (setLastDataSubId)", exception?.message)
        }
}
