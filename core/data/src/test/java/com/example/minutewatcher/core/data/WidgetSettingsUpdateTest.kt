package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsUpdateTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()

    private lateinit var repository: WidgetSettingsRepository

    @Before
    fun setup() {
        repository =
            WidgetSettingsRepository(
                appSettingsRepository,
                simIdentityRepository,
                simWidgetVisibilityRepository,
            )
    }

    @Test
    fun `save settings should succeed`() =
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
                    simStates = emptyMap(),
                )

            repository.save(state)
        }
}
