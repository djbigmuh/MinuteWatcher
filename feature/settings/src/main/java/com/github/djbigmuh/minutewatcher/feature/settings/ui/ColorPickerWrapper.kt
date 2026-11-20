@file:Suppress("MatchingDeclarationName") // Multi-composable file

package com.github.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.resources.R
import com.github.skydoves.colorpicker.compose.AlphaSlider
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController

@Composable
fun ColorPickerWrapper(
    initialColor: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val controller = rememberColorPickerController()
    var dismissedCalled by remember { mutableStateOf(false) }

    fun dismissOnce() {
        if (!dismissedCalled) {
            dismissedCalled = true
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { dismissOnce() },
        title = { Text(stringResource(id = R.string.color_background)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
            ) {
                HsvColorPicker(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    controller = controller,
                    initialColor = Color(initialColor),
                )
                Spacer(modifier = Modifier.height(16.dp))
                BrightnessSlider(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(35.dp),
                    controller = controller,
                )
                Spacer(modifier = Modifier.height(8.dp))
                AlphaSlider(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(35.dp),
                    controller = controller,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val colorInt = controller.selectedColor.value.toArgb()
                    onColorSelected(colorInt)
                    dismissOnce()
                },
            ) {
                Text(stringResource(id = R.string.save_button))
            }
        },
        dismissButton = {
            Button(
                onClick = { dismissOnce() },
            ) {
                Text(stringResource(id = R.string.cancel_button))
            }
        },
    )
}
