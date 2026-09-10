package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionRepositoryTimeoutTest {
    @Test
    fun `getActiveCallSubIds returns null on DataStore timeout`() =
        runTest {
            // 1. Setup a DataStore that hangs (Flow that never emits)
            val hangingFlow = MutableSharedFlow<Preferences>()
            val dataStore = mock<DataStore<Preferences>>()
            whenever(dataStore.data).thenReturn(hangingFlow)

            val repository = CallSessionRepositoryImpl(dataStore)

            // 2. Call the method. It should timeout after Constants.Flow.SHORT_TIMEOUT_MS
            // In runTest, time is virtual, so it will trigger the timeout quickly.
            val result = repository.getActiveCallSubIds()

            // 3. Verify it returns null
            Assert.assertNull("Should return null on timeout", result)
        }
}
