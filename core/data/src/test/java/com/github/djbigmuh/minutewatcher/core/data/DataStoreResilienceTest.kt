package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.data.util.DataStoreReadTimeoutException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `AutoResetScheduleRepositoryImpl throws DataStoreReadTimeoutException on hanging DataStore`() =
        runTest {
            val hangingDs =
                mock<DataStore<Preferences>> {
                    on { data } doReturn MutableSharedFlow<Preferences>()
                }

            val repo = AutoResetScheduleRepositoryImpl(hangingDs)

            val result = runCatching { repo.getLastCheckMillis() }
            assertTrue(
                "Should throw DataStoreReadTimeoutException on hang, but was ${result.exceptionOrNull()}",
                result.exceptionOrNull() is DataStoreReadTimeoutException,
            )
        }

    @Test
    fun `CallSessionRepositoryImpl returns null active call subIds on hang`() =
        runTest {
            val hangingDs =
                mock<DataStore<Preferences>> {
                    on { data } doReturn MutableSharedFlow<Preferences>()
                }

            val repo = CallSessionRepositoryImpl(hangingDs)
            val subIds = repo.getActiveCallSubIds()
            assertEquals("Should return null on hang", null, subIds)
        }
}
