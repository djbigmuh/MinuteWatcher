package host.serv.djbigmuh.minutewatcher.feature.semantic

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for applying shortcuts to the system in a traceable manner,
 * respecting platform limits.
 */
interface ShortcutApplier {
    /**
     * Applies the given shortcuts to the system.
     *
     * @param context Application context.
     * @param shortcuts List of shortcuts to apply.
     * @throws UnsupportedOperationException if shortcuts are not supported.
     * @throws IllegalStateException if the system rejects the shortcut update.
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
                if (shortcuts.isEmpty()) {
                    ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                    return
                }

                val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                val effectiveShortcuts =
                    if (shortcuts.size > maxCount) {
                        AppLogger.w("Truncating dynamic shortcuts from ${shortcuts.size} to max allowed $maxCount")
                        shortcuts.take(maxCount)
                    } else {
                        shortcuts
                    }

                ShortcutManagerCompat.removeAllDynamicShortcuts(context)

                for (shortcut in effectiveShortcuts) {
                    val success = ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                    if (!success) {
                        val message = "System rejected dynamic shortcut update for ID: ${shortcut.id}"
                        AppLogger.e(message)
                        throw IllegalStateException(message)
                    }
                }
            } catch (e: Exception) {
                if (e !is IllegalStateException && e !is UnsupportedOperationException) {
                    AppLogger.e("Failed to update dynamic shortcuts: ${e.javaClass.simpleName}", e)
                }
                throw e
            }
        }
    }
