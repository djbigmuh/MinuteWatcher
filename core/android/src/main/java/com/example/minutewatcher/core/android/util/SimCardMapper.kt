package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel
import com.example.minutewatcher.core.model.util.ClampingUtils
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Mappers for converting domain models (SimUsage) to UI models (SimCardUiModel).
 *
 * This object maintains a small internal cache [negativeLogged] to prevent log spamming
 * when negative usage values are detected.
 */
object SimCardMapper {
    /**
     * Кэш identity SIM-карт, для которых уже было залогировано предупреждение
     * об отрицательных значениях. Предотвращает повторное логирование
     * одного и того же identity в одном слоте.
     *
     * **Потокобезопасность:** обёрнут в [Collections.synchronizedMap].
     * Все составные операции (проверка размера + удаление) выполняются
     * внутри явного `synchronized(negativeLogged)` блока.
     *
     * **Эвикция:** при превышении [MAX_LOGGED_SIZE] удаляется самый старый
     * ключ (FIFO). [LinkedHashMap] сохраняет порядок вставки, поэтому
     * `keys.firstOrNull()` возвращает первый вставленный ключ.
     * Это допустимая эвристика в пределах критерия надёжности:
     * кэш ограничен 20 записями, что покрывает все реальные слоты
     * (максимум [Constants.SimConstants.MAX_SUPPORTED_SIM_SLOTS] = 10).
     */
    private val negativeLogged = Collections.synchronizedMap(LinkedHashMap<Int, String>())
    private const val MAX_LOGGED_SIZE = 20

    fun map(
        context: Context,
        slot: Int,
        usage: SimUsage,
    ): SimCardUiModel {
        // Мапа negativeLogged подавляет повторное логирование отрицательных
        // значений для одного и того же identity в слоте. При смене SIM
        // в слоте (identity изменился) старая запись удаляется, чтобы
        // логирование для нового identity не было подавлено.
        synchronized(negativeLogged) {
            if ((negativeLogged[slot] != null) && (negativeLogged[slot] != usage.identity)) {
                negativeLogged.remove(slot)
            }
        }

        logNegativeValuesIfNeeded(slot, usage)

        val safeMinutesUsed = ClampingUtils.clamp(usage.minutesUsed, 0, Constants.MAX_MINUTES_LIMIT)
        val safeMinutesLimit = ClampingUtils.clamp(usage.minutesLimit, 0, Constants.MAX_MINUTES_LIMIT)

        val maxTrafficMb = (Constants.MAX_TRAFFIC_LIMIT_GB * Constants.MB_PER_GB).toInt()
        val safeTrafficUsedMb = ClampingUtils.clamp(usage.trafficUsedMb, 0f, maxTrafficMb.toFloat())
        val safeTrafficLimitMb = ClampingUtils.clamp(usage.trafficLimitMb, 0, maxTrafficMb)

        // manualAdjustment и trafficAdjustmentGb передаются в calculate()
        // без предварительного clamp. Отрицательные значения допустимы
        // (корректировка в меньшую сторону). MinutesModel.calculate и
        // TrafficModel.calculate гарантируют effectiveLimit >= 0 через ClampingUtils.clamp.
        val minutesModel =
            MinutesModel.calculate(
                used = safeMinutesUsed,
                limit = safeMinutesLimit,
                manualAdjustment = usage.manualAdjustment,
            )

        val trafficModel =
            TrafficModel.calculate(
                usedMb = safeTrafficUsedMb,
                limitMb = safeTrafficLimitMb,
                manualAdjustmentGb = usage.trafficAdjustmentGb,
            )

        return SimCardUiModel(
            slot = slot,
            identity = usage.identity,
            operatorName = SimUtils.getShortOperatorName(context, usage.operator),
            iconRes = SimUtils.getOperatorIconResource(usage.operator),
            minutesUsed = safeMinutesUsed,
            minutesLimit = safeMinutesLimit,
            manualAdjustment = usage.manualAdjustment,
            minutesRemaining = minutesModel.remaining,
            effectiveMinutesLimit = minutesModel.effectiveLimit,
            isMinutesLimitSet = minutesModel.isLimitSet,
            trafficUsedMb = safeTrafficUsedMb,
            trafficLimitMb = safeTrafficLimitMb,
            trafficRemainingGb = trafficModel.remainingGb,
            effectiveTrafficLimitGb = trafficModel.effectiveLimitGb,
            trafficAdjustmentGb = usage.trafficAdjustmentGb,
            isTrafficLimitSet = trafficModel.isLimitSet,
            autoResetEnabled = usage.autoReset,
            autoResetDay = usage.autoResetDay,
            rolloverEnabled = usage.rolloverEnabled,
        )
    }

    private fun logNegativeValuesIfNeeded(
        slot: Int,
        usage: SimUsage,
    ) {
        val hasNegativeValues =
            usage.minutesUsed < 0 ||
                usage.minutesLimit < 0 ||
                usage.trafficUsedMb < 0f ||
                usage.trafficLimitMb < 0

        if (!hasNegativeValues) return

        synchronized(negativeLogged) {
            val lastLoggedIdentity = negativeLogged[slot]
            if (lastLoggedIdentity == usage.identity) return

            AppLogger.e(
                "SimCardMapper: Detected negative usage/limit values for slot $slot (id=${usage.identity}): " +
                    "minUsed=${usage.minutesUsed}, minLimit=${usage.minutesLimit}, " +
                    "trafUsed=${usage.trafficUsedMb}, trafLimit=${usage.trafficLimitMb}",
            )
            negativeLogged[slot] = usage.identity
            // Clean up when overflowing to maintain cache size limit
            if (negativeLogged.size > MAX_LOGGED_SIZE) {
                // LinkedHashMap сохраняет порядок вставки. firstOrNull() возвращает
                // самый старый ключ (FIFO). Удаляем его для ограничения размера кэша.
                val eldestKey = negativeLogged.keys.firstOrNull()
                if (eldestKey != null) {
                    negativeLogged.remove(eldestKey)
                }
            }
        }
    }
}
