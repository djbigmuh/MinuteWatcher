package com.github.djbigmuh.minutewatcher.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppExtendedFab
import com.github.djbigmuh.minutewatcher.core.resources.R

@Composable
fun MainFab(onAddWidget: () -> Unit) {
    AppExtendedFab(
        text = stringResource(id = R.string.add_widget_button),
        onClick = onAddWidget,
    )
}
