package com.example.minutewatcher.feature.main.controller

import javax.inject.Inject

class MainViewModelControllers
    @Inject
    constructor(
        val dialog: MainDialogController,
        val banner: MainBannerController,
        val refresh: MainSimRefreshController,
        val settings: MainSettingsController,
        val simData: MainSimDataController,
    )
