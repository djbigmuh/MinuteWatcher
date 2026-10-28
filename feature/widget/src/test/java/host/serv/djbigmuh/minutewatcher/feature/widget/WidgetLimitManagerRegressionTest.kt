package host.serv.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import android.os.TransactionTooLargeException
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetLimitManagerRegressionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val renderer: WidgetRenderer = mock()
    private val retryManager = WidgetUpdateRetryManager()
    private val errorClassifier = WidgetErrorClassifier()

    @Before
    fun setup() {
        initTestLogger()
        context = mock()
    }

    @Test
    fun `regression - 10 consecutive updateAll in SIMPLIFIED mode results in exactly one PROBE attempt`() =
        runTest {
            val settings = AppSettings(widgetLimitExceeded = true)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(settings))
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableStateFlow(settings))

            val probeCalls = AtomicInteger(0)
            val simplifiedCalls = AtomicInteger(0)
            whenever(renderer.renderAll(any(), any())).thenAnswer {
                val forceFull = it.arguments[1] as Boolean
                if (forceFull) {
                    probeCalls.incrementAndGet()
                    throw IllegalArgumentException("limit", TransactionTooLargeException())
                } else {
                    simplifiedCalls.incrementAndGet()
                    Unit
                }
            }

            val limitManager = WidgetLimitManager(appSettingsRepository, backgroundScope)
            limitManager.start()
            runCurrent()

            val updater =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = appSettingsRepository,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = limitManager,
                    errorClassifier = errorClassifier,
                    scope = backgroundScope,
                )

            repeat(9) {
                updater.updateAll()
            }

            assertEquals("Expected 9 SIMPLIFIED updates", 9, simplifiedCalls.get())
            assertEquals("Expected 0 PROBE updates", 0, probeCalls.get())

            updater.updateAll()
            // 1st attempt: PROBE fails. 2nd attempt: SIMPLIFIED (because isFirstAttempt=false).
            assertEquals("10th update should attempt PROBE exactly once", 1, probeCalls.get())
            assertEquals("10th update should fallback to SIMPLIFIED on retry", 10, simplifiedCalls.get())
        }

    @Test
    fun `regression - counter does NOT increment multiple times during retries of a single update`() =
        runTest {
            val settings = AppSettings(widgetLimitExceeded = true)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(settings))
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableStateFlow(settings))

            val limitManager = WidgetLimitManager(appSettingsRepository, backgroundScope)
            limitManager.start()
            runCurrent()

            val updater =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = appSettingsRepository,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = limitManager,
                    errorClassifier = errorClassifier,
                    scope = backgroundScope,
                )

            whenever(renderer.renderAll(any(), any())).thenReturn(Unit)
            repeat(9) {
                updater.updateAll()
            }

            val callCount = AtomicInteger(0)
            val probeCalls = AtomicInteger(0)
            val simplifiedCalls = AtomicInteger(0)

            // 10th update: PROBE on 1st attempt. We mock generic transient failure.
            whenever(renderer.renderAll(any(), any())).thenAnswer {
                val forceFull = it.arguments[1] as Boolean
                if (forceFull) probeCalls.incrementAndGet() else simplifiedCalls.incrementAndGet()

                val current = callCount.incrementAndGet()
                if (current <= 2) throw IllegalArgumentException("Transient failure")
                Unit
            }

            updater.updateAll()
            // 1st attempt: PROBE -> fails.
            // 2nd attempt: SIMPLIFIED -> fails.
            // 3rd attempt: SIMPLIFIED -> succeeds.
            assertEquals("Should have attempted probe only once", 1, probeCalls.get())
            assertEquals("Should have attempted simplified twice during retries", 2, simplifiedCalls.get())

            // Now perform 11th update.
            limitManager.setWidgetLimitExceeded(true)
            runCurrent()

            val probeCallsNew = AtomicInteger(0)
            val simplifiedCallsNew = AtomicInteger(0)
            whenever(renderer.renderAll(any(), any())).thenAnswer {
                val forceFull = it.arguments[1] as Boolean
                if (forceFull) probeCallsNew.incrementAndGet() else simplifiedCallsNew.incrementAndGet()
                Unit
            }

            updater.updateAll()
            assertEquals("11th update should NOT be a probe", 0, probeCallsNew.get())
            assertEquals("11th update should be SIMPLIFIED", 1, simplifiedCallsNew.get())
        }
}
