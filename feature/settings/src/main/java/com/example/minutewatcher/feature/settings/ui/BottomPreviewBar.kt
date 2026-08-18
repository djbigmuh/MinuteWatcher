package com.example.minutewatcher.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.settings.ui.components.WidgetPreview
import kotlinx.coroutines.launch

@Composable
fun BottomPreviewBar(
    uiState: WidgetUiState?,
    isSaving: Boolean,
    /**
     * suspend-функция сохранения настроек. Вызывается в CoroutineScope, созданном внутри BottomPreviewBar.
     * Возвращает [SaveResult] для обработки результата.
     */
    onSave: suspend () -> SaveResult,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .navigationBarsPadding()
                .padding(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.preview),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
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

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    onSave()
                    // Результат обрабатывается через LaunchedEffect(saveResult)
                    // для Success/Failed. AlreadyInProgress возвращается напрямую.
                }
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(id = R.string.save_button))
        }
    }
}
