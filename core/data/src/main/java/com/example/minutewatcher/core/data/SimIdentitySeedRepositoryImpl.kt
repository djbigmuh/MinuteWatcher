package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of [SimIdentitySeedRepository] using [DataStore].
 *
 * Provides a stable seed for generating unique SIM identities.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 */
@Singleton
class SimIdentitySeedRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
    ) : SimIdentitySeedRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        private val seedMutex = Mutex()

        private val _isEphemeralSeedActive = MutableStateFlow(false)
        override val isEphemeralSeedActive: StateFlow<Boolean> = _isEphemeralSeedActive.asStateFlow()

        @Volatile private var cachedEphemeralSeed: String? = null

        /**
         * Flow of the identity seed string.
         *
         * Emits an empty string if not yet initialized.
         * Safe to call from any coroutine context.
         */
        override val seedFlow: Flow<String> =
            dataStore.data
                .catch { e ->
                    AppLogger.e("DataStore error in seedFlow, emitting empty string", e)
                    emit(emptyPreferences())
                }.map { prefs ->
                    prefs[DataStoreKeys.SIM_IDENTITY_SEED] ?: ""
                }

        /**
         * Updates the identity seed string.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setSeed(seed: String) {
            seedMutex.withLock {
                dataStore.edit { it[DataStoreKeys.SIM_IDENTITY_SEED] = seed }
                cachedEphemeralSeed = null
                _isEphemeralSeedActive.value = false
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
         * **Защита от зависаний:** Чтение и запись в DataStore ограничены таймаутом [Constants.Flow.SHORT_TIMEOUT_MS].
         * Это гарантирует, что мьютекс [seedMutex] не будет заблокирован на неопределённое время при деградации
         * файловой системы или системных сервисов (актуально для кастомных прошивок).
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
                    val existingSeed =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            dataStore.data.first()[DataStoreKeys.SIM_IDENTITY_SEED]
                        } ?: ""

                    if (existingSeed.isNotEmpty()) {
                        cachedEphemeralSeed = null
                        _isEphemeralSeedActive.value = false
                        return@withLock existingSeed
                    }

                    val seed = generator()
                    generatedSeed = seed

                    val saveSuccess =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            dataStore.edit { it[DataStoreKeys.SIM_IDENTITY_SEED] = seed }
                        } != null

                    if (!saveSuccess) {
                        AppLogger.e("Timeout writing identity seed to DataStore")
                    }

                    cachedEphemeralSeed = if (saveSuccess) null else seed
                    _isEphemeralSeedActive.value = !saveSuccess
                    seed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to read/write identity seed, generating/using ephemeral seed", e)
                    // Используем уже сгенерированный в этом вызове seed, если он есть,
                    // либо ранее закэшированный эфемерный, либо создаем новый.
                    val fallback = generatedSeed ?: cachedEphemeralSeed ?: generator()
                    cachedEphemeralSeed = fallback
                    _isEphemeralSeedActive.value = true
                    fallback
                }
            }
    }
