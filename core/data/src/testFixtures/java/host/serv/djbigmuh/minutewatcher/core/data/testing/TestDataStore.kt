package host.serv.djbigmuh.minutewatcher.core.data.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import host.serv.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

fun createTestDataStore(
    initial: Preferences = emptyPreferences(),
): DataStore<Preferences> = InMemoryPreferencesDataStore(initial)

fun createTestFilePreferencesDataStore(
    tmpFolder: TemporaryFolder,
    scope: CoroutineScope,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(tmpFolder.root, "${UUID.randomUUID()}.preferences_pb") },
    )
