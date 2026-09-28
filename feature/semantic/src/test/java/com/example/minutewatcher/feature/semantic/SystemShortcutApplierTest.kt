package com.example.minutewatcher.feature.semantic

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.test.core.app.ApplicationProvider
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
    fun `apply with non empty shortcuts succeeds without exception`() {
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, "test_shortcut")
                .setShortLabel("Label")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .build()

        applier.apply(context, listOf(shortcut))
    }

    @Test
    fun `apply with empty shortcuts succeeds without exception`() {
        applier.apply(context, emptyList())
    }
}
