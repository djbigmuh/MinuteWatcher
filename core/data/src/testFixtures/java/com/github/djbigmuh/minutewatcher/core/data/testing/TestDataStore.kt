package com.github.djbigmuh.minutewatcher.core.data.testing

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

fun createTestDataStore(initial: Preferences = emptyPreferences()): DataStore<Preferences> =
    InMemoryPreferencesDataStore(initial)

fun createTestFilePreferencesDataStore(
    tmpFolder: TemporaryFolder,
    scope: CoroutineScope,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = corruptionHandler,
        scope = scope,
        produceFile = { File(tmpFolder.root, "test_${UUID.randomUUID()}.preferences_pb") },
    )
