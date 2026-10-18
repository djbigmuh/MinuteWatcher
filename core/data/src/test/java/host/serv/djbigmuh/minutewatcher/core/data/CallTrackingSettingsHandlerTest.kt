package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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

    @Test
    fun `writeCallDuration with notify and fallback increments inaccurate count and notifies data changed`() =
        runTest {
            whenever(simUsageRepository.incrementMinutesUsedForCall("id_1", 5, "1000|2000"))
                .thenReturn(true)

            handler.writeCallDuration(
                identity = "id_1",
                durationMinutes = 5,
                isFallbackUsed = true,
                notify = true,
                callSignature = "1000|2000",
            )

            verify(dataChangeNotifier, times(1)).notifyDataChanged()
            verify(appSettingsRepository, times(1)).updateAppSettings(any())
        }

    @Test
    fun `writeCallDuration without notify and without fallback does not notify or update settings`() =
        runTest {
            whenever(simUsageRepository.incrementMinutesUsedForCall("id_1", 5, "1000|2000"))
                .thenReturn(true)

            handler.writeCallDuration(
                identity = "id_1",
                durationMinutes = 5,
                isFallbackUsed = false,
                notify = false,
                callSignature = "1000|2000",
            )

            verify(dataChangeNotifier, never()).notifyDataChanged()
            verify(appSettingsRepository, never()).updateAppSettings(any())
        }

    @Test
    fun `writeCallDuration for duplicate signature returns early without notifying or updating settings`() =
        runTest {
            whenever(simUsageRepository.incrementMinutesUsedForCall("id_1", 5, "1000|2000"))
                .thenReturn(false)

            handler.writeCallDuration(
                identity = "id_1",
                durationMinutes = 5,
                isFallbackUsed = true,
                notify = true,
                callSignature = "1000|2000",
            )

            verify(dataChangeNotifier, never()).notifyDataChanged()
            verify(appSettingsRepository, never()).updateAppSettings(any())
        }
}
