package com.example.minutewatcher.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.settings.ui.components.WidgetPreview

@Composable
fun BottomPreviewBar(
    uiState: WidgetUiState?,
    isSaving: Boolean,
) {
    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .navigationBarsPadding()
                .padding(16.dp),
    ) {
        // Text используется для заголовков и меток настроек.
        // style = MaterialTheme.typography.titleSmall для заголовков секций.
        // style = MaterialTheme.typography.bodyMedium для меток.
        Text(
            text = stringResource(id = R.string.preview),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Box обеспечивает центрирование превью виджета.
        // contentAlignment = Alignment.Center центрирует содержимое.
        // Динамическая высота с ограничением до 350.dp позволяет вместить 6-строчный режим.
        // background(MaterialTheme.colorScheme.surface) обеспечивает фон превью.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 350.dp)
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            uiState?.let {
                WidgetPreview(
                    uiState = it,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
