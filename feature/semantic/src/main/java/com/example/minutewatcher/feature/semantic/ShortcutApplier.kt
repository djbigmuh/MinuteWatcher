package com.example.minutewatcher.feature.semantic

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.example.minutewatcher.core.common.util.AppLogger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for applying shortcuts to the system.
 */
interface ShortcutApplier {
    /**
     * Applies the given shortcuts to the system.
     *
     * @param context Application context.
     * @param shortcuts List of shortcuts to apply.
     * @throws UnsupportedOperationException if shortcuts are not supported.
     */
    fun apply(
        context: Context,
        shortcuts: List<ShortcutInfoCompat>,
    )
}

/**
 * Production implementation of [ShortcutApplier] using [ShortcutManagerCompat].
 */
@Singleton
class SystemShortcutApplier
@Inject
constructor() : ShortcutApplier {
    override fun apply(
        context: Context,
        shortcuts: List<ShortcutInfoCompat>,
    ) {
        try {
            if (shortcuts.isNotEmpty()) {
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            } else {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            AppLogger.e("Failed to update dynamic shortcuts: ${e.javaClass.simpleName}", e)
            throw e
        }
    }
}
