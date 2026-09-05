package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.WidgetUpdateCoordinator
import javax.inject.Inject
import javax.inject.Singleton

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
 * @property dataRefreshNotifier Notifier for data refresh events.
 * @property widgetObservationRecovery Lazy reference to coordinator for recovery.
 */
@Singleton
class MainViewModelControllers
    @Inject
    constructor(
        val banner: MainBannerController,
        val refresh: MainSimRefreshController,
        val settings: MainSettingsController,
        val simData: MainSimDataController,
        val dataRefreshNotifier: DataRefreshNotifier,
        val widgetObservationRecovery: dagger.Lazy<WidgetUpdateCoordinator>,
    )
