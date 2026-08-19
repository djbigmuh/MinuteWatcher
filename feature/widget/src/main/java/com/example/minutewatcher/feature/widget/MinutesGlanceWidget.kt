/**
 * Файл содержит UI-компоненты виджета.
 *
 * **Обоснование TooManyFunctions:** разделение на подфункции
 * (SimItemIcon, SimItemText, SimItemSpacer, SimSeparator и т.д.)
 * необходимо для читаемости и переиспользования в SimLayoutRenderV2.
 * Дальнейшее разделение на файлы снизит когезию UI-компонентов виджета.
 *
 * **Строгое соблюдение API:** все компоненты используют Glance API
 * в соответствии с документацией. На AOSP Glance обеспечивает
 * стабильный рендер; отклонения возможны только на кастомных прошивках.
 */
@file:Suppress("TooManyFunctions")

package com.example.minutewatcher.feature.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.minutewatcher.core.android.ui.SimLayoutRenderV2
import com.example.minutewatcher.core.android.util.WidgetColorResolver
import com.example.minutewatcher.core.android.util.WidgetStringsFactory
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import androidx.glance.color.ColorProvider as GlanceColorProvider
import androidx.glance.unit.ColorProvider as IColorProvider

/**
 * Основной класс виджета MinuteWatcher.
 * Рендерит состояние SIM-карт на домашнем экране.
 *
 * Строгое соблюдение API: все компоненты используют Glance API
 * в соответствии с документацией. На AOSP Glance обеспечивает
 * стабильный рендер; отклонения возможны только на кастомных прошивках.
 *
 * Обоснование TooManyFunctions: разделение на подфункции
 * (SimItemIcon, SimItemText, SimItemSpacer, SimSeparator и т. д.)
 * необходимо для читаемости и переиспользования в SimLayoutRenderV2.
 * Дальнейшее разделение на файлы снизит когезию UI-компонентов виджета.
 */
class MinutesGlanceWidget : GlanceAppWidget() {
    /**
     * Hilt EntryPoint для виджета.
     * Позволяет получать зависимости в виджете через EntryPointAccessors.
     *
     * @return UsageStateRepository, WidgetSettingsLoader, NavigationProvider, TimeProvider.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun usageStateRepository(): UsageStateRepository

        fun widgetSettingsLoader(): WidgetSettingsLoader

        fun navigationProvider(): NavigationProvider

        fun timeProvider(): TimeProvider
    }

    /**
     * Рендерит виджет.
     * Вызывается системой при обновлении виджета.
     * При любом исключении показывает FallbackContent.
     *
     * Строгое соблюдение API: на AOSP provideGlance вызывается только
     * через updateAll(). Окно гонки не реализуется на AOSP.
     *
     * Отклонение кастомной прошивки: если кастомная прошивка вызывает
     * provideGlance асинхронно относительно updateAll() — это проблема
     * кастомной прошивки. Защита: provideGlance обёрнут в try-catch
     * с fallback-рендером.
     *
     * @param context Контекст.
     * @param id Идентификатор виджета.
     */
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        try {
            val uiState = getUiState(context)
            val navigationProvider =
                try {
                    getNavigationProvider(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Widget: failed to get NavigationProvider", e)
                    null
                }

            provideContent {
                GlanceTheme {
                    WidgetContent(uiState, navigationProvider)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Widget provideGlance failed, showing fallback", e)
            provideContent {
                GlanceTheme {
                    FallbackContent(context)
                }
            }
        }
    }

    /**
     * Рендерит fallback-содержимое виджета.
     * Показывается при любом исключении в provideGlance.
     * Содержит сообщение "Ошибка обновления".
     *
     * Строгое соблюдение API: на AOSP provideGlance не бросает исключений
     * при корректном lifecycle. Fallback возможен только при деградации
     * на кастомной прошивке. Это проблема кастомной прошивки.
     *
     * @param context Контекст.
     */
    @Composable
    private fun FallbackContent(context: Context) {
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = context.getString(R.string.refresh_error),
                style =
                    TextStyle(
                        fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                    ),
            )
        }
    }

    private fun getNavigationProvider(context: Context): NavigationProvider {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        return entryPoint.navigationProvider()
    }

    /**
     * Получает состояние виджета.
     * Загружает snapshot и настройки виджета.
     * При ошибке загрузки настроек использует значения по умолчанию.
     *
     * Строгое соблюдение API: на AOSP загрузка snapshot и настроек
     * завершается за миллисекунды. Ошибка указывает на деградацию
     * DataStore на кастомной прошивке. Это проблема кастомной прошивки.
     *
     * @param context Контекст.
     * @return Состояние виджета.
     */
    private suspend fun getUiState(context: Context): WidgetUiState {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        val usageRepository = entryPoint.usageStateRepository()
        val widgetSettingsLoader = entryPoint.widgetSettingsLoader()
        val timeProvider = entryPoint.timeProvider()

        val refreshResult = getSnapshot(usageRepository, timeProvider)
        val snapshot = refreshResult.snapshot
        val refreshFailed =
            when (refreshResult) {
                is com.example.minutewatcher.core.domain.RefreshResult.Success -> false
                is com.example.minutewatcher.core.domain.RefreshResult.Timeout -> {
                    AppLogger.w("Widget: using stale snapshot, timestamp=${refreshResult.snapshot.timestamp}")
                    true
                }
            }

        val settingsState =
            try {
                widgetSettingsLoader.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to load widget settings, using defaults", e)
                WidgetSettingsState(settingsLoadFailed = true)
            }

        if (refreshFailed && settingsState.settingsLoadFailed) {
            AppLogger.w("Widget: both snapshot refresh and settings load failed")
        }

        return WidgetUiMapper.mapFromSnapshot(
            strings = WidgetStringsFactory.create(context),
            snapshot = snapshot,
            state = settingsState,
            operatorNames = WidgetStringsFactory.createOperatorNames(context, snapshot.simSnapshots),
            refreshFailed = refreshFailed,
        )
    }

    // Централизованное создание WidgetStrings и имён операторов перенесено в WidgetStringsFactory
    // для исключения дублирования с экраном настроек и гарантии консистентности.

    /**
     * Получает snapshot для виджета.
     * Если snapshot не пустой и не устаревший, возвращает его.
     * Иначе пытается обновить через usageRepository.refresh().
     * При ошибке обновления возвращает Timeout с текущим snapshot.
     *
     * Строгое соблюдение API: на AOSP UsageStateRepository.refresh()
     * возвращает Success. Таймаут указывает на деградацию DataStore
     * на кастомной прошивке. Это проблема кастомной прошивки.
     *
     * @param usageRepository Репозиторий состояния использования.
     * @param timeProvider Провайдер времени.
     * @return Результат обновления (Success или Timeout).
     */
    private suspend fun getSnapshot(
        usageRepository: UsageStateRepository,
        timeProvider: TimeProvider,
    ): com.example.minutewatcher.core.domain.RefreshResult {
        val initialSnapshot = usageRepository.observeSnapshot().value
        val now = timeProvider.now()
        val isStale = (now - initialSnapshot.timestamp) > Constants.Data.STALE_SNAPSHOT_THRESHOLD_MS

        if (initialSnapshot.timestamp != 0L && !isStale) {
            return com.example.minutewatcher.core.domain.RefreshResult
                .Success(initialSnapshot)
        }

        // Attempt to force a refresh if the snapshot is empty or stale
        return try {
            usageRepository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Widget refresh failed", e)
            com.example.minutewatcher.core.domain.RefreshResult
                .Timeout(initialSnapshot)
        }
    }
}

/**
 * Рендерит содержимое виджета.
 * Содержит фон, список SIM-карт и индикатор устаревших данных.
 *
 * @param uiState Состояние виджета.
 * @param navigationProvider Провайдер навигации для клика по виджету.
 */
@Composable
private fun WidgetContent(
    uiState: WidgetUiState,
    navigationProvider: NavigationProvider?,
) {
    val context = LocalContext.current
    val alpha = Constants.Widget.ALPHA_FULL - uiState.transparency / Constants.Widget.PERCENT_MAX_FLOAT
    val bgColor =
        androidx.compose.ui.graphics
            .Color(uiState.backgroundColor)
            .copy(alpha = alpha)

    val noDataColor = getNoDataColor(uiState)

    Box(
        modifier = getWidgetModifier(context, navigationProvider, bgColor),
        contentAlignment = Alignment.Center,
    ) {
        WidgetMainContent(uiState, noDataColor)
    }
}

/**
 * Возвращает цвет для сообщения "Нет данных".
 * При useSystemColors возвращает GlanceTheme.colors.onSurface.
 * Иначе возвращает цвет, вычисленный через WidgetColorResolver.
 *
 * @param uiState Состояние виджета.
 * @return Цвет для сообщения "Нет данных".
 */
@Composable
private fun getNoDataColor(uiState: WidgetUiState): IColorProvider {
    if (uiState.useSystemColors) {
        return GlanceTheme.colors.onSurface
    }

    val colorInt = WidgetColorResolver.resolveNoDataColor(uiState.backgroundColor)
    val composeColor =
        androidx.compose.ui.graphics
            .Color(colorInt)
    return GlanceColorProvider(day = composeColor, night = composeColor)
}

/**
 * Создаёт модификатор для виджета.
 * Содержит фон, appWidgetBackground и clickable для клика по виджету.
 *
 * @param context Контекст.
 * @param navigationProvider Провайдер навигации для клика по виджету.
 * @param bgColor Цвет фона.
 * @return Модификатор для виджета.
 */
private fun getWidgetModifier(
    context: Context,
    navigationProvider: NavigationProvider?,
    bgColor: androidx.compose.ui.graphics.Color,
): GlanceModifier {
    var modifier =
        GlanceModifier
            .fillMaxSize()
            .background(GlanceColorProvider(day = bgColor, night = bgColor))
            .appWidgetBackground()

    val className = navigationProvider?.mainActivityClassName ?: ""
    if (className.isNotEmpty()) {
        try {
            /**
             * Запуск из виджета должен имитировать запуск лаунчера
             * (ACTION_MAIN + CATEGORY_LAUNCHER + NEW_TASK|CLEAR_TOP),
             * чтобы не создавать дублирующих задач и сохранить корректное
             * поведение кнопки «Домой»/Recents.
             */
            val launchIntent =
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(context, className)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            modifier = modifier.clickable(actionStartActivity(launchIntent))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Failed to attach click action to widget", e)
        }
    }
    return modifier
}

/**
 * Рендерит основное содержимое виджета.
 * Показывает сообщение об ошибке, сообщение об отсутствии SIM
 * или список SIM-карт в зависимости от состояния.
 *
 * @param uiState Состояние виджета.
 * @param noDataColor Цвет для сообщения "Нет данных".
 */
@Composable
private fun WidgetMainContent(
    uiState: WidgetUiState,
    noDataColor: IColorProvider,
) {
    val context = LocalContext.current
    val simUiStates = uiState.simUiStates.filter { it.showSim }

    when {
        uiState.refreshFailed && uiState.isSnapshotEmpty -> {
            WidgetMessage(context.getString(R.string.refresh_error), noDataColor)
        }

        uiState.settingsLoadFailed -> {
            WidgetMessage(context.getString(R.string.settings_load_error), noDataColor)
        }

        simUiStates.isNotEmpty() -> {
            SimCardsLayout(simUiStates, uiState, noDataColor)
        }

        else -> {
            WidgetMessage(context.getString(R.string.no_sim_found), noDataColor)
        }
    }
}

/**
 * Рендерит список SIM-карт в виджете.
 * Показывает индикатор устаревших данных при refreshFailed.
 * Содержит список SIM-карт через MultiSimLayout.
 *
 * @param simUiStates Список состояний SIM-карт.
 * @param uiState Состояние виджета.
 * @param noDataColor Цвет для сообщения "Нет данных".
 */
@Composable
private fun SimCardsLayout(
    simUiStates: List<SimUiState>,
    uiState: WidgetUiState,
    noDataColor: IColorProvider,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (uiState.refreshFailed) {
            StaleDataIndicator(noDataColor)
        }
        Box(
            modifier = GlanceModifier.defaultWeight(),
            contentAlignment = Alignment.Center,
        ) {
            MultiSimLayout(simUiStates, uiState)
        }
    }
}

/**
 * Рендерит индикатор устаревших данных.
 * Показывается при refreshFailed = true.
 * Содержит иконку предупреждения и текст "Данные могут быть устаревшими".
 *
 * @param color Цвет индикатора.
 */
@Composable
private fun StaleDataIndicator(color: IColorProvider) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.padding(top = 4.dp),
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_warning_amber),
            contentDescription = null,
            modifier = GlanceModifier.size(12.dp),
            colorFilter = androidx.glance.ColorFilter.tint(color),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = context.getString(R.string.stale_data_warning),
            style =
                TextStyle(
                    fontSize = 10.sp,
                    color = color,
                ),
        )
    }
}

/**
 * Рендерит сообщение в виджете.
 * Используется для сообщений об ошибке, отсутствии SIM и т. д.
 *
 * @param text Текст сообщения.
 * @param color Цвет текста.
 */
@Composable
private fun WidgetMessage(
    text: String,
    color: IColorProvider,
) {
    Text(
        text = text,
        style =
            TextStyle(
                fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                color = color,
            ),
    )
}

/**
 * Рендерит несколько SIM-карт в виджете.
 * Использует HorizontalSimLayout или VerticalSimLayout
 * в зависимости от isHorizontal.
 *
 * @param sims Список состояний SIM-карт.
 * @param uiState Состояние виджета.
 */
@Composable
private fun MultiSimLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    if (uiState.isHorizontal) {
        HorizontalSimLayout(sims, uiState)
    } else {
        VerticalSimLayout(sims, uiState)
    }
}

/**
 * Рендерит SIM-карты горизонтально (в ряд).
 * Каждая SIM-карта занимает равную ширину (defaultWeight).
 * Разделитель показывается между SIM-картами при showSeparator.
 *
 * @param sims Список состояний SIM-карт.
 * @param uiState Состояние виджета.
 */
@Composable
private fun HorizontalSimLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sims.forEachIndexed { index, sim ->
            Box(
                modifier =
                    GlanceModifier
                        .fillMaxHeight()
                        .defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                SimItem(sim, uiState)
            }
            if (uiState.showSeparator && index < sims.size - 1) {
                SimSeparator(uiState, isHorizontal = true)
            }
        }
    }
}

/**
 * Рендерит SIM-карты вертикально (в столбец).
 * Каждая SIM-карта занимает равную высоту (defaultWeight).
 * Разделитель показывается между SIM-картами при showSeparator.
 *
 * @param sims Список состояний SIM-карт.
 * @param uiState Состояние виджета.
 */
@Composable
private fun VerticalSimLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sims.forEachIndexed { index, sim ->
            Box(
                modifier =
                    GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                SimItem(sim, uiState)
            }
            if (uiState.showSeparator && index < sims.size - 1) {
                SimSeparator(uiState, isHorizontal = false)
            }
        }
    }
}

/**
 * Рендерит разделитель между SIM-картами в виджете.
 * Показывается при showSeparator = true и наличии нескольких SIM.
 *
 * @param uiState Состояние виджета.
 * @param isHorizontal true если разделитель вертикальный (для горизонтального расположения).
 */
@Composable
private fun SimSeparator(
    uiState: WidgetUiState,
    isHorizontal: Boolean,
) {
    val separatorColor =
        if (uiState.useSystemColors) {
            GlanceTheme.colors.outline
        } else {
            val color =
                androidx.compose.ui.graphics
                    .Color(uiState.simUiStates.firstOrNull()?.secondaryTextColor ?: android.graphics.Color.GRAY)
                    .copy(alpha = Constants.Widget.SEPARATOR_ALPHA)
            GlanceColorProvider(day = color, night = color)
        }

    val modifier =
        if (isHorizontal) {
            GlanceModifier
                .width(Constants.Widget.SEPARATOR_THICKNESS.dp)
                .fillMaxHeight()
        } else {
            GlanceModifier
                .height(Constants.Widget.SEPARATOR_THICKNESS.dp)
                .fillMaxWidth()
        }

    Spacer(modifier = modifier.background(separatorColor))
}

/**
 * Рендерит одну SIM-карту в виджете.
 * Содержит иконку, имя оператора, состояние минут и трафика.
 * Использует SimLayoutRenderV2 для рендера.
 *
 * @param sim Состояние SIM-карты.
 * @param uiState Состояние виджета.
 */
@Composable
private fun SimItem(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val context = LocalContext.current
    val summary =
        context.getString(
            R.string.sim_status_summary,
            sim.operatorName,
            sim.minutesText,
            sim.trafficText,
        )

    Column(
        modifier =
            GlanceModifier
                .padding(Constants.Widget.PADDING_SIM_ITEM.dp)
                .semantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimItemLayout(sim, uiState)
    }
}

/**
 * Рендерит layout SIM-карты через SimLayoutRenderV2.
 * Передаёт рендереры для иконки, текста и spacer.
 *
 * @param sim Состояние SIM-карты.
 * @param uiState Состояние виджета.
 */
@Composable
private fun SimItemLayout(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    SimLayoutRenderV2(
        spec = sim.layoutSpec,
        iconRenderer = { component ->
            SimItemIcon(component)
        },
        textRenderer = { component ->
            SimItemText(component, sim, uiState)
        },
        spacerRenderer = { component ->
            SimItemSpacer(component)
        },
        rowContainer = { content ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        },
    )
}

/**
 * Рендерит иконку SIM-карт в виджете.
 * Используется в SimLayoutRenderV2.
 *
 * @param component Компонент иконки (iconRes, sizeDp).
 */
@Composable
private fun SimItemIcon(component: com.example.minutewatcher.core.model.SimLayoutComponent.Icon) {
    Image(
        provider = ImageProvider(component.iconRes),
        contentDescription = null,
        modifier = GlanceModifier.size(component.sizeDp.dp),
    )
}

/**
 * Рендерит текст SIM-карты в виджете.
 * Используется в SimLayoutRenderV2.
 * Цвет текста определяется в зависимости от useSystemColors.
 *
 * @param component Компонент текста (text, color, fontSizeSp, isBold).
 * @param sim Состояние SIM-карты.
 * @param uiState Состояние виджета.
 */
@Composable
private fun SimItemText(
    component: com.example.minutewatcher.core.model.SimLayoutComponent.Text,
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val textColor =
        if (uiState.useSystemColors && component.color == sim.textColor) {
            GlanceTheme.colors.onSurface
        } else if (uiState.useSystemColors && component.color == sim.secondaryTextColor) {
            GlanceTheme.colors.onSurfaceVariant
        } else {
            val composeColor =
                androidx.compose.ui.graphics
                    .Color(component.color)
            GlanceColorProvider(day = composeColor, night = composeColor)
        }

    Text(
        text = component.text,
        style =
            TextStyle(
                fontSize = component.fontSizeSp.sp,
                fontWeight = if (component.isBold) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
            ),
    )
}

/**
 * Рендерит spacer в виджете.
 * Используется в SimLayoutRenderV2.
 *
 * @param component Компонент spacer (widthDp, heightDp).
 */
@Composable
private fun SimItemSpacer(component: com.example.minutewatcher.core.model.SimLayoutComponent.Spacer) {
    if (component.widthDp > 0) {
        Spacer(modifier = GlanceModifier.width(component.widthDp.dp))
    }
    if (component.heightDp > 0) {
        Spacer(modifier = GlanceModifier.height(component.heightDp.dp))
    }
}
