package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.model.SchemaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SnapshotAssemblerTest {
    private val semanticVersion: SemanticVersion = mock()
    private val semanticCapabilities: SemanticCapabilities = mock()
    private val assembler = SnapshotAssembler()

    @Before
    fun setup() {
        whenever(semanticVersion.getVersion()).thenReturn(SchemaVersion(1, 0, 0))
        whenever(semanticCapabilities.getCapabilities()).thenReturn(emptyList())
    }

    @Test
    fun `assemble transfers flags for new eventId`() {
        val event = DataRefreshNotifier.RefreshEvent(isManual = true, suppressWidgetUpdate = true, eventId = 1L)
        val snapshot =
            assembler.assemble(
                emptyList(),
                event,
                1000L,
                semanticVersion,
                semanticCapabilities,
            ) { _, _ -> mock() }

        assertTrue(snapshot.isManualUpdate)
        assertTrue(snapshot.suppressWidgetUpdate)
    }

    @Test
    fun `assemble clears flags for same eventId`() {
        val event = DataRefreshNotifier.RefreshEvent(isManual = true, suppressWidgetUpdate = true, eventId = 1L)

        // First call
        assembler.assemble(emptyList(), event, 1000L, semanticVersion, semanticCapabilities) { _, _ -> mock() }

        // Second call with same eventId
        val snapshot2 =
            assembler.assemble(
                emptyList(),
                event,
                2000L,
                semanticVersion,
                semanticCapabilities,
            ) { _, _ -> mock() }

        assertFalse(snapshot2.isManualUpdate)
        assertFalse(snapshot2.suppressWidgetUpdate)
    }

    @Test
    fun `assemble transfers flags for subsequent different eventIds`() {
        val event1 = DataRefreshNotifier.RefreshEvent(isManual = true, suppressWidgetUpdate = true, eventId = 1L)
        val event2 = DataRefreshNotifier.RefreshEvent(isManual = true, suppressWidgetUpdate = true, eventId = 2L)

        val snapshot1 =
            assembler.assemble(
                emptyList(),
                event1,
                1000L,
                semanticVersion,
                semanticCapabilities,
            ) { _, _ -> mock() }
        assertTrue(snapshot1.isManualUpdate)

        val snapshot2 =
            assembler.assemble(
                emptyList(),
                event2,
                2000L,
                semanticVersion,
                semanticCapabilities,
            ) { _, _ -> mock() }
        assertTrue(snapshot2.isManualUpdate)
    }

    @Test
    fun `assemble increments revision on every call`() {
        val event = DataRefreshNotifier.RefreshEvent(eventId = 1L)

        val snapshot1 =
            assembler.assemble(
                emptyList(),
                event,
                1000L,
                semanticVersion,
                semanticCapabilities,
            ) { _, _ -> mock() }
        val snapshot2 =
            assembler.assemble(
                emptyList(),
                event,
                1000L,
                semanticVersion,
                semanticCapabilities,
            ) { _, _ -> mock() }
        val snapshot3 =
            assembler.assemble(
                emptyList(),
                event,
                1000L,
                semanticVersion,
                semanticCapabilities,
            ) { _, _ -> mock() }

        assertTrue(snapshot2.revision > snapshot1.revision)
        assertTrue(snapshot3.revision > snapshot2.revision)
        // Verify it increments even with same timestamp
        assertEquals(snapshot1.timestamp, snapshot2.timestamp)
    }
}
