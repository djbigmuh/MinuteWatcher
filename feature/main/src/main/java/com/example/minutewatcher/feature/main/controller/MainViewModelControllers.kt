package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.domain.WidgetUpdateCoordinator
import dagger.Lazy
import javax.inject.Inject

/**
 * Container for controllers used by MainViewModel.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 *
 * @property banner Controller for tracking banners.
 * @property refresh Controller for SIM data refresh operations.
 * @property settings Controller for application settings.
 * @property simData Controller for SIM card data management.
 * @property resume Controller for activity resume logic.
 * @property widgetObservationRecovery Lazy reference to coordinator for recovery.
 */
internal class MainViewModelControllers
    @Inject
    constructor(
        val banner: MainBannerController,
        val refresh: MainSimRefreshController,
        val settings: MainSettingsController,
        val simData: MainSimDataController,
        val resume: MainResumeController,
        val widgetObservationRecovery: Lazy<WidgetUpdateCoordinator>,
    )
