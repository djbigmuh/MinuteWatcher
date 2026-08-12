package com.example.minutewatcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object NavigationIntentFactory {
    fun getAppSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * Возвращает Intent для открытия настроек доступа к данным об использовании.
     *
     * Передача package URI обязательна для открытия настроек конкретного приложения
     * на актуальных Android/OEM; без URI ряд оболочек блокирует implicit-intent.
     * Поведение при отсутствии разрешения на конкретном устройстве — проблема OEM-оболочки,
     * приложение обязано предоставить fallback.
     */
    fun getUsageAccessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
}
