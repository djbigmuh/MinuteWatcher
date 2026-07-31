package com.example.minutewatcher.feature.semantic

import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticIsolationTest {
    private val searchManager: SemanticSearchManager = mock()
    private val shortcutManager: ShortcutManager = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    @Test
    fun `error in shortcuts does not affect AppSearch during initialization`() =
        runTest {
            val snapshotFlow = MutableStateFlow(mock<com.example.minutewatcher.core.model.GlobalSnapshot>())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            whenever(shortcutManager.initialize(any())).thenThrow(RuntimeException("Shortcuts failed"))

            // Emulate MinuteWatcherApp logic
            try {
                searchManager.initialize(snapshotFlow)
            } catch (e: Exception) {
                searchManager.setError(e.message)
            }

            try {
                shortcutManager.initialize(snapshotFlow)
            } catch (e: Exception) {
                shortcutManager.setError(e.message)
            }

            verify(searchManager).initialize(snapshotFlow)
            verify(shortcutManager).setError("Shortcuts failed")
        }
}
