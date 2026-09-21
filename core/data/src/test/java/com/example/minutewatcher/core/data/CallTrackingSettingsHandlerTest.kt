package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackingSettingsHandlerTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val retryHelper = DataStoreRetryHelper()

    private lateinit var handler: CallTrackingSettingsHandler

    @Before
    fun setup() {
        handler =
            CallTrackingSettingsHandler(
                appSettingsRepository = appSettingsRepository,
                simUsageRepository = simUsageRepository,
                dataChangeNotifier = dataChangeNotifier,
                retryHelper = retryHelper,
            )
    }

    @Test
    fun `isCallTrackingEnabled returns false when settings say false`() =
        runTest {
            whenever(appSettingsRepository.getAppSettings()).thenReturn(
                flowOf(AppSettings(callTrackingEnabled = false, isLoaded = true)),
            )

            val result = handler.isCallTrackingEnabled()

            assertFalse(result)
        }

    @Test(expected = IOException::class)
    fun `isCallTrackingEnabled throws IOException on timeout`() =
        runTest {
            // Mock timeout: flow that never emits
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())

            handler.isCallTrackingEnabled()
        }
}
