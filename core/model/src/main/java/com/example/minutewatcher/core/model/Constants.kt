package com.example.minutewatcher.core.model

object Constants {
    const val PREFS_NAME = "app_prefs"

    object Notifications {
        const val CHANNEL_ID = "usage_monitor_channel"
    }

    object UI {
        const val DEFAULT_BG_COLOR: Int = 0xFF000000.toInt()
        const val LUMINANCE_THRESHOLD = 0.5f
        const val SWITCH_SCALE = 0.8f
        const val HIGHLIGHT_DELAY_MS = 3000L
    }

    object Widget {
        /** Стандартный размер шрифта для виджета (sp). */
        const val FONT_SIZE_MEDIUM = 14f

        /** Задержка debounce для реактивного обновления виджета (мс).
         * Предотвращает множественные обновления при быстрых изменениях данных. */
        const val DEBOUNCE_MS = 300L

        const val SIM_ARRANGEMENT_HORIZONTAL = 0

        /** Порог прозрачности (0-100), выше которого используются системные цвета.
         * Выбран как компромисс между читаемостью и эстетикой. */
        const val TRANSPARENCY_SYSTEM_COLORS_THRESHOLD = 80

        /** Количество SIM, при котором превентивно ограничивается layout.
         * На AOSP RemoteViews лимит ~1МБ. 3 SIM × 6 строк × ~500Б ≈ 9КБ — безопасно.
         * Упрощённый layout применяется при widgetLimitExceeded = true. */
        const val PREEMPTIVE_LAYOUT_LIMIT_SIMS = 3

        /** Минимальное количество SIM для отображения разделителя. */
        const val MIN_SIMS_FOR_SEPARATOR = 2

        const val PERCENT_MAX_FLOAT = 100f
        const val SEPARATOR_ALPHA = 0.5f

        const val PADDING_DEFAULT = 8
        const val PADDING_SIM_ITEM = 4
        const val CORNER_RADIUS = 16
        const val SEPARATOR_THICKNESS = 1
        const val ALPHA_FULL = 1f

        /** Начальная задержка перед повторной попыткой обновления виджета (мс).
         * Экспоненциальное удвоение: 500 → 1000 → 2000 → 4000. */
        const val RETRY_BACKOFF_MS = 500L

        /** Максимальная задержка между повторными попытками (мс). */
        const val MAX_RETRY_DELAY_MS = 5000L

        /** Максимальное количество попыток обновления виджета.
         * Ограничивает общее время ожидания: 500+1000+2000+4000 = 7.5с. */
        const val MAX_UPDATE_ATTEMPTS = 4
        // RACE_DELAY_MS removed: synchronization is provided by synchronous semantics of StateFlow.value

        const val MIN_LAYOUT_MODE = 0
        const val MAX_LAYOUT_MODE = 4
        const val MIN_SIM_ARRANGEMENT = 0
        const val MAX_SIM_ARRANGEMENT = 1
        const val MIN_TRANSPARENCY = 0
        const val MAX_TRANSPARENCY = 100
        const val MIN_FONT_SIZE = 0
        const val MAX_FONT_SIZE = 4
    }

    object Traffic {
        /**
         * Minimum change in traffic (in MB) required to trigger a repository update.
         * Used to filter out small measurement noise (e.g. background keep-alive packets).
         * 0.01 MB is approximately 10 KB.
         */
        const val MIN_DELTA_MB = 0.01f
    }

    object Data {
        const val SNAPSHOT_TIMEOUT_MS = 2000L
        const val STALE_SNAPSHOT_THRESHOLD_MS = 60_000L
        const val EMPTY_TIMESTAMP = 0L
    }

    object Semantic {
        const val THROTTLE_MS = 30_000L
        const val SEARCH_INDEX_TIMEOUT_MS = 10_000L
    }

    object Flow {
        /** Таймаут остановки WhileSubscribed (мс).
         * Достаточно для кратковременных поворотов экрана без перезапуска Flow. */
        const val WHILE_SUBSCRIBED_STOP_TIMEOUT_MS = 5_000L

        /** Общий debounce для UI-потоков (мс). */
        const val DEBOUNCE_MS = 300L

        /** Таймаут для "быстрых" операций (мс), таких как проверка наличия данных. */
        const val SHORT_TIMEOUT_MS = 5_000L

        /** Таймаут для "тяжелых" операций (мс), например, первичная генерация seed. */
        const val LONG_TIMEOUT_MS = 10_000L

        /**
         * Таймаут чтения сессии звонка из DataStore.
         * 2000 мс выбран как компромисс: достаточно для чтения 4 ключей
         * (callStartTime, bootTime, uptime, callIdentity) из DataStore
         * на AOSP, но не настолько велик, чтобы блокировать обработчик
         * звонков на длительное время. На AOSP чтение DataStore занимает
         * < 100 мс; таймаут срабатывает только при деградации хранилища.
         */
        const val CALL_SESSION_READ_TIMEOUT_MS = 2000L

        /** Таймаут ожидания первичной генерации seed идентичности (мс). */
        const val SEED_GENERATION_TIMEOUT_MS = 10_000L

        /** Задержка перед повторной попыткой при сетевых/дисковых сбоях (мс). */
        const val RETRY_DELAY_MS = 1_000L

        /** Дополнительное время ожидания для завершения всех фоновых задач при генерации seed. */
        const val SEED_TOTAL_TIMEOUT_EXTRA_MS = 3_000L

        /** Базовая задержка для повторных попыток наблюдения в UpdateCoordinator (мс). */
        const val WIDGET_RETRY_DELAY_MS = 5_000L
    }

    object Calls {
        /**
         * Heuristic to identify stale call sessions.
         * If a call session exists for more than 12 hours without an IDLE event,
         * it is considered stale and can be cleaned up during ambiguous IDLE events.
         */
        const val MAX_CALL_DURATION_MS = 12 * 60 * 60 * 1000L
    }

    object IntentExtras {
        const val SIM_SLOT = "sim_slot"
    }

    object SimConstants {
        const val MAX_SUPPORTED_SIM_SLOTS = 10
        const val MOCK_SUB_ID_BASE = 1000
        val SUPPORTED_SLOT_INDICES = 0 until MAX_SUPPORTED_SIM_SLOTS
        const val INVALID_SLOT_IDENTITY_PREFIX = "invalid_slot_"
    }

    const val MILLIS_PER_SECOND = 1000L
    const val SECONDS_PER_MINUTE = 60
    const val BYTES_PER_MB = 1024L * 1024L
    const val BYTES_PER_GB = 1024L * 1024L * 1024L
    const val MB_PER_GB = 1024.0f
    const val LOW_REMAINING_THRESHOLD_PERCENT = 0.20f
    const val CRITICAL_REMAINING_THRESHOLD_PERCENT = 0.10f
    const val MAX_MINUTES_LIMIT = 44640
    const val MAX_TRAFFIC_LIMIT_GB = 10240f
}
