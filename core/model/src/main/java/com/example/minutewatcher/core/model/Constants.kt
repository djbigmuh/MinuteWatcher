package com.example.minutewatcher.core.model

/**
 * Global application constants.
 * Contains constants for widgets, flows, SIM, notifications,
 * data, semantics, traffic, calls, and intent extras.
 *
 * All constants are documented with their strategy and reliability criteria.
 */
object Constants {
    /**
     * Имя SharedPreferences для миграции в DataStore.
     * Используется в SharedPreferencesMigration при инициализации DataStore.
     */
    const val PREFS_NAME = "app_prefs"

    /**
     * Константы уведомлений.
     *
     * CHANNEL_ID — ID канала уведомлений.
     */
    object Notifications {
        const val CHANNEL_ID = "usage_monitor_channel"
    }

    object UI {
        const val DEFAULT_BG_COLOR: Int = 0xFF000000.toInt()
        const val LUMINANCE_THRESHOLD = 0.5f
        const val SWITCH_SCALE = 0.8f
        const val HIGHLIGHT_DELAY_MS = 3000L
    }

    /**
     * Константы виджета.
     *
     * FONT_SIZE_MEDIUM — стандартный размер шрифта для виджета (sp).
     * DEBOUNCE_MS — задержка debounce для реактивного обновления виджета (мс).
     *   Предотвращает множественные обновления при быстрых изменениях данных.
     * SIM_ARRANGEMENT_HORIZONTAL — константа горизонтального расположения SIM.
     * TRANSPARENCY_SYSTEM_COLORS_THRESHOLD — порог прозрачности (0-100),
     *   выше которого используются системные цвета.
     *   Выбран как компромисс между читаемостью и эстетикой.
     * PREEMPTIVE_LAYOUT_LIMIT_SIMS — количество SIM, при котором превентивно
     *   ограничивается layout. На AOSP RemoteViews-лимит ~1 МБ.
     *   3 SIM × 6 строк × ~500 Б ≈ 9 кБ — безопасно.
     *   Упрощённый layout применяется при widgetLimitExceeded = true.
     * MIN_SIMS_FOR_SEPARATOR — минимальное количество SIM для отображения разделителя.
     * PERCENT_MAX_FLOAT — максимальное значение прозрачности (100%).
     * SEPARATOR_ALPHA — прозрачность разделителя (0.5).
     * PADDING_DEFAULT — стандартный отступ виджета (dp).
     * PADDING_SIM_ITEM — отступ SIM-карты в виджете (dp).
     * CORNER_RADIUS — радиус скругления углов виджета (dp).
     * SEPARATOR_THICKNESS — толщина разделителя (dp).
     * ALPHA_FULL — полная непрозрачность (1.0).
     * RETRY_BACKOFF_MS — начальная задержка повторной попытки обновления виджета (мс).
     * MAX_RETRY_DELAY_MS — максимальная задержка между повторными попытками (мс).
     * MAX_UPDATE_ATTEMPTS — максимальное количество попыток обновления виджета.
     */
    object Widget {
        const val FONT_SIZE_MEDIUM = 14f
        const val DEBOUNCE_MS = 300L
        const val SIM_ARRANGEMENT_HORIZONTAL = 0
        const val TRANSPARENCY_SYSTEM_COLORS_THRESHOLD = 80
        const val PREEMPTIVE_LAYOUT_LIMIT_SIMS = 3
        const val MIN_SIMS_FOR_SEPARATOR = 2
        const val PERCENT_MAX_FLOAT = 100f
        const val SEPARATOR_ALPHA = 0.5f
        const val PADDING_DEFAULT = 8
        const val PADDING_SIM_ITEM = 4
        const val CORNER_RADIUS = 16
        const val SEPARATOR_THICKNESS = 1
        const val ALPHA_FULL = 1f
        const val RETRY_BACKOFF_MS = 500L
        const val MAX_RETRY_DELAY_MS = 3000L
        const val MAX_UPDATE_ATTEMPTS = 3
        // RACE_DELAY_MS removed: synchronization is provided by synchronous semantics of StateFlow.value

        /** Максимальное количество последовательных неудач обновления виджета до блокировки. */
        const val MAX_CONSECUTIVE_UPDATE_FAILURES = 5

        const val MIN_LAYOUT_MODE = 0
        const val MAX_LAYOUT_MODE = 4
        const val MIN_SIM_ARRANGEMENT = 0
        const val MAX_SIM_ARRANGEMENT = 1
        const val MIN_TRANSPARENCY = 0
        const val MAX_TRANSPARENCY = 100
        const val MIN_FONT_SIZE = 0
        const val MAX_FONT_SIZE = 4
    }

    /**
     * Константы трафика.
     *
     * MIN_DELTA_MB — минимальное изменение трафика (МБ) для обновления.
     *   Предотвращает обновление при незначительных изменениях
     *   (например, фоновые keep-alive пакеты).
     *   Эвристика в пределах критерия надёжности: 0.01 МБ ≈ 10 КБ.
     */
    object Traffic {
        const val MIN_DELTA_MB = 0.01f
    }

    /**
     * Константы данных.
     *
     * SNAPSHOT_TIMEOUT_MS — таймаут обновления snapshot (мс).
     * STALE_SNAPSHOT_THRESHOLD_MS — порог устаревания snapshot (мс).
     *   Если snapshot старше этого порога, виджет показывает индикатор
     *   устаревших данных.
     * EMPTY_TIMESTAMP — пустая метка времени (0). Используется для
     *   обозначения отсутствия данных.
     */
    object Data {
        const val SNAPSHOT_TIMEOUT_MS = 2000L
        const val STALE_SNAPSHOT_THRESHOLD_MS = 60_000L
        const val EMPTY_TIMESTAMP = 0L
    }

    /**
     * Константы семантики.
     *
     * THROTTLE_MS — задержка throttle для семантических обновлений (мс).
     *   Предотвращает множественные обновления при быстрых изменениях данных.
     * SEARCH_INDEX_TIMEOUT_MS — таймаут индексации поиска (мс).
     *   Строгое соблюдение API: на AOSP индексация завершается за миллисекунды.
     *   Таймаут указывает на деградацию на кастомной прошивке.
     */
    object Semantic {
        const val THROTTLE_MS = 30_000L
        const val SEARCH_INDEX_TIMEOUT_MS = 10_000L
    }

    /**
     * Константы времени.
     */
    object Time {
        /** Количество миллисекунд в одной секунде. */
        const val MILLIS_IN_SECOND = 1000L

        /** Количество секунд в одной минуте. */
        const val SECONDS_IN_MINUTE = 60

        /** Таймаут обновления виджета (мс). */
        const val WIDGET_UPDATE_TIMEOUT_MS = 10_000L
    }

    /**
     * Константы потоков.
     *
     * WHILE_SUBSCRIBED_STOP_TIMEOUT_MS — таймаут остановки WhileSubscribed (мс).
     *   Достаточно для кратковременных поворотов экрана без перезапуска Flow.
     * DEBOUNCE_MS — общий debounce для UI-потоков (мс).
     * SHORT_TIMEOUT_MS — таймаут для "быстрых" операций (мс).
     * LONG_TIMEOUT_MS — таймаут для "тяжёлых" операций (мс).
     * CALL_SESSION_READ_TIMEOUT_MS — таймаут чтения сессии звонка из DataStore.
     *   2000 мс выбран как компромисс: достаточно для чтения 4 ключей
     *   (callStartTime, bootTime, uptime, callIdentity) из DataStore
     *   на AOSP, но не настолько велик, чтобы блокировать обработчик
     *   звонков на длительное время. На AOSP чтение DataStore занимает
     *   < 100 мс; таймаут срабатывает только при деградации хранилища.
     * RETRY_DELAY_MS — задержка перед повторной попыткой при сетевых/дисковых сбоях (мс).
     * SEED_GENERATION_TIMEOUT_MS — таймаут первичной генерации seed identity (мс).
     * SEED_TOTAL_TIMEOUT_EXTRA_MS — дополнительное время ожидания для завершения
     *   всех фоновых задач при генерации seed.
     */
    object Flow {
        const val WHILE_SUBSCRIBED_STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_READ_TIMEOUT_MS = 5_000L
        const val DEBOUNCE_MS = 100L
        const val SHORT_TIMEOUT_MS = 5_000L
        const val LONG_TIMEOUT_MS = 10_000L
        const val INITIAL_SETUP_RETRY_TIMEOUT_MS = 15_000L
        const val CALL_SESSION_READ_TIMEOUT_MS = 2000L
        const val SEED_GENERATION_TIMEOUT_MS = 10_000L
        const val RETRY_DELAY_MS = 1_000L

        /**
         * Total timeout for a single traffic update cycle.
         * Includes data fetching, calculations, and persistence.
         * Adjusted to accommodate sequential DataStore operations.
         */
        const val TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS = 25_000L
    }

    /**
     * Константы звонков.
     *
     * MAX_CALL_DURATION_MS — максимальная длительность звонка (мс).
     *   Если звонок длится дольше, он считается "зависшим" и сессия
     *   очищается. Это предотвращает бесконечное накопление минут.
     *   Эвристика в пределах критерия надёжности: 12 часов — разумный
     *   максимум для одного звонка.
     */
    object Calls {
        const val MAX_CALL_DURATION_MS =
            12 * Time.SECONDS_IN_MINUTE.toLong() * Time.SECONDS_IN_MINUTE.toLong() * Time.MILLIS_IN_SECOND

        /**
         * Threshold for showing inaccurate attribution banner.
         * Why 3: three consecutive unassigned events indicate a systemic issue
         * rather than a transient race condition.
         */
        const val INACCURATE_ATTRIBUTION_BANNER_THRESHOLD = 3
    }

    /**
     * Константы intent extras.
     *
     * SIM_SLOT — ключ для передачи индекса слота SIM в intent.
     */
    object IntentExtras {
        const val SIM_SLOT = "sim_slot"
    }

    /**
     * Константы SIM.
     *
     * MAX_SUPPORTED_SIM_SLOTS — максимальное количество поддерживаемых SIM-слотов.
     * SUPPORTED_SLOT_INDICES — диапазон поддерживаемых индексов слотов.
     * INVALID_SLOT_IDENTITY_PREFIX — префикс невалидной identity слота.
     *   Используется когда SIM не может быть идентифицирована.
     */
    object SimConstants {
        const val MAX_SUPPORTED_SIM_SLOTS = 10
        val SUPPORTED_SLOT_INDICES = 0 until MAX_SUPPORTED_SIM_SLOTS
        const val INVALID_SLOT_IDENTITY_PREFIX = "invalid_slot_"

        /**
         * Проверяет, является ли identity невалидной (сгенерированной для пустого слота).
         */
        fun isInvalid(identity: String): Boolean = identity.startsWith(INVALID_SLOT_IDENTITY_PREFIX)
    }

    /**
     * Количество байт в мегабайте (1024 × 1024 = 1048576).
     */
    const val BYTES_PER_MB = 1024L * 1024L

    /**
     * Количество байт в гигабайте (1024 × 1024 × 1024 = 1073741824).
     */
    const val BYTES_PER_GB = 1024L * 1024L * 1024L

    /**
     * Количество мегабайт в гигабайте (1024.0).
     * Float для использования в делении.
     */
    const val MB_PER_GB = 1024.0f

    /**
     * Порог низкого остатка (20%).
     * При остатке ниже этого порога показывается уведомление.
     */
    const val LOW_REMAINING_THRESHOLD_PERCENT = 0.20f

    /**
     * Порог критического остатка (10%).
     * При остатке ниже этого порога показывается критическое уведомление.
     */
    const val CRITICAL_REMAINING_THRESHOLD_PERCENT = 0.10f

    /** Порог критического остатка в процентах (целое число). */
    const val CRITICAL_THRESHOLD_PERCENT_INT = 10

    /** Порог низкого остатка в процентах (целое число). */
    const val LOW_THRESHOLD_PERCENT_INT = 20

    /**
     * Максимальный лимит минут (44640 = 31 день × 24 часа × 60 минут).
     * Выбран как разумный максимум для месячного лимита минут.
     */
    const val MAX_MINUTES_LIMIT = 44640

    /**
     * Максимальный лимит трафика в ГБ (10240 = 10 ТБ).
     * Выбран как разумный максимум для месячного лимита трафика.
     * При значениях выше этого Float теряет точность до 1 МБ.
     */
    const val MAX_TRAFFIC_LIMIT_GB = 10240f
}
