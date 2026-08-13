package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimIdentitySeedRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
    ) : SimIdentitySeedRepository {
        companion object {
            private val SIM_IDENTITY_SEED = stringPreferencesKey("sim_identity_seed")
        }

        private val seedMutex = Mutex()

        @Volatile private var cachedEphemeralSeed: String? = null

        override val seedFlow: Flow<String> =
            dataStore.data
                .catch { e ->
                    AppLogger.e("DataStore error in seedFlow, emitting empty string", e)
                    emit(emptyPreferences())
                }.map { prefs ->
                    prefs[SIM_IDENTITY_SEED] ?: ""
                }

        override suspend fun setSeed(seed: String) {
            seedMutex.withLock {
                dataStore.edit { it[SIM_IDENTITY_SEED] = seed }
                cachedEphemeralSeed = null
            }
        }

        /**
         * Возвращает существующий seed или генерирует и сохраняет новый.
         *
         * **Штатный путь:** seed читается/создаётся один раз и сохраняется в DataStore. Это обеспечивает стабильность
         * identity всех SIM-карт между перезапусками приложения, что критично для накопления статистики.
         *
         * **Error-путь (деградация):** При недоступности или повреждении DataStore (например, на кастомных прошивках
         * с некорректно работающим API хранилища) возвращается **эфемерный** seed. Этот seed не сохраняется.
         *
         * **ВАЖНО:** Использование эфемерного seed приводит к тому, что после перезапуска приложения будет
         * сгенерирован новый seed, вследствие чего все identity SIM-карт изменятся. Накопленная статистика
         * и лимиты для старых identity станут «осиротевшими». Это поведение является осознанным компромиссом
         * для сохранения работоспособности приложения при системных сбоях хранилища.
         *
         * Строгое соблюдение API: на AOSP DataStore работает стабильно. Проблемы с сохранением seed —
         * это проблема окружения (кастомной прошивки), а не логики приложения.
         *
         * @param generator Функция для генерации нового seed, если он отсутствует.
         * @return Стабильный seed из DataStore или эфемерный seed при сбое.
         */
        override suspend fun getOrCreateSeed(generator: () -> String): String =
            seedMutex.withLock {
                var generatedSeed: String? = null
                try {
                    val existingSeed = dataStore.data.first()[SIM_IDENTITY_SEED] ?: ""
                    if (existingSeed.isNotEmpty()) {
                        cachedEphemeralSeed = null
                        return@withLock existingSeed
                    }

                    val seed = generator()
                    generatedSeed = seed
                    dataStore.edit { it[SIM_IDENTITY_SEED] = seed }
                    cachedEphemeralSeed = null
                    seed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to read/write identity seed, generating/using ephemeral seed", e)
                    // Используем уже сгенерированный в этом вызове seed, если он есть,
                    // либо ранее закэшированный эфемерный, либо создаем новый.
                    val fallback = generatedSeed ?: cachedEphemeralSeed ?: generator()
                    cachedEphemeralSeed = fallback
                    fallback
                }
            }
    }
