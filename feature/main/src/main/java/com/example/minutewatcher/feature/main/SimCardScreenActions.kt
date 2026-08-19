package com.example.minutewatcher.feature.main

import androidx.compose.runtime.Stable
import com.example.minutewatcher.core.model.AppTheme

/**
 * Контейнер колбэков для экрана SIM-карт.
 * Аннотация @Stable гарантирует стабильность для Compose.
 * Все колбэки стабильны (не изменяются при рекомпозиции).
 *
 * @property onOpenSettings Открыть экран настроек виджета.
 * @property onAddWidget Показать инструкцию по добавлению виджета.
 * @property onThemeChange Изменить тему приложения.
 * @property onLanguageChange Изменить язык приложения.
 * @property onGrantPhonePermission Запросить разрешение на телефон (READ_PHONE_STATE).
 * @property onShowUsageAccessDialog Показать диалог доступа к использованию.
 * @property onOpenUsageAccessSettings Открыть системные настройки доступа к использованию.
 * @property onOpenAppSettings Открыть системные настройки приложения.
 * @property onGrantNotificationPermission Запросить разрешение на уведомления.
 */
@Stable
data class SimCardScreenActions(
    val onOpenSettings: () -> Unit,
    val onAddWidget: () -> Unit,
    val onThemeChange: (AppTheme) -> Unit,
    val onLanguageChange: (String?) -> Unit,
    val onGrantPhonePermission: () -> Unit,
    val onShowUsageAccessDialog: () -> Unit,
    val onOpenUsageAccessSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onGrantNotificationPermission: () -> Unit,
)
