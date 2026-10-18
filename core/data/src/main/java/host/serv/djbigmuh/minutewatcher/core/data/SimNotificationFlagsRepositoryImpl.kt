package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import host.serv.djbigmuh.minutewatcher.core.common.di.SimDataStore
import host.serv.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SimNotificationFlagsRepository] using [DataStore].
 *
 * Tracks if specific usage notifications have been shown for SIM identities.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 */
@Singleton
class SimNotificationFlagsRepositoryImpl
@Inject
constructor(
    @SimDataStore dataStore: DataStore<Preferences>,
) : BaseDataStoreRepository(dataStore),
    SimNotificationFlagsRepository {
    // DataStore keys are an implementation detail. The public repository API must not expose them.
    // This complies with the Dependency Inversion Principle.

    /**
     * Returns a flow indicating if low minutes notification was shown for the identity.
     *
     * Safe to call from any coroutine context.
     */
    override fun getNotifiedLowMinutes(identity: String): Flow<Boolean> =
        getData(DataStoreKeys.notifiedLowMinutesKey(identity), defaultValue = false)

    /**
     * Sets the low minutes notification flag.
     *
     * Safe to call from any coroutine context.
     * @throws java.io.IOException if write fails.
     */
    override suspend fun setNotifiedLowMinutes(
        identity: String,
        notified: Boolean,
    ) {
        dataStore.edit { it[DataStoreKeys.notifiedLowMinutesKey(identity)] = notified }
    }

    /**
     * Returns a flow indicating if low traffic notification was shown for the identity.
     *
     * Safe to call from any coroutine context.
     */
    override fun getNotifiedLowTraffic(identity: String): Flow<Boolean> =
        getData(DataStoreKeys.notifiedLowTrafficKey(identity), defaultValue = false)

    /**
     * Sets the low traffic notification flag.
     *
     * Safe to call from any coroutine context.
     * @throws java.io.IOException if write fails.
     */
    override suspend fun setNotifiedLowTraffic(
        identity: String,
        notified: Boolean,
    ) {
        dataStore.edit { it[DataStoreKeys.notifiedLowTrafficKey(identity)] = notified }
    }

    /**
     * Returns a flow indicating if exhausted minutes notification was shown for the identity.
     *
     * Safe to call from any coroutine context.
     */
    override fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean> =
        getData(DataStoreKeys.notifiedExhaustedMinutesKey(identity), defaultValue = false)

    /**
     * Sets the exhausted minutes notification flag.
     *
     * Safe to call from any coroutine context.
     * @throws java.io.IOException if write fails.
     */
    override suspend fun setNotifiedExhaustedMinutes(
        identity: String,
        notified: Boolean,
    ) {
        dataStore.edit { it[DataStoreKeys.notifiedExhaustedMinutesKey(identity)] = notified }
    }

    /**
     * Returns a flow indicating if exhausted traffic notification was shown for the identity.
     *
     * Safe to call from any coroutine context.
     */
    override fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean> =
        getData(DataStoreKeys.notifiedExhaustedTrafficKey(identity), defaultValue = false)

    /**
     * Sets the exhausted traffic notification flag.
     *
     * Safe to call from any coroutine context.
     * @throws java.io.IOException if write fails.
     */
    override suspend fun setNotifiedExhaustedTraffic(
        identity: String,
        notified: Boolean,
    ) {
        dataStore.edit { it[DataStoreKeys.notifiedExhaustedTrafficKey(identity)] = notified }
    }

    override fun getNotifiedCriticalMinutes(identity: String): Flow<Boolean> =
        getData(DataStoreKeys.notifiedCriticalMinutesKey(identity), defaultValue = false)

    override suspend fun setNotifiedCriticalMinutes(
        identity: String,
        notified: Boolean,
    ) {
        dataStore.edit { it[DataStoreKeys.notifiedCriticalMinutesKey(identity)] = notified }
    }

    override fun getNotifiedCriticalTraffic(identity: String): Flow<Boolean> =
        getData(DataStoreKeys.notifiedCriticalTrafficKey(identity), defaultValue = false)

    override suspend fun setNotifiedCriticalTraffic(
        identity: String,
        notified: Boolean,
    ) {
        dataStore.edit { it[DataStoreKeys.notifiedCriticalTrafficKey(identity)] = notified }
    }
}
