package com.example.minutewatcher.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.example.minutewatcher.core.resources.R
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

/**
 * Обёртка для ColorPickerDialog, использующая DisposableEffect для управления
 * жизненным циклом диалога.
 *
 * Поведение при изменении конфигурации (поворот экрана):
 * DisposableEffect(Unit) выполняется один раз при входе в композицию.
 * При изменении конфигурации композиция пересоздаётся, cleanup вызывает
 * dialog.dismiss(), и диалог исчезает. Это допустимое поведение:
 * пользователь может открыть диалог повторно.
 *
 * onColorSelected вызывается перед onDismiss, что гарантирует сохранение
 * выбранного цвета перед закрытием диалога.
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
                .Builder(context)
                .setTitle(R.string.color_background)
                .setPositiveButton(
                    R.string.save_button,
                    ColorEnvelopeListener { envelope, _ ->
                        onColorSelected(envelope.color)
                        onDismiss()
                    },
                ).setNegativeButton(R.string.cancel_button) { dialogInterface, _ ->
                    dialogInterface.dismiss()
                    onDismiss()
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
