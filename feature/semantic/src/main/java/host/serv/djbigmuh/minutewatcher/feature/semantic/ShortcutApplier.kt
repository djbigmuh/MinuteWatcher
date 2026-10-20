package host.serv.djbigmuh.minutewatcher.feature.semantic

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
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
                val result =
                    if (shortcuts.isNotEmpty()) {
                        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
                    } else {
                        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                        true
                    }
                if (!result) {
                    val message = "System rejected dynamic shortcuts update"
                    AppLogger.e(message)
                    throw IllegalStateException(message)
                }
            } catch (e: Exception) {
                if (e !is IllegalStateException) {
                    AppLogger.e("Failed to update dynamic shortcuts: ${e.javaClass.simpleName}", e)
                }
                throw e
            }
        }
    }
