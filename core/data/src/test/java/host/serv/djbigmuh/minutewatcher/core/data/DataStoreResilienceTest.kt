package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `AutoResetScheduleRepositoryImpl returns default on hanging DataStore`() =
        runTest {
            val hangingDs =
                mock<DataStore<Preferences>> {
                    on { data } doReturn MutableSharedFlow<Preferences>()
                }

            val repo = AutoResetScheduleRepositoryImpl(hangingDs)

            val checkMillis = repo.getLastCheckMillis()
            assertEquals("Should return null on hang", null, checkMillis)

            val failureCount = repo.getFailureCount()
            assertEquals("Should return default 0 on hang", 0, failureCount)
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
