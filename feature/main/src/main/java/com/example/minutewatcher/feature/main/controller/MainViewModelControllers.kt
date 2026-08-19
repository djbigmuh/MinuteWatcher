package com.example.minutewatcher.feature.main.controller

import javax.inject.Inject

/**
 * Контейнер контроллеров для MainViewModel.
 * Содержит контроллеры диалогов, баннеров, обновления, настроек и данных SIM.
 *
 * @property dialog Контроллер диалогов.
 * @property banner Контроллер баннеров.
 * @property refresh Контроллер обновления.
 * @property settings Контроллер настроек.
 * @property simData Контроллер данных SIM.
 */
class MainViewModelControllers
    @Inject
    constructor(
        val dialog: MainDialogController,
        val banner: MainBannerController,
        val refresh: MainSimRefreshController,
        val settings: MainSettingsController,
        val simData: MainSimDataController,
    )
