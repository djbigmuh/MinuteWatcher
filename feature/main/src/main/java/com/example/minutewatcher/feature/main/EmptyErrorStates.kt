package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.resources.R

/**
 * Отображается при отсутствии обнаруженных SIM-карт или отсутствии разрешений.
 * Информирует пользователя о том, что приложению не удалось получить доступ к данным SIM,
 * и предлагает перейти к предоставлению необходимых разрешений (READ_PHONE_STATE).
 *
 * Состав экрана:
 * - Заголовок: Сообщение о том, что SIM-карты не найдены.
 * - Описание: Пояснение необходимости проверки разрешений.
 * - Кнопка действия: Инициирует процесс запроса разрешений.
 *
 * @param onGrantPermission Колбэк для запроса разрешений на доступ к состоянию телефона.
 */
@Composable
fun EmptyState(onGrantPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.no_sim_found),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.check_permissions_sim),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onGrantPermission) {
            Text(text = stringResource(id = R.string.action_grant))
        }
    }
}

/**
 * Отображается при возникновении критической ошибки загрузки данных или сбоя репозитория.
 * Позволяет пользователю увидеть описание ошибки и предпринять попытку повторного получения данных.
 *
 * Состав экрана:
 * - Заголовок: Стандартное сообщение об ошибке.
 * - Описание: Детализированный текст ошибки, переданный через параметр [message].
 * - Кнопка действия: Повторный запуск процесса загрузки данных через [onRetry].
 *
 * @param message Текстовое описание возникшей ошибки для пользователя.
 * @param onRetry Колбэк для повторной попытки загрузки данных или сброса состояния ошибки.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.error),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(id = R.string.action_retry))
        }
    }
}
