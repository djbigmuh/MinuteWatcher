package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SimNotificationFlagsRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    private fun createRepository(): SimNotificationFlagsRepositoryImpl {
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { File(tmpFolder.root, "test_${java.util.UUID.randomUUID()}.preferences_pb") },
            )
        return SimNotificationFlagsRepositoryImpl(dataStore)
    }

    @Test
    fun `default values are false for all flags`() =
        runTest {
            val repo = createRepository()
            val id = "sim_1"

            assertEquals(false, repo.getNotifiedLowMinutes(id).first())
            assertEquals(false, repo.getNotifiedLowTraffic(id).first())
            assertEquals(false, repo.getNotifiedExhaustedMinutes(id).first())
            assertEquals(false, repo.getNotifiedExhaustedTraffic(id).first())
            assertEquals(false, repo.getNotifiedCriticalMinutes(id).first())
            assertEquals(false, repo.getNotifiedCriticalTraffic(id).first())
        }

    @Test
    fun `set and get work independently for all flags and identities`() =
        runTest {
            val repo = createRepository()
            val id1 = "sim_1"
            val id2 = "sim_2"

            repo.setNotifiedLowMinutes(id1, true)
            repo.setNotifiedLowTraffic(id1, true)
            repo.setNotifiedExhaustedMinutes(id1, true)
            repo.setNotifiedExhaustedTraffic(id1, true)
            repo.setNotifiedCriticalMinutes(id1, true)
            repo.setNotifiedCriticalTraffic(id1, true)

            assertEquals(true, repo.getNotifiedLowMinutes(id1).first())
            assertEquals(true, repo.getNotifiedLowTraffic(id1).first())
            assertEquals(true, repo.getNotifiedExhaustedMinutes(id1).first())
            assertEquals(true, repo.getNotifiedExhaustedTraffic(id1).first())
            assertEquals(true, repo.getNotifiedCriticalMinutes(id1).first())
            assertEquals(true, repo.getNotifiedCriticalTraffic(id1).first())

            // id2 should still be false
            assertEquals(false, repo.getNotifiedLowMinutes(id2).first())
            assertEquals(false, repo.getNotifiedCriticalMinutes(id2).first())

            repo.setNotifiedCriticalMinutes(id1, false)
            assertEquals(false, repo.getNotifiedCriticalMinutes(id1).first())
        }
}
