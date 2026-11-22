package com.github.djbigmuh.minutewatcher.core.model

import com.github.djbigmuh.minutewatcher.core.model.util.ClampingUtils
import com.github.djbigmuh.minutewatcher.core.model.util.FontScaleProvider
import com.github.djbigmuh.minutewatcher.core.model.util.SemanticMapper
import com.github.djbigmuh.minutewatcher.core.model.util.WidgetContent
import com.github.djbigmuh.minutewatcher.core.model.util.buildSimSummary
import com.github.djbigmuh.minutewatcher.core.model.util.resolveWidgetContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class ModelDataClassesCoverageTest {
    @Test
    fun testActiveSimInfo() {
        val identity = SimIdentity("stable_1", 1, 0, IdentitySource.SUBSCRIPTION_ID, true)
        val info =
            ActiveSimInfo(
                identity = identity,
                slotIndex = 0,
                subscriptionId = 1,
                iccId = "123456",
                displayName = "SIM 1",
                carrierName = "Carrier",
                isDefaultData = true,
                isDefaultVoice = false,
            )

        assertEquals(identity, info.identity)
        assertEquals(0, info.slotIndex)
        assertEquals(1, info.subscriptionId)
        assertEquals("123456", info.iccId)
        assertEquals("SIM 1", info.displayName)
        assertEquals("Carrier", info.carrierName)
        assertTrue(info.isDefaultData)
        assertFalse(info.isDefaultVoice)

        val copy = info.copy(isDefaultVoice = true)
        assertTrue(copy.isDefaultVoice)
        assertEquals(info.hashCode(), info.hashCode())
        assertEquals(info, info)
        assertNotNull(info.toString())
    }

    @Test
    fun testSimIdentity() {
        val identity =
            SimIdentity(
                stableId = "id_1",
                subscriptionId = 10,
                slotIndex = 1,
                source = IdentitySource.ICCID,
                isStable = true,
            )

        assertEquals("id_1", identity.stableId)
        assertEquals(10, identity.subscriptionId)
        assertEquals(1, identity.slotIndex)
        assertEquals(IdentitySource.ICCID, identity.source)
        assertTrue(identity.isStable)

        val copy = identity.copy(isStable = false)
        assertFalse(copy.isStable)
        assertEquals(identity.hashCode(), identity.hashCode())
        assertEquals(identity, identity)
        assertNotNull(identity.toString())
    }

    @Test
    fun testSimUsageCoverage() {
        val usage =
            SimUsage(
                identity = "sim_1",
                slotIndex = 0,
                subscriptionId = 101,
                operator = "MTS",
                minutesUsed = 10,
                minutesLimit = 100,
                trafficUsedMb = 500f,
                trafficLimitMb = 1024,
                manualAdjustment = 5,
                trafficAdjustmentGb = 1.0f,
                autoReset = true,
                autoResetDay = 15,
                lastResetDate = 1000L,
                rolloverEnabled = true,
                brandKey = "mts",
            )

        assertEquals("sim_1", usage.identity)
        assertEquals(0, usage.slotIndex)
        assertEquals(101, usage.subscriptionId)
        assertEquals("MTS", usage.operator)
        assertEquals(10, usage.minutesUsed)
        assertEquals(100, usage.minutesLimit)
        assertEquals(500f, usage.trafficUsedMb, 0.001f)
        assertEquals(1024, usage.trafficLimitMb)
        assertEquals(5, usage.manualAdjustment)
        assertEquals(1.0f, usage.trafficAdjustmentGb, 0.001f)
        assertTrue(usage.autoReset)
        assertEquals(15, usage.autoResetDay)
        assertEquals(1000L, usage.lastResetDate)
        assertTrue(usage.rolloverEnabled)
        assertEquals("mts", usage.brandKey)

        val copy = usage.copy(rolloverEnabled = false)
        assertFalse(copy.rolloverEnabled)
        assertEquals(usage.hashCode(), usage.hashCode())
        assertEquals(usage, usage)
        assertNotNull(usage.toString())
    }

    @Test
    fun testSimDiscoveryResultAndReasons() {
        val identity = SimIdentity("id_1", 1, 0, IdentitySource.ICCID, true)
        val sim = ActiveSimInfo(identity, 0, 1, null, null, null, true, true)
        val available = SimDiscoveryResult.Available(listOf(sim))
        assertEquals(1, available.sims.size)

        assertEquals(SimDiscoveryResult.PermissionMissing, SimDiscoveryResult.PermissionMissing)
        assertEquals(SimDiscoveryResult.Unsupported, SimDiscoveryResult.Unsupported)

        val failed = SimDiscoveryResult.Failed(SimDiscoveryFailureReason.API_ERROR)
        assertEquals(SimDiscoveryFailureReason.API_ERROR, failed.reason)

        assertEquals(3, SimDiscoveryFailureReason.entries.size)
        assertTrue(SimDiscoveryFailureReason.entries.contains(SimDiscoveryFailureReason.STORAGE_ERROR))
        assertTrue(SimDiscoveryFailureReason.entries.contains(SimDiscoveryFailureReason.UNKNOWN))
    }

    @Test
    fun testMigrationSkipReason() {
        assertEquals(3, MigrationSkipReason.entries.size)
        assertTrue(MigrationSkipReason.entries.contains(MigrationSkipReason.NO_LEGACY_DATA))
        assertTrue(MigrationSkipReason.entries.contains(MigrationSkipReason.LEGACY_DATA_CHECK_FAILED))
        assertTrue(MigrationSkipReason.entries.contains(MigrationSkipReason.MIGRATION_PARTIAL_FAILURE))
    }

    @Test
    fun testSemanticContracts() {
        assertEquals(2, PackageType.entries.size)
        assertEquals(2, MeasurementUnit.entries.size)
        assertEquals(2, DataSourceType.entries.size)

        val entity =
            SemanticEntity(
                id = "entity_1",
                simSlot = 0,
                operatorName = "Operator",
                packageType = PackageType.MINUTES,
                limit = 100L,
                used = 10L,
                remaining = 90L,
                unit = MeasurementUnit.MINUTES,
                periodStart = 1000L,
                periodEnd = 2000L,
                lastUpdateTime = 1500L,
                sourceType = DataSourceType.SYSTEM,
            )

        assertEquals("entity_1", entity.id)
        assertEquals(PackageType.MINUTES, entity.packageType)
        assertEquals(MeasurementUnit.MINUTES, entity.unit)
        assertEquals(DataSourceType.SYSTEM, entity.sourceType)

        val cap = AppCapability("search", true, "AppSearch capability")
        assertEquals("search", cap.type)
        assertTrue(cap.isSupported)

        val version = SchemaVersion(1, 2, 3)
        assertEquals("1.2.3", version.toString())

        val snapshot =
            SimSnapshot(
                slot = 0,
                identity = "id_1",
                operatorName = "Operator",
                entities = mapOf(PackageType.MINUTES to entity),
                autoResetDay = 1,
                autoReset = true,
                brandKey = "brand",
            )
        assertTrue(snapshot.autoReset)
        assertEquals("brand", snapshot.brandKey)

        val global =
            GlobalSnapshot(
                version = version,
                timestamp = 5000L,
                simSnapshots = listOf(snapshot),
                capabilities = listOf(cap),
                isManualUpdate = true,
                suppressWidgetUpdate = false,
                revision = 1L,
            )
        assertTrue(global.isManualUpdate)
        assertEquals(1L, global.revision)
    }

    @Test
    fun testTrackingKeyAndStatus() {
        assertEquals("traffic", TrackingKey.TRAFFIC.id)
        assertEquals(8, TrackingKey.entries.size)

        val idle = TrackingStatus.Idle
        val active = TrackingStatus.Active
        val ready = TrackingStatus.Ready(100L)
        val err =
            TrackingStatus.Error(
                type = TrackingStatus.ErrorType.PERMISSION_MISSING,
                message = "Permission missing",
                code = TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE,
            )

        assertEquals(idle, TrackingStatus.Idle)
        assertEquals(active, TrackingStatus.Active)
        assertEquals(100L, ready.lastUpdateTimestamp)
        assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, err.type)
        assertEquals("Permission missing", err.message)
        assertEquals(TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE, err.code)

        assertEquals(5, TrackingStatus.ErrorType.entries.size)
        assertEquals(4, TrackingStatus.ErrorCode.entries.size)
        assertEquals(50, TrackingStatus.PRIORITY_PERMISSION_MISSING)
        assertEquals(40, TrackingStatus.PRIORITY_DATABASE_ERROR)
        assertEquals(30, TrackingStatus.PRIORITY_TRANSIENT_ERROR)
        assertEquals(20, TrackingStatus.PRIORITY_HEALTHY)

        assertTrue(TrackingStatus.ErrorType.PERMISSION_MISSING.isSticky())
        assertTrue(TrackingStatus.ErrorType.DATABASE_ERROR.isSticky())
        assertFalse(TrackingStatus.ErrorType.UNKNOWN.isSticky())
        assertFalse(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION.isSticky())
        assertFalse(TrackingStatus.ErrorType.NOT_SUPPORTED.isSticky())
    }

    @Test
    fun testWidgetUiModelsAndStrings() {
        val strings =
            WidgetStrings(
                usedText = "Used",
                minutesAbbr = "min",
                limitText = "Limit",
                gbAbbr = "GB",
                refreshErrorText = "Error",
                settingsLoadErrorText = "Load error",
                noDataText = "No data",
                noSimFoundText = "No SIM",
                staleDataWarningText = "Stale",
                simStatusSummaryFormat = "%1\$s %2\$s %3\$s",
                valuePlaceholder = "—",
                labelValueFormat = "%1\$s: %2\$s",
                limitSuffixFormat = "/%1\$s",
                locale = Locale.US,
            )

        assertEquals("Used", strings.usedText)
        assertEquals("min", strings.minutesAbbr)

        val icon = SimLayoutComponent.Icon(1, 16)
        val text = SimLayoutComponent.Text("Text", 0xFF000000.toInt(), 12f, true)
        val spacer = SimLayoutComponent.Spacer(4, 4)

        assertEquals(1, icon.iconRes)
        assertEquals("Text", text.text)
        assertEquals(4, spacer.widthDp)

        val row = SimLayoutRow(listOf(icon, text, spacer))
        val spec = SimLayoutSpec(listOf(row))
        assertEquals(1, spec.rows.size)

        val simUi =
            SimUiState(
                slot = 0,
                operatorName = "MTS",
                operatorIconRes = 10,
                showSim = true,
                showIcon = true,
                showName = true,
                showMinutes = true,
                minutesText = "100",
                minutesSuffix = "min",
                minutesColor = 0,
                showMinutesLimit = true,
                minutesLimitText = "200",
                showTraffic = true,
                trafficText = "5",
                trafficSuffix = "GB",
                trafficColor = 0,
                showTrafficLimit = true,
                trafficLimitText = "10",
                fontMultiplier = 1.0f,
                textColor = 0,
                secondaryTextColor = 0,
                layoutSpec = spec,
            )

        assertEquals("MTS", simUi.operatorName)

        val summary = buildSimSummary(strings, simUi)
        assertEquals("MTS 100 5", summary)

        val badStrings = strings.copy(simStatusSummaryFormat = "%d %d %d %d")
        val fallbackSummary = buildSimSummary(badStrings, simUi)
        assertEquals("MTS: 100, 5", fallbackSummary)

        val emptyFormatStrings = strings.copy(simStatusSummaryFormat = "")
        val emptyFormatSummary = buildSimSummary(emptyFormatStrings, simUi)
        assertEquals("MTS: 100, 5", emptyFormatSummary)

        val widgetUi =
            WidgetUiState(
                layoutMode = WidgetLayoutMode.THREE_LINES.value,
                simArrangement = 0,
                backgroundColor = 0,
                transparency = 0,
                simUiStates = listOf(simUi),
                isHorizontal = true,
                strings = strings,
            )

        assertEquals(1, widgetUi.simUiStates.size)
        assertTrue(widgetUi.isHorizontal)
        assertEquals(WidgetContent.Sims(listOf(simUi)), resolveWidgetContent(widgetUi))
    }

    @Test
    fun testSimCardUiModel() {
        val model =
            SimCardUiModel(
                slot = 0,
                identity = "id_1",
                operatorName = "MTS",
                iconRes = 10,
                minutesUsed = 10,
                minutesLimit = 100,
                manualAdjustment = 0,
                minutesRemaining = 90,
                effectiveMinutesLimit = 100,
                isMinutesLimitSet = true,
                trafficUsedMb = 500f,
                trafficLimitMb = 1024,
                trafficRemainingGb = 0.5f,
                effectiveTrafficLimitGb = 1.0f,
                trafficAdjustmentGb = 0f,
                isTrafficLimitSet = true,
                autoResetEnabled = true,
                autoResetDay = 1,
                rolloverEnabled = false,
            )

        assertEquals(0, model.slot)
        assertEquals("MTS", model.operatorName)
        assertEquals(10, model.minutesUsed)
        assertTrue(model.autoResetEnabled)
        assertFalse(model.rolloverEnabled)
    }

    @Test
    fun testWidgetSettingsStateValidation() {
        val simState =
            SimSettingsState(
                showSim = true,
                showIcon = true,
                showName = true,
                showMinutes = true,
                showMinutesLimit = true,
                showTraffic = true,
                showTrafficLimit = true,
                lastKnownIdentity = "sim1",
            )
        assertEquals("sim1", simState.lastKnownIdentity)

        val invalidState =
            WidgetSettingsState(
                layoutMode = 999,
                simArrangement = 999,
                transparency = 999,
                fontSize = 999,
                simStates = mapOf(0 to simState),
            )

        val validated = WidgetSettingsState.validate(invalidState)
        assertEquals(Constants.Widget.MAX_SELECTABLE_LAYOUT_MODE, validated.layoutMode)
        assertEquals(Constants.Widget.MAX_SIM_ARRANGEMENT, validated.simArrangement)
        assertEquals(Constants.Widget.MAX_TRANSPARENCY, validated.transparency)
        assertEquals(Constants.Widget.MAX_FONT_SIZE, validated.fontSize)

        val validState =
            WidgetSettingsState(
                layoutMode = Constants.Widget.DEFAULT_LAYOUT_MODE,
                simArrangement = Constants.Widget.SIM_ARRANGEMENT_HORIZONTAL,
                transparency = Constants.Widget.DEFAULT_TRANSPARENCY,
                fontSize = Constants.Widget.DEFAULT_FONT_SIZE_INDEX,
            )
        assertEquals(validState, WidgetSettingsState.validate(validState))
    }

    @Test
    fun testFontScaleProviderAllCases() {
        assertEquals(0.8f, FontScaleProvider.getMultiplier(0), 0.001f)
        assertEquals(0.9f, FontScaleProvider.getMultiplier(1), 0.001f)
        assertEquals(1.0f, FontScaleProvider.getMultiplier(2), 0.001f)
        assertEquals(1.1f, FontScaleProvider.getMultiplier(3), 0.001f)
        assertEquals(1.2f, FontScaleProvider.getMultiplier(4), 0.001f)
        assertEquals(1.0f, FontScaleProvider.getMultiplier(100), 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testClampingUtilsInvalidRangeThrows() {
        ClampingUtils.clamp(5, 10, 2)
    }

    @Test
    fun testClampingUtilsValidRange() {
        assertEquals(5, ClampingUtils.clamp(5, 1, 10))
        assertEquals(1, ClampingUtils.clamp(-5, 1, 10))
        assertEquals(10, ClampingUtils.clamp(15, 1, 10))
    }

    @Test
    fun testSemanticMapperMethods() {
        val zone = ZoneId.of("UTC")
        val minutesModel = MinutesModel.calculate(used = 10, limit = 100, manualAdjustment = 5)
        val minutesEntity = SemanticMapper.mapMinutes(0, "MTS", minutesModel, 1, 1600000000000L, zone)

        assertEquals("sim_0_minutes", minutesEntity.id)
        assertEquals(DataSourceType.MANUAL, minutesEntity.sourceType)

        val trafficModel = TrafficModel.calculate(usedMb = 500f, limitMb = 2048, manualAdjustmentGb = 0f)
        val trafficEntity = SemanticMapper.mapTraffic(0, "MTS", trafficModel, 1, 1600000000000L, zone)

        assertEquals("sim_0_data", trafficEntity.id)
        assertEquals(DataSourceType.SYSTEM, trafficEntity.sourceType)
    }

    @Test
    fun testAppSettingsAndAppTheme() {
        assertEquals(3, AppTheme.entries.size)
        assertTrue(AppTheme.entries.contains(AppTheme.FOLLOW_SYSTEM))
        assertEquals(AppTheme.FOLLOW_SYSTEM, AppTheme.fromValue(-1))
        assertEquals(AppTheme.LIGHT, AppTheme.fromValue(1))
        assertEquals(AppTheme.DARK, AppTheme.fromValue(2))
        assertEquals(AppTheme.FOLLOW_SYSTEM, AppTheme.fromValue(99))

        val settings =
            AppSettings(
                theme = AppTheme.DARK,
                language = "ru",
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                aodSummaryEnabled = false,
            )

        assertEquals(AppTheme.DARK, settings.theme)
        assertEquals("ru", settings.language)
        assertFalse(settings.aodSummaryEnabled)
    }

    @Test
    fun testPermissionUiState() {
        assertEquals(3, PermissionUiState.entries.size)
        assertTrue(PermissionUiState.entries.contains(PermissionUiState.GRANTED))
        assertTrue(PermissionUiState.entries.contains(PermissionUiState.NOT_REQUESTED))
        assertTrue(PermissionUiState.entries.contains(PermissionUiState.PERMANENTLY_DENIED))
    }

    @Test
    fun testConstantsCoverage() {
        assertEquals("app_prefs", Constants.PREFS_NAME)
        assertEquals("secret_usage_prefs", Constants.LEGACY_SIM_PREFS_NAME)
        assertEquals("usage_monitor_channel", Constants.Notifications.CHANNEL_ID)
        assertEquals("aod_summary_channel", Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
        assertEquals("aod_summary_channel_v2", Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2)
        assertEquals(15 * 60_000L, Constants.Aod.HEARTBEAT_INTERVAL_MS)
        assertEquals(0xFF000000.toInt(), Constants.UI.DEFAULT_BG_COLOR)
        assertEquals(0.5f, Constants.UI.LUMINANCE_THRESHOLD, 0.001f)
        assertEquals(0.8f, Constants.UI.SWITCH_SCALE, 0.001f)
        assertEquals(3000L, Constants.UI.HIGHLIGHT_DELAY_MS)
        assertEquals(72, Constants.UI.SWIPE_NAVIGATION_THRESHOLD_DP)
        assertEquals(6, Constants.UI.MAX_MINUTES_INPUT_CHARS)
        assertEquals(10, Constants.UI.MAX_TRAFFIC_INPUT_CHARS)
        assertEquals(14f, Constants.Widget.FONT_SIZE_MEDIUM, 0.001f)
        assertEquals(300L, Constants.Widget.DEBOUNCE_MS)
        assertEquals(0.01f, Constants.Traffic.MIN_DELTA_MB, 0.001f)
        assertEquals(2000L, Constants.Data.SNAPSHOT_TIMEOUT_MS)
        assertEquals(60_000L, Constants.Data.STALE_SNAPSHOT_THRESHOLD_MS)
        assertEquals(0L, Constants.Data.EMPTY_TIMESTAMP)
        assertEquals(1577836800000L, Constants.Data.MIN_VALID_TIMESTAMP)
        assertEquals(30_000L, Constants.Semantic.THROTTLE_MS)
        assertEquals(10_000L, Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS)
        assertEquals(15_000L, Constants.Semantic.INITIAL_SNAPSHOT_TIMEOUT_MS)
        assertEquals(1000L, Constants.Time.MILLIS_IN_SECOND)
        assertEquals(60, Constants.Time.SECONDS_IN_MINUTE)
        assertEquals(60, Constants.Time.MINUTES_IN_HOUR)
        assertEquals(10_000L, Constants.Time.WIDGET_UPDATE_TIMEOUT_MS)
        assertEquals(1, Constants.AutoReset.MIN_DAY)
        assertEquals(31, Constants.AutoReset.MAX_DAY)
        assertEquals(5_000L, Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS)
        assertEquals(5_000L, Constants.Flow.DEFAULT_READ_TIMEOUT_MS)
        assertEquals(100L, Constants.Flow.DEBOUNCE_MS)
        assertEquals(5_000L, Constants.Flow.SHORT_TIMEOUT_MS)
        assertEquals(10_000L, Constants.Flow.LONG_TIMEOUT_MS)
        assertEquals(12 * 3600 * 1000L, Constants.Calls.MAX_CALL_DURATION_MS)
        assertEquals(15 * 60_000L, Constants.Calls.BOOT_EPOCH_TOLERANCE_MS)
        assertEquals(3, Constants.Calls.ATTRIBUTION_FAILURE_LIMIT)
        assertEquals(3, Constants.Calls.INACCURATE_ATTRIBUTION_BANNER_THRESHOLD)
        assertEquals("sim_slot", Constants.IntentExtras.SIM_SLOT)
        assertEquals("shortcut_id", Constants.IntentExtras.SHORTCUT_ID)
        assertEquals(10, Constants.SimConstants.MAX_SUPPORTED_SIM_SLOTS)
        assertEquals(0 until 10, Constants.SimConstants.SUPPORTED_SLOT_INDICES)
        assertEquals(1024L * 1024L, Constants.BYTES_PER_MB)
        assertEquals(1024L * 1024L * 1024L, Constants.BYTES_PER_GB)
        assertEquals(1024.0f, Constants.MB_PER_GB, 0.001f)
        assertEquals(10, Constants.CRITICAL_THRESHOLD_PERCENT_INT)
        assertEquals(20, Constants.LOW_THRESHOLD_PERCENT_INT)
        assertEquals(44640, Constants.MAX_MINUTES_LIMIT)
        assertEquals(10240f, Constants.MAX_TRAFFIC_LIMIT_GB, 0.001f)
        assertEquals((10240f * 1024.0f).toInt(), Constants.MAX_TRAFFIC_LIMIT_MB)
    }
}
