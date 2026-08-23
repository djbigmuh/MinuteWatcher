package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SimUsageRepositoryConcurrencyTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val timeProvider = FixedTimeProvider(currentTime = 1000000000000L)

    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `resetSimUsageIfDue handles concurrent calls by performing reset only once`() =
        runTest {
            // 1. Генерируем уникальную директорию и имя файла.
            // Использование отдельной директории гарантирует изоляцию от других тестов
            // и стабильность работы файловой системы (особенно на Windows).
            val dir = tmpFolder.newFolder()
            val uniqueName = "test_concurrency_${UUID.randomUUID()}.preferences_pb"
            val file = File(dir, uniqueName)

            // 2. Создаем СТРОГО ОДИН экземпляр DataStore и Repository
            // Используем backgroundScope для корректного ожидания фоновых задач DataStore
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { file },
                )
            val repository = SimUsageRepositoryImpl(dataStore, timeProvider, SimUsageMapper(), SimUsageResetLogic())

            val identity = "test_sim"
            val autoResetDay = 1
            val now = 1000000000000L

            // Подготовка данных
            repository.setLastResetDate(identity, 0L)
            repository.setMinutesUsed(identity, 50)

            // 3. Запускаем конкурентные вызовы на ОДНОМ И ТОМ ЖЕ экземпляре репозитория
            val concurrentCalls = 10
            val results =
                (1..concurrentCalls)
                    .map {
                        async {
                            repository.resetSimUsageIfDue(identity, autoResetDay, now)
                        }
                    }.awaitAll()

            // Даем время DataStore завершить фоновые операции переименования файлов
            testScheduler.advanceUntilIdle()

            val resetCount = results.count { it }
            assertEquals("Should only perform reset once", 1, resetCount)

            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(now, usage.lastResetDate)
        }
}
