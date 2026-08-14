package com.example.minutewatcher.feature.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
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
import com.example.minutewatcher.core.android.ui.SimLayoutRender
import com.example.minutewatcher.core.android.util.WidgetColorResolver
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

class MinutesGlanceWidget : GlanceAppWidget() {
    companion object {
        private const val PREFS_NAME = "widget_render_flags"
        private const val KEY_FORCE_SIMPLIFIED = "force_simplified"
        private const val KEY_FORCE_FULL = "force_full"

        /**
         * Устанавливает флаг принудительного упрощённого рендера.
         * Флаг сохраняется в SharedPreferences, чтобы быть доступным
         * при асинхронном вызове provideGlance системой.
         *
         * Хрупкая эвристика: GlanceAppWidget.updateAll() является suspend-функцией,
         * однако момент вызова provideGlance определяется системой. Строгое соблюдение
         * API не гарантирует синхронность. Передача через SharedPreferences устраняет
         * зависимость от тайминга.
         */
        fun setForceSimplified(
            context: Context,
            value: Boolean,
        ) {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit {
                    putBoolean(KEY_FORCE_SIMPLIFIED, value)
                }
        }

        private fun getForceSimplified(context: Context): Boolean =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FORCE_SIMPLIFIED, false)

        /**
         * Устанавливает флаг принудительного ПОЛНОГО рендера (игнорируя лимит).
         */
        fun setForceFull(
            context: Context,
            value: Boolean,
        ) {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit {
                    putBoolean(KEY_FORCE_FULL, value)
                }
        }

        private fun getForceFull(context: Context): Boolean =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FORCE_FULL, false)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun usageStateRepository(): UsageStateRepository

        fun widgetSettingsLoader(): WidgetSettingsLoader

        fun navigationProvider(): NavigationProvider

        fun timeProvider(): TimeProvider
    }

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
            refreshResult is com.example.minutewatcher.core.domain.RefreshResult.Timeout

        val forceSimplified = getForceSimplified(context.applicationContext)
        val forceFull = getForceFull(context.applicationContext)
        val settingsState =
            try {
                val loaded = widgetSettingsLoader.load()
                when {
                    forceSimplified -> loaded.copy(widgetLimitExceeded = true)
                    forceFull -> loaded.copy(widgetLimitExceeded = false)
                    else -> loaded
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to load widget settings, using defaults", e)
                val fallback = WidgetSettingsState(settingsLoadFailed = true)
                if (forceSimplified) fallback.copy(widgetLimitExceeded = true) else fallback
            }

        if (refreshFailed && settingsState.settingsLoadFailed) {
            AppLogger.w("Widget: both snapshot refresh and settings load failed")
        }

        return WidgetUiMapper.mapFromSnapshot(
            strings = getWidgetStrings(context),
            snapshot = snapshot,
            state = settingsState,
            operatorNames = getOperatorNames(context, snapshot.simSnapshots),
            refreshFailed = refreshFailed,
        )
    }

    private fun getWidgetStrings(context: Context): com.example.minutewatcher.core.model.WidgetStrings =
        com.example.minutewatcher.core.model.WidgetStrings(
            usedText = context.getString(R.string.used_text),
            minutesAbbr = context.getString(R.string.minutes_abbr),
            limitText = context.getString(R.string.limit_text),
            gbAbbr = context.getString(R.string.gb_abbr),
            fractionFormat = context.getString(R.string.fraction_format),
        )

    private fun getOperatorNames(
        context: Context,
        simSnapshots: List<com.example.minutewatcher.core.model.SimSnapshot>,
    ): Map<Int, String> =
        simSnapshots.associate { sim ->
            sim.slot to
                com.example.minutewatcher.core.android.util.SimUtils
                    .getShortOperatorName(
                        context,
                        sim.operator,
                    )
        }

    /**
     * Получает актуальный snapshot для рендера виджета.
     *
     * **Strict Compliance:**
     * 1. Проверка staleness использует wall clock (TimeProvider.now()), так как
     *    `snapshot.timestamp` сохраняется в репозитории именно как wall clock.
     * 2. Поведение при переводе часов:
     *    - Вперёд: Snapshot может быть помечен как stale раньше времени, что приведет
     *      к безопасному внеплановому обновлению.
     *    - Назад: Разница `now - timestamp` становится отрицательной, что всегда меньше
     *      порога STALE_SNAPSHOT_THRESHOLD_MS. Snapshot не будет помечен как stale,
     *      лишний вызов refresh() не выполнится. Это безопасно и не ведет к потере данных.
     *
     * Строгое соблюдение API: на AOSP UsageStateRepository.refresh()
     * не возвращает null при корректном состоянии DataStore.
     * Если кастомная прошивка не соответствует документации Google —
     * это проблема кастомной прошивки.
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

@Composable
private fun SimItemLayout(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    SimLayoutRender(
        spec = sim.layoutSpec,
        rowContainer = { content ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        },
        iconRenderer = { component ->
            SimItemIcon(component)
        },
        textRenderer = { component ->
            SimItemText(component, sim, uiState)
        },
        spacerRenderer = { component ->
            SimItemSpacer(component)
        },
    )
}

@Composable
private fun SimItemIcon(component: com.example.minutewatcher.core.model.SimLayoutComponent.Icon) {
    Image(
        provider = ImageProvider(component.iconRes),
        contentDescription = null,
        modifier = GlanceModifier.size(component.sizeDp.dp),
    )
}

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

@Composable
private fun SimItemSpacer(component: com.example.minutewatcher.core.model.SimLayoutComponent.Spacer) {
    if (component.widthDp > 0) {
        Spacer(modifier = GlanceModifier.width(component.widthDp.dp))
    }
    if (component.heightDp > 0) {
        Spacer(modifier = GlanceModifier.height(component.heightDp.dp))
    }
}
