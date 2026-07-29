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
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.RefreshState
import com.example.minutewatcher.core.resources.R
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
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onGrantPhonePermission: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onGrantNotificationPermission: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val highlightedSlot by viewModel.highlightedSlot.collectAsStateWithLifecycle()
    val trackingBannerState by viewModel.trackingBannerState.collectAsStateWithLifecycle()
    val showUsageAccessDialog by viewModel.showUsageAccessDialog.collectAsStateWithLifecycle()
    val showOnboardingDialog by viewModel.showOnboardingDialog.collectAsStateWithLifecycle()
    val refreshStatus by viewModel.refreshStatus.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val genericErrorMessage = stringResource(id = R.string.error)

    LaunchedEffect(refreshStatus) {
        handleRefreshStatus(refreshStatus, snackbarHostState, genericErrorMessage, viewModel)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            SimCardTopBar(
                appTheme = appTheme,
                onThemeChange = onThemeChange,
                onLanguageChange = onLanguageChange,
                onOpenSettings = onOpenSettings,
            )
        },
        floatingActionButton = {
            MainFab(onClick = onAddWidget)
        },
    ) { paddingValues ->
        MainScreenContent(
            uiState = uiState,
            paddingValues = paddingValues,
            viewModel = viewModel,
            trackingBannerState = trackingBannerState,
            highlightedSlot = highlightedSlot,
            onGrantPhonePermission = onGrantPhonePermission,
            onOpenUsageSettings = onOpenUsageSettings,
            onOpenAppSettings = onOpenAppSettings,
            onGrantNotificationPermission = onGrantNotificationPermission,
        )
    }

    DialogRenderer(
        viewModel = viewModel,
        showUsageAccessDialog = showUsageAccessDialog,
        showOnboardingDialog = showOnboardingDialog,
        onOpenUsageSettings = onOpenUsageSettings,
    )
}

private fun handleRefreshStatus(
    status: RefreshState,
    snackbarHostState: SnackbarHostState,
    errorMsg: String,
    viewModel: MainViewModel,
) {
    if (status is RefreshState.Error) {
        viewModel.viewModelScope.launch {
            snackbarHostState.showSnackbar(status.message ?: errorMsg)
            viewModel.consumeRefreshStatus()
        }
    } else if (status is RefreshState.Success) {
        viewModel.consumeRefreshStatus()
    }
}

@Composable
private fun MainFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(stringResource(id = R.string.add_widget_button)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimCardTopBar(
    appTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(id = R.string.app_name)) },
        actions = {
            LanguageMenu(onLanguageChange)
            ThemeMenu(appTheme, onThemeChange)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(id = R.string.widget_settings_content_desc))
            }
        },
    )
}

@Composable
private fun LanguageMenu(onLanguageChange: (String?) -> Unit) {
    val languagesNames = stringArrayResource(id = R.array.languages)
    val languageCodes = stringArrayResource(id = R.array.language_codes)
    val languageIcons = stringArrayResource(id = R.array.language_icons)

    val languages =
        remember(languagesNames, languageCodes, languageIcons) {
            val minSize = minOf(languagesNames.size, languageCodes.size, languageIcons.size)
            (0 until minSize).map { index ->
                val code = languageCodes[index]
                LanguageOption(if (code == "default") null else code, languagesNames[index], languageIcons[index])
            }
        }

    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Text(stringResource(id = R.string.language_system_icon))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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
                        showMenu = false
                        onLanguageChange(lang.code)
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeMenu(
    appTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isNight =
        when (appTheme) {
            AppTheme.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            AppTheme.DARK -> true
            AppTheme.LIGHT -> false
        }

    Box {
        IconButton(onClick = { showMenu = true }) {
            Text(
                text = stringResource(if (isNight) R.string.theme_dark_icon else R.string.theme_light_icon),
                fontSize = 20.sp,
            )
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            AppTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text =
                                    when (theme) {
                                        AppTheme.FOLLOW_SYSTEM -> stringResource(id = R.string.theme_system_icon)
                                        AppTheme.LIGHT -> stringResource(id = R.string.theme_light_icon)
                                        AppTheme.DARK -> stringResource(id = R.string.theme_dark_icon)
                                    },
                                fontSize = 18.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text =
                                    when (theme) {
                                        AppTheme.FOLLOW_SYSTEM -> stringResource(id = R.string.theme_system)
                                        AppTheme.LIGHT -> stringResource(id = R.string.theme_light)
                                        AppTheme.DARK -> stringResource(id = R.string.theme_dark)
                                    },
                            )
                        }
                    },
                    onClick = {
                        showMenu = false
                        onThemeChange(theme)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    uiState: SimCardListState,
    paddingValues: PaddingValues,
    viewModel: MainViewModel,
    trackingBannerState: TrackingBannerState,
    highlightedSlot: Int?,
    onGrantPhonePermission: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onGrantNotificationPermission: () -> Unit,
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showResetConfirmSlot by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(highlightedSlot, uiState) {
        if (highlightedSlot != null && uiState is SimCardListState.Success) {
            val index = uiState.simCards.indexOfFirst { it.slot == highlightedSlot }
            if (index >= 0) listState.animateScrollToItem(index)
        }
    }

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
        when (uiState) {
            is SimCardListState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is SimCardListState.Empty -> EmptyState()
            is SimCardListState.Error -> ErrorState(uiState.message)
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
                            onGrantNotificationPermission = onGrantNotificationPermission,
                        )
                    }
                    items(uiState.simCards) { model ->
                        SimCardItem(
                            model = model,
                            onSetLimits = {
                                viewModel.openLimitDialog(
                                    LimitDialogData(model.slot, model.operatorName, model.minutesLimit, model.trafficLimitMb, model.rolloverEnabled),
                                )
                            },
                            onResetCounters = { showResetConfirmSlot = model.slot },
                            onToggleAutoReset = { enabled -> viewModel.toggleAutoReset(model.slot, enabled) },
                            onEditAutoResetDay = { viewModel.openDayDialog(DayDialogData(model.slot, model.autoResetDay)) },
                            onAdjustMinutes = { viewModel.openAdjustDialog(AdjustDialogData(model.slot, model.minutesRemaining.toFloat(), true)) },
                            onAdjustTraffic = { viewModel.openAdjustDialog(AdjustDialogData(model.slot, model.trafficRemainingGb, false)) },
                            onShowTrafficInfo = { viewModel.openTrafficLimitationDialog() },
                        )
                    }
                }
            }
        }
    }

    showResetConfirmSlot?.let { slot ->
        ResetConfirmationDialog(onDismiss = { showResetConfirmSlot = null }, onConfirm = {
            viewModel.resetUsage(slot)
            showResetConfirmSlot = null
        })
    }
}

@Composable
private fun DialogRenderer(
    viewModel: MainViewModel,
    showUsageAccessDialog: Boolean,
    showOnboardingDialog: Boolean,
    onOpenUsageSettings: () -> Unit,
) {
    val showLimitDialog by viewModel.showLimitDialog.collectAsStateWithLifecycle()
    val showDayDialog by viewModel.showDayDialog.collectAsStateWithLifecycle()
    val showAdjustDialog by viewModel.showAdjustDialog.collectAsStateWithLifecycle()
    val showTrafficLimitationDialog by viewModel.showTrafficLimitationDialog.collectAsStateWithLifecycle()

    showLimitDialog?.let { data ->
        SimLimitComposeDialog(
            operatorName = data.name,
            initialMinutes = data.minutes,
            initialTrafficMb = data.traffic,
            initialRolloverEnabled = data.rolloverEnabled,
            onDismiss = { viewModel.closeLimitDialog() },
            onConfirm = { min, traf, rollover ->
                viewModel.updateLimits(data.slot, min, traf, rollover)
                viewModel.closeLimitDialog()
            },
            onReset = {
                viewModel.updateLimits(data.slot, 0, 0, false)
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
        SimpleMessageDialog(
            title = stringResource(id = R.string.traffic_tracking_limitation_title),
            text = stringResource(id = R.string.traffic_tracking_limitation_body),
            onDismiss = { viewModel.closeTrafficLimitationDialog() },
        )
    }

    if (showUsageAccessDialog) {
        UsageAccessDialog(
            onDismiss = { viewModel.closeUsageAccessDialog() },
            onSettings = {
                viewModel.closeUsageAccessDialog()
                onOpenUsageSettings()
            },
        )
    }

    if (showOnboardingDialog) {
        OnboardingDialog(onDismiss = { viewModel.completeOnboarding() })
    }
}

@Composable
private fun ResetConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.reset_confirmation_title)) },
        text = { Text(stringResource(id = R.string.reset_confirmation_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.reset_counters)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel_button)) }
        },
    )
}

@Composable
private fun SimpleMessageDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_ok)) }
        },
    )
}

@Composable
private fun UsageAccessDialog(
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.usage_access_dialog_title)) },
        text = { Text(stringResource(id = R.string.usage_access_dialog_description)) },
        confirmButton = {
            Button(onClick = onSettings) { Text(stringResource(id = R.string.action_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel_button)) }
        },
    )
}

@Composable
private fun OnboardingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.onboarding_title)) },
        text = { Text(stringResource(id = R.string.onboarding_message)) },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(id = R.string.onboarding_button)) }
        },
    )
}
