package com.example.minutewatcher.feature.main

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.RefreshState
import com.example.minutewatcher.feature.main.ui.components.AdjustmentComposeDialog
import com.example.minutewatcher.feature.main.ui.components.DayPickerComposeDialog
import com.example.minutewatcher.feature.main.ui.components.SimCardItem
import com.example.minutewatcher.feature.main.ui.components.SimLimitComposeDialog
import com.example.minutewatcher.feature.main.ui.components.TrackingBanners
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimCardScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onAddWidget: () -> Unit,
    onToggleTheme: () -> Unit,
    onLanguageChange: (String?) -> Unit,
    onGrantPhonePermission: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val highlightedSlot by viewModel.highlightedSlot.collectAsStateWithLifecycle()
    val trackingBannerState by viewModel.trackingBannerState.collectAsStateWithLifecycle()
    val showUsageAccessDialog by viewModel.showUsageAccessDialog.collectAsStateWithLifecycle()
    val showOnboardingDialog by viewModel.showOnboardingDialog.collectAsStateWithLifecycle()
    val refreshStatus by viewModel.refreshStatus.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isRefreshing by remember { mutableStateOf(value = false) }
    var showLanguageMenu by remember { mutableStateOf(value = false) }
    var showResetConfirmSlot by remember { mutableStateOf<Int?>(null) }

    val genericErrorMessage = stringResource(id = R.string.error)

    LaunchedEffect(refreshStatus) {
        when (val status = refreshStatus) {
            is RefreshState.Error -> {
                snackbarHostState.showSnackbar(status.message ?: genericErrorMessage)
                viewModel.consumeRefreshStatus()
            }
            is RefreshState.Success -> {
                // Success is already indicated by data update and pull-to-refresh spinner hiding
                viewModel.consumeRefreshStatus()
            }
            else -> {}
        }
    }

    // Dialog states from ViewModel
    val showLimitDialog by viewModel.showLimitDialog.collectAsStateWithLifecycle()
    val showDayDialog by viewModel.showDayDialog.collectAsStateWithLifecycle()
    val showAdjustDialog by viewModel.showAdjustDialog.collectAsStateWithLifecycle()
    val showTrafficLimitationDialog by viewModel.showTrafficLimitationDialog.collectAsStateWithLifecycle()

    val languagesNames = stringArrayResource(id = R.array.languages)
    val languageCodes = stringArrayResource(id = R.array.language_codes)
    val languagesFlags =
        listOf(
            stringResource(id = R.string.language_system_icon),
            "EN",
            "RU",
            "DE",
            "FR",
            "AR",
            "ES",
            "JA",
            "ZH",
            "HI",
        )

    val languages =
        remember(languagesNames, languageCodes) {
            require(languagesNames.size == languageCodes.size) {
                "languages and language_codes arrays must have the same size"
            }
            require(languagesFlags.size == languagesNames.size) {
                "languagesFlags must have the same size as languages"
            }

            languagesNames.mapIndexed { index, name ->
                LanguageOption(
                    code = languageCodes[index].ifEmpty { null },
                    name = name,
                    flag = languagesFlags[index],
                )
            }
        }

    LaunchedEffect(highlightedSlot, uiState) {
        val slot = highlightedSlot
        val state = uiState
        if (slot != null && state is SimCardListState.Success) {
            val index = state.simCards.indexOfFirst { it.slot == slot }
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                actions = {
                    Box {
                        IconButton(onClick = { showLanguageMenu = true }) {
                            Text(stringResource(id = R.string.language_system_icon))
                        }
                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false },
                        ) {
                            languages.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(lang.flag)
                                            Spacer(Modifier.width(8.dp))
                                            Text(lang.name)
                                        }
                                    },
                                    onClick = {
                                        showLanguageMenu = false
                                        onLanguageChange(lang.code)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onToggleTheme) {
                        val isNight =
                            when (appTheme) {
                                AppTheme.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                                AppTheme.DARK -> true
                                AppTheme.LIGHT -> false
                            }
                        Text(
                            text =
                                if (isNight) {
                                    stringResource(
                                        id = R.string.theme_light_icon,
                                    )
                                } else {
                                    stringResource(id = R.string.theme_dark_icon)
                                },
                            fontSize = 20.sp,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(id = R.string.widget_settings_content_desc))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddWidget,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(id = R.string.add_widget_button)) },
            )
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    viewModel.refreshSimCardsNow()
                    isRefreshing = false
                }
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val state = uiState) {
                is SimCardListState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is SimCardListState.Empty -> {
                    EmptyState()
                }
                is SimCardListState.Error -> {
                    ErrorState(state.message)
                }
                is SimCardListState.Success -> {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = 80.dp),
                    ) {
                        item {
                            TrackingBanners(
                                state = trackingBannerState,
                                onGrantPhonePermission = onGrantPhonePermission,
                                onOpenUsageSettings = onOpenUsageSettings,
                                onOpenAppSettings = onOpenAppSettings,
                                onRetryAppSearch = { viewModel.retryAppSearch() },
                                onRetryTraffic = { viewModel.retryTraffic() },
                            )
                        }

                        items(state.simCards) { model ->
                            SimCardItem(
                                model = model,
                                onSetLimits = {
                                    viewModel.openLimitDialog(
                                        LimitDialogData(model.slot, model.operatorName, model.minutesLimit, model.trafficLimitMb),
                                    )
                                },
                                onResetCounters = { showResetConfirmSlot = model.slot },
                                onToggleAutoReset = { enabled -> viewModel.toggleAutoReset(model.slot, enabled) },
                                onEditAutoResetDay = {
                                    viewModel.openDayDialog(DayDialogData(model.slot, model.autoResetDay))
                                },
                                onAdjustMinutes = {
                                    viewModel.openAdjustDialog(AdjustDialogData(model.slot, model.minutesRemaining.toFloat(), true))
                                },
                                onAdjustTraffic = {
                                    viewModel.openAdjustDialog(AdjustDialogData(model.slot, model.trafficRemainingGb, false))
                                },
                                onShowTrafficInfo = {
                                    viewModel.openTrafficLimitationDialog()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Render Dialogs
    showLimitDialog?.let { data ->
        SimLimitComposeDialog(
            operatorName = data.name,
            initialMinutes = data.minutes,
            initialTrafficMb = data.traffic,
            onDismiss = { viewModel.closeLimitDialog() },
            onConfirm = { min, traf ->
                viewModel.updateLimits(data.slot, min, traf)
                viewModel.closeLimitDialog()
            },
            onReset = {
                viewModel.updateLimits(data.slot, 0, 0)
                viewModel.closeLimitDialog()
            },
        )
    }

    showDayDialog?.let { data ->
        DayPickerComposeDialog(
            initialDay = data.day,
            onDismiss = { viewModel.closeDayDialog() },
            onDaySelected = { day ->
                viewModel.updateAutoResetDay(data.slot, day)
                viewModel.closeDayDialog()
            },
        )
    }

    showAdjustDialog?.let { data ->
        AdjustmentComposeDialog(
            currentValue = data.currentValue,
            isMinutes = data.isMinutes,
            onDismiss = { viewModel.closeAdjustDialog() },
            onConfirm = { value ->
                if (data.isMinutes) {
                    viewModel.adjustRemainingMinutes(data.slot, value.toInt())
                } else {
                    viewModel.adjustRemainingTraffic(data.slot, value)
                }
                viewModel.closeAdjustDialog()
            },
        )
    }

    if (showTrafficLimitationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.closeTrafficLimitationDialog() },
            title = { Text(stringResource(id = R.string.traffic_tracking_limitation_title)) },
            text = { Text(stringResource(id = R.string.traffic_tracking_limitation_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.closeTrafficLimitationDialog() }) {
                    Text(stringResource(id = R.string.action_ok))
                }
            },
        )
    }

    if (showUsageAccessDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.closeUsageAccessDialog() },
            title = { Text(stringResource(id = R.string.usage_access_dialog_title)) },
            text = { Text(stringResource(id = R.string.usage_access_dialog_description)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.closeUsageAccessDialog()
                    onOpenUsageSettings()
                }) {
                    Text(stringResource(id = R.string.action_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeUsageAccessDialog() }) {
                    Text(stringResource(id = R.string.cancel_button))
                }
            },
        )
    }

    if (showOnboardingDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.completeOnboarding() },
            title = { Text(stringResource(id = R.string.onboarding_title)) },
            text = { Text(stringResource(id = R.string.onboarding_message)) },
            confirmButton = {
                Button(onClick = { viewModel.completeOnboarding() }) {
                    Text(stringResource(id = R.string.onboarding_button))
                }
            },
        )
    }

    showResetConfirmSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { showResetConfirmSlot = null },
            title = { Text(stringResource(id = R.string.reset_confirmation_title)) },
            text = { Text(stringResource(id = R.string.reset_confirmation_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetUsage(slot)
                        showResetConfirmSlot = null
                    },
                ) {
                    Text(stringResource(id = R.string.reset_counters))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmSlot = null }) {
                    Text(stringResource(id = R.string.cancel_button))
                }
            },
        )
    }
}
