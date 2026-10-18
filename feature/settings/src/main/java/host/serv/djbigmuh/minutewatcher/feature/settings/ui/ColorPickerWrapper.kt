package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import host.serv.djbigmuh.minutewatcher.core.resources.R

/**
 * Wrapper for ColorPickerDialog using DisposableEffect to manage
 * dialog lifecycle.
 *
 * Behavior on configuration change (screen rotation):
 * DisposableEffect(Unit) executes once upon entering composition.
 * On configuration change composition is recreated, cleanup calls
 * dialog.dismiss(), and dialog disappears. This is acceptable behavior:
 * user can reopen dialog.
 *
 * onColorSelected is called before onDismiss, guaranteeing save of
 * selected color before closing dialog.
 * onDismiss is called exactly once via setOnDismissListener for all dismissal paths
 * (positive button, negative button, system dismissal, onDispose on configuration change).
 */
@Composable
fun ColorPickerWrapper(
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val dialog =
            ColorPickerDialog
                .Builder(context, R.style.Theme_MinuteWatcher)
                .setTitle(R.string.color_background)
                .setPositiveButton(
                    R.string.save_button,
                    ColorEnvelopeListener { envelope, _ ->
                        if (envelope != null) {
                            onColorSelected(envelope.color)
                        }
                    },
                ).setNegativeButton(R.string.cancel_button) { dialogInterface, _ ->
                    dialogInterface.dismiss()
                }.attachAlphaSlideBar(false)
                .attachBrightnessSlideBar(true)
                .setOnDismissListener {
                    onDismiss()
                }.show()

        onDispose {
            dialog.dismiss()
        }
    }
}
