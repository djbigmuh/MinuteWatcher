package com.github.djbigmuh.minutewatcher.feature.semantic

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemShortcutApplierTest {
    private lateinit var context: Context
    private lateinit var applier: SystemShortcutApplier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        applier = SystemShortcutApplier()
    }

    @Test
    fun `apply with shortcuts publishes them as dynamic shortcuts`() {
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, "test_shortcut")
                .setShortLabel("Label")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .build()

        applier.apply(context, listOf(shortcut))

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(1, shortcuts.size)
        assertEquals("test_shortcut", shortcuts[0].id)
        assertEquals("Label", shortcuts[0].shortLabel)
    }

    @Test
    fun `apply with empty list removes all dynamic shortcuts`() {
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, "test_shortcut")
                .setShortLabel("Label")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .build()

        applier.apply(context, listOf(shortcut))
        assertEquals(1, ShortcutManagerCompat.getDynamicShortcuts(context).size)

        applier.apply(context, emptyList())

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertTrue(shortcuts.isEmpty())
    }
}
