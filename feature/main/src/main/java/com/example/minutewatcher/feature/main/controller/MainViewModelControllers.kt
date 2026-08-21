package com.example.minutewatcher.feature.main.controller

import javax.inject.Inject

/**
 * Container for controllers used by MainViewModel.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 *
 * @property dialog Controller for dialog states.
 * @property banner Controller for tracking banners.
 * @property refresh Controller for SIM data refresh operations.
 * @property settings Controller for application settings.
 * @property simData Controller for SIM card data management.
 */
class MainViewModelControllers
    @Inject
    constructor(
        val dialog: MainDialogController,
        val banner: MainBannerController,
        val refresh: MainSimRefreshController,
        val settings: MainSettingsController,
        val simData: MainSimDataController,
        val init: MainInitializationController,
        val resume: MainResumeController,
    )
