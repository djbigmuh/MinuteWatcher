package com.github.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppCard
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing
import com.github.djbigmuh.minutewatcher.core.model.WidgetUiState
import com.github.djbigmuh.minutewatcher.core.resources.R
import com.github.djbigmuh.minutewatcher.feature.settings.ui.components.WidgetPreview

@Composable
fun BottomPreviewBar(uiState: WidgetUiState?) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = MwSpacing.md),
        contentPadding = PaddingValues(MwSpacing.lg),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = stringResource(id = R.string.preview),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = MwSpacing.sm),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            contentAlignment = Alignment.Center,
        ) {
            uiState?.let {
                WidgetPreview(
                    uiState = it,
                    modifier = Modifier.padding(MwSpacing.sm),
                )
            }
        }
    }
}
