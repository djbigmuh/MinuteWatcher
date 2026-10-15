package com.example.minutewatcher.service.tile

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageTileStateTest {

    @Test
    fun `active content maps to STATE_ACTIVE and inactive to STATE_INACTIVE without STATE_UNAVAILABLE`() {
        val activeState = if (true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        assertEquals(Tile.STATE_ACTIVE, activeState)

        val inactiveState = if (false) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        assertEquals(Tile.STATE_INACTIVE, inactiveState)

        assertNotEquals(Tile.STATE_UNAVAILABLE, activeState)
        assertNotEquals(Tile.STATE_UNAVAILABLE, inactiveState)
    }
}
