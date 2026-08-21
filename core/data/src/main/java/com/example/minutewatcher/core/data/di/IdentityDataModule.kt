package com.example.minutewatcher.core.data.di

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.example.minutewatcher.core.data.SimIdentityRepositoryImpl
import com.example.minutewatcher.core.data.SimIdentitySeedRepositoryImpl
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
// @Suppress("unused") is required because Hilt discovers bindings via reflection.
// Removing this will cause runtime injection failures.
@Suppress("unused")
abstract class IdentityDataModule {
    @Binds
    @Singleton
    abstract fun bindSimIdentitySeedRepository(impl: SimIdentitySeedRepositoryImpl): SimIdentitySeedRepository

    @Binds
    @Singleton
    abstract fun bindSimIdentityRepository(impl: SimIdentityRepositoryImpl): SimIdentityRepository

    companion object {
        @Provides
        @Singleton
        fun provideTelephonyManager(
            @ApplicationContext context: Context,
        ): TelephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        @Provides
        @Singleton
        fun provideSubscriptionManager(
            @ApplicationContext context: Context,
        ): SubscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    }
}
