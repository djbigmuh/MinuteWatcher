package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsUpdateTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()

    private lateinit var repository: WidgetSettingsRepository

    @Before
    fun setup() {
        repository =
            WidgetSettingsRepository(
                appSettingsRepository,
                simIdentityRepository,
                simWidgetVisibilityRepository,
                dataChangeNotifier,
            )
    }

    @Test
    fun `save settings triggers widget update`() =
        runTest {
            val state =
                WidgetSettingsState(
                    layoutMode = 1,
                    simArrangement = 1,
                    transparency = 0,
                    fontSize = 12,
                    backgroundColor = 0,
                    notifyLowMinutes = true,
                    notifyLowTraffic = true,
                    callTrackingEnabled = true,
                    simStates = emptyMap(),
                )

            repository.save(state)

            verify(dataChangeNotifier).notifyDataChanged(eq(true), any())
        }
}
