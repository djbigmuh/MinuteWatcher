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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
     */
    onSave: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isSavingLocal = remember { mutableStateOf(value = false) }

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
        // Высота 120.dp обеспечивает фиксированную высоту превью.
        // background(MaterialTheme.colorScheme.surface) обеспечивает фон превью.
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

        // Button "Сохранить" вызывает onSave в rememberCoroutineScope().
        // enabled = !isSaving && !isSavingLocal.value предотвращает повторные нажатия во время сохранения.
        // modifier = Modifier.fillMaxWidth() обеспечивает полную ширину кнопки.
        Button(
            onClick = {
                if (!isSavingLocal.value) {
                    isSavingLocal.value = true
                    // onSave вызывается в rememberCoroutineScope(), привязанном к lifecycle
                    // композабла. Если пользователь уходит с экрана во время сохранения,
                    // корутина отменяется. Сохранение может не завершиться.
                    // saveMutex освобождается в finally-блоке, что гарантирует отсутствие deadlock.
                    // Это допустимое поведение: пользователь может вернуться и сохранить повторно.
                    scope.launch {
                        try {
                            onSave()
                        } finally {
                            isSavingLocal.value = false
                        }
                        // Результат обрабатывается через LaunchedEffect(saveResult)
                        // для Success/Failed. AlreadyInProgress возвращается напрямую.
                    }
                }
            },
            enabled = !isSaving && !isSavingLocal.value,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(id = R.string.save_button))
        }
    }
}
