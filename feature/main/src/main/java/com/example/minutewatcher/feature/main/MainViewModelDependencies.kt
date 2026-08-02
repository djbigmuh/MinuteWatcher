package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MainViewModelDependencies
    @Inject
    constructor(
        val simIdentityRepository: SimIdentityRepository,
        val simLimitsRepository: SimLimitsRepository,
        val appSettingsRepository: AppSettingsRepository,
        val usageStateRepository: UsageStateRepository,
        val autoResetUseCase: AutoResetUseCase,
        val usageUseCase: UsageUseCase,
        val settingsUseCase: SettingsUseCase,
        val permissionStateNotifier: PermissionStateNotifier,
        val permissionRepository: PermissionRepository,
        val dataChangeNotifier: DataChangeNotifier,
    )
