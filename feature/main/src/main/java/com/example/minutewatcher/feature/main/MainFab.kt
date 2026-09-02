package com.example.minutewatcher.feature.main

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.minutewatcher.core.resources.R

@Composable
fun MainFab(onAddWidget: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onAddWidget,
    ) {
        Text(text = stringResource(id = R.string.add_widget_button))
    }
}
