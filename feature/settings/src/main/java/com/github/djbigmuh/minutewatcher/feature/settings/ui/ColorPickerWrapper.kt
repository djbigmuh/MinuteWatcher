@file:Suppress("MatchingDeclarationName") // Multi-composable file

package com.github.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

class ColorPickerController(
    initialColor: Int,
) {
    var selectedColor by mutableStateOf(Color(initialColor))
        internal set
}

@Composable
fun rememberColorPickerController(initialColor: Int): ColorPickerController =
    remember(initialColor) {
        ColorPickerController(initialColor)
    }

@Composable
fun HsvColorPicker(
    controller: ColorPickerController,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(8.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(controller.selectedColor),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
        ) {
            val colors =
                listOf(
                    Color.Red,
                    Color.Green,
                    Color.Blue,
                    Color.Yellow,
                    Color.Cyan,
                    Color.Magenta,
                    Color.White,
                    Color.Black,
                )
            for (c in colors) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .height(40.dp)
                            .background(c)
                            .clickable {
                                controller.selectedColor = c
                            },
                )
            }
        }
    }
}

@Composable
fun ColorPickerWrapper(
    initialColor: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val controller = rememberColorPickerController(initialColor)
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
            HsvColorPicker(
                controller = controller,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val colorWithAlpha = controller.selectedColor.copy(alpha = 1f).toArgb()
                    onColorSelected(colorWithAlpha)
                    dismissOnce()
                },
            ) {
                Text(stringResource(id = R.string.save_button))
            }
        },
        dismissButton = {
            Button(
                onClick = {
                    dismissOnce()
                },
            ) {
                Text(stringResource(id = R.string.cancel_button))
            }
        },
    )
}
