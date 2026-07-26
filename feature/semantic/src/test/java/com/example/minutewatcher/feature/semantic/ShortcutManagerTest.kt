package com.example.minutewatcher.feature.semantic

import android.content.Context
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class ShortcutManagerTest {
    private val context: Context = mock()
    private val testScope = TestScope()
    private lateinit var shortcutManager: ShortcutManager

    @Before
    fun setUp() {
        shortcutManager = ShortcutManager(context, testScope)
    }

    @Test
    fun close_doesNotCancelApplicationScope() =
        runTest {
            val snapshotFlow =
                MutableStateFlow(
                    GlobalSnapshot(
                        version = SchemaVersion(1, 0, 0),
                        timestamp = System.currentTimeMillis(),
                        simSnapshots = emptyList(),
                        capabilities = emptyList(),
                    ),
                )

            shortcutManager.initialize(snapshotFlow)
            shortcutManager.close()

            assertTrue("Scope should still be active after close()", testScope.isActive)
        }
}
