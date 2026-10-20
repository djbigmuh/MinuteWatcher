package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import host.serv.djbigmuh.minutewatcher.core.common.di.AppDataStore
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreWriteTimeoutException
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetScheduleRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class AutoResetScheduleRepositoryImpl
    @Inject
    constructor(
        @AppDataStore dataStore: DataStore<Preferences>,
    ) : BaseDataStoreRepository(dataStore),
        AutoResetScheduleRepository {
        override suspend fun getLastCheckMillis(): Long? =
            getValue(DataStoreKeys.AUTO_RESET_LAST_CHECK_MILLIS, -1L).takeIf { it != -1L }

        override suspend fun setLastCheckMillis(valueMillis: Long) {
            val result =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataStore.edit { prefs ->
                        prefs[DataStoreKeys.AUTO_RESET_LAST_CHECK_MILLIS] = valueMillis
                    }
                }
            if (result == null) {
                AppLogger.w("AutoResetScheduleRepositoryImpl: setLastCheckMillis timed out")
                throw DataStoreWriteTimeoutException("setLastCheckMillis")
            }
        }

        override suspend fun getFailureCount(): Int = getValue(DataStoreKeys.AUTO_RESET_FAIL_COUNT, 0)

        override suspend fun setFailureCount(count: Int) {
            val result =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataStore.edit { prefs ->
                        prefs[DataStoreKeys.AUTO_RESET_FAIL_COUNT] = count
                    }
                }
            if (result == null) {
                AppLogger.w("AutoResetScheduleRepositoryImpl: setFailureCount timed out")
                throw DataStoreWriteTimeoutException("setFailureCount")
            }
        }
    }
