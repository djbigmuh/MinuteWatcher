package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import host.serv.djbigmuh.minutewatcher.core.android.ui.onHorizontalSwipe
import host.serv.djbigmuh.minutewatcher.core.android.ui.resolveNavigationSwipeDirection
import host.serv.djbigmuh.minutewatcher.core.android.util.WidgetStringsFactory
import host.serv.djbigmuh.minutewatcher.core.android.util.WidgetUiMapper
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetUiState
import host.serv.djbigmuh.minutewatcher.core.resources.R

private typealias SimSettingsTransform = (SimSettingsState) -> SimSettingsState

@Composable
fun WidgetSettingsScreen(
    viewModel: WidgetSettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val aodSummaryAvailable by viewModel.aodSummaryAvailable.collectAsStateWithLifecycle()
    val aodSummaryHasContent by viewModel.aodSummaryHasContent.collectAsStateWithLifecycle()

    ObserveResumeEffect(onResume = viewModel::refreshAodSummaryAvailability)

    val context = LocalContext.current
    val strings = remember(context) { WidgetStringsFactory.create(context) }

    val screenUiState =
        remember(uiState.settings, snapshot, strings, context) {
            val settings = uiState.settings ?: return@remember null
            val operatorNames = WidgetStringsFactory.createOperatorNames(context, snapshot.simSnapshots)
            WidgetUiMapper.mapFromSnapshot(
                strings = strings,
                snapshot = snapshot,
                state = settings,
                operatorNames = operatorNames,
            )
        }

    WidgetSettingsScreenBody(
        isLoadError = uiState.isLoadError,
        settings = uiState.settings,
        screenUiState = screenUiState,
        saveResult = uiState.saveResult,
        aodSummaryAvailable = aodSummaryAvailable,
        aodSummaryHasContent = aodSummaryHasContent,
        onRetryLoad = viewModel::retryLoad,
        onConsumeSaveResult = viewModel::consumeSaveResult,
        onUpdateSettings = viewModel::updateSettings,
        onUpdateSimSettings = viewModel::updateSimSettings,
        onBack = onBack,
    )
}

@Composable
fun WidgetSettingsScreenBody(
    isLoadError: Boolean,
    settings: WidgetSettingsState?,
    screenUiState: WidgetUiState?,
    saveResult: SaveResult?,
    aodSummaryAvailable: Boolean,
    aodSummaryHasContent: Boolean,
    onRetryLoad: () -> Unit,
    onConsumeSaveResult: () -> Unit,
    onUpdateSettings: ((WidgetSettingsState) -> WidgetSettingsState) -> Unit,
    onUpdateSimSettings: (slot: Int, transform: SimSettingsTransform) -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onHorizontalSwipe(
                    resolveNavigationSwipeDirection(
                        navigateForward = false,
                        isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
                    ),
                    Constants.UI.SWIPE_NAVIGATION_THRESHOLD_DP.dp,
                ) { onBack() },
    ) {
        if (isLoadError) {
            ErrorState(
                onRetry = onRetryLoad,
                onBack = onBack,
            )
        } else if (settings == null) {
            LoadingState()
        } else {
            WidgetSettingsContent(
                settings = settings,
                screenUiState = screenUiState,
                saveResult = saveResult,
                aodSummaryAvailable = aodSummaryAvailable,
                aodSummaryHasContent = aodSummaryHasContent,
                onConsumeSaveResult = onConsumeSaveResult,
                onUpdateSettings = onUpdateSettings,
                onUpdateSimSettings = onUpdateSimSettings,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { SettingsTopAppBar(onBack = onBack) },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(id = R.string.error),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(text = stringResource(id = R.string.action_retry))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetSettingsContent(
    settings: WidgetSettingsState,
    screenUiState: WidgetUiState?,
    saveResult: SaveResult?,
    aodSummaryAvailable: Boolean,
    aodSummaryHasContent: Boolean,
    onConsumeSaveResult: () -> Unit,
    onUpdateSettings: ((WidgetSettingsState) -> WidgetSettingsState) -> Unit,
    onUpdateSimSettings: (slot: Int, transform: SimSettingsTransform) -> Unit,
    onBack: () -> Unit,
) {
    var showColorPicker by rememberSaveable { mutableStateOf(value = false) }
    val snackbarHostState = remember { SnackbarHostState() }

    HandleSaveResultEffect(
        saveResult = saveResult,
        snackbarHostState = snackbarHostState,
        onConsumeSaveResult = onConsumeSaveResult,
    )

    Scaffold(
        topBar = { SettingsTopAppBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            BottomPreviewBar(
                uiState = screenUiState,
            )
        },
    ) { paddingValues ->
        WidgetSettingsList(
            modifier = Modifier.padding(paddingValues),
            state = settings,
            uiState = screenUiState,
            aodSummaryAvailable = aodSummaryAvailable,
            aodSummaryHasContent = aodSummaryHasContent,
            onUpdateSettings = onUpdateSettings,
            onUpdateSimSettings = onUpdateSimSettings,
        ) {
            showColorPicker = true
        }

        if (showColorPicker) {
            ColorPickerWrapper(
                initialColor = settings.backgroundColor,
                onColorSelected = { color ->
                    onUpdateSettings { it.copy(backgroundColor = color) }
                },
                onDismiss = {
                    showColorPicker = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopAppBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(id = R.string.settings_widget_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.navigate_up_content_desc),
                )
            }
        },
    )
}

@Composable
private fun HandleSaveResultEffect(
    saveResult: SaveResult?,
    snackbarHostState: SnackbarHostState,
    onConsumeSaveResult: () -> Unit,
) {
    val resources = LocalResources.current
    val partialSuccessMessage = stringResource(id = R.string.settings_save_partial_success)
    val failedMessage = stringResource(id = R.string.settings_save_failed)
    val partialFailureGeneralMessage = stringResource(id = R.string.settings_save_partial_failure)

    LaunchedEffect(saveResult) {
        if (saveResult != null) {
            try {
                val message =
                    resolveSaveResultMessage(
                        saveResult = saveResult,
                        partialSuccessMessage = partialSuccessMessage,
                        failedMessage = failedMessage,
                        partialFailureGeneralMessage = partialFailureGeneralMessage,
                        resources = resources,
                    )
                message?.let { snackbarHostState.showSnackbar(it) }
            } finally {
                onConsumeSaveResult()
            }
        }
    }
}

private fun resolveSaveResultMessage(
    saveResult: SaveResult,
    partialSuccessMessage: String,
    failedMessage: String,
    partialFailureGeneralMessage: String,
    resources: Resources,
): String? =
    when (saveResult) {
        SaveResult.Success -> null
        is SaveResult.PartialSuccess -> partialSuccessMessage
        is SaveResult.PartialFailure -> {
            if (saveResult.failedSlots.isEmpty()) {
                partialFailureGeneralMessage
            } else {
                val slotsString = saveResult.failedSlots.map { it + 1 }.joinToString(", ")
                resources.getString(R.string.settings_save_partial_failure_slots, slotsString)
            }
        }

        is SaveResult.Failed -> failedMessage
    }

@Composable
private fun WidgetSettingsList(
    modifier: Modifier = Modifier,
    state: WidgetSettingsState,
    uiState: WidgetUiState?,
    aodSummaryAvailable: Boolean,
    aodSummaryHasContent: Boolean,
    onUpdateSettings: ((WidgetSettingsState) -> WidgetSettingsState) -> Unit,
    onUpdateSimSettings: (slot: Int, transform: SimSettingsTransform) -> Unit,
    onShowColorPicker: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            modifier
                .verticalScroll(scrollState)
                .padding(16.dp),
    ) {
        GeneralSettingsSection(
            state = state,
            enabled = true,
            onUpdateLayoutMode = { mode -> onUpdateSettings { it.copy(layoutMode = mode) } },
            onUpdateSimArrangement = { arr -> onUpdateSettings { it.copy(simArrangement = arr) } },
            onUpdateTransparency = { tr -> onUpdateSettings { it.copy(transparency = tr) } },
            onUpdateFontSize = { fs -> onUpdateSettings { it.copy(fontSize = fs) } },
            onShowColorPicker = onShowColorPicker,
        )

        Spacer(modifier = Modifier.height(16.dp))

        TrackingSettingsSection(
            state = state,
            enabled = true,
            onUpdateCallTracking = { en -> onUpdateSettings { it.copy(callTrackingEnabled = en) } },
            onUpdateAssistantAccess = { en -> onUpdateSettings { it.copy(assistantAccessEnabled = en) } },
        )

        Spacer(modifier = Modifier.height(16.dp))

        NotificationSettingsSection(
            state = state,
            enabled = true,
            aodSummaryAvailable = aodSummaryAvailable,
            aodSummaryHasContent = aodSummaryHasContent,
            onUpdateNotifyMinutes = { en -> onUpdateSettings { it.copy(notifyLowMinutes = en) } },
            onUpdateNotifyTraffic = { en -> onUpdateSettings { it.copy(notifyLowTraffic = en) } },
            onUpdateAodSummary = { en -> onUpdateSettings { it.copy(aodSummaryEnabled = en) } },
        )

        Spacer(modifier = Modifier.height(16.dp))

        SimCardsSettingsSection(
            state = state,
            uiState = uiState,
            enabled = !state.simListLoadFailed,
            onUpdateSimSettings = onUpdateSimSettings,
        )
    }
}

@Composable
private fun ObserveResumeEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    onResume()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun WidgetSettingsScreenPreview() {
    MaterialTheme {
        WidgetSettingsScreenBody(
            isLoadError = false,
            settings = WidgetSettingsState(),
            screenUiState = null,
            saveResult = null,
            aodSummaryAvailable = true,
            aodSummaryHasContent = false,
            onRetryLoad = {},
            onConsumeSaveResult = {},
            onUpdateSettings = {},
            onUpdateSimSettings = { _, _ -> },
            onBack = {},
        )
    }
}
