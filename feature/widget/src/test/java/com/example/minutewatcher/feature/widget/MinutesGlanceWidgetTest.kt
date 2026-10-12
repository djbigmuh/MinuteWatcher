package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MinutesGlanceWidgetTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val usageStateRepository: UsageStateRepository = mock()
    private val timeProvider: TimeProvider = mock()

    @Test
    fun `getSnapshot returns Failure on repository exception`() =
        runTest {
            val initialSnapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
            val stateFlow = MutableStateFlow(initialSnapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            whenever(timeProvider.now()).thenReturn(200000L)

            val exception = IOException("Storage error")
            whenever(usageStateRepository.refresh()).thenThrow(exception)

            val widget = MinutesGlanceWidget()
            val result =
                withTimeout(5.seconds) {
                    widget.getSnapshot(usageStateRepository, timeProvider)
                }

            assertTrue("Result should be Failure", result is RefreshResult.Failure)
            val failure = result as RefreshResult.Failure
            assertEquals(initialSnapshot, failure.snapshot)
            assertEquals(exception, failure.cause)
        }
}
