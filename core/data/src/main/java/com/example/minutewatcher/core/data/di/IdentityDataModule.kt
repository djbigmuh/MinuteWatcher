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
abstract class IdentityDataModule {
    @Binds
    @Singleton
    internal abstract fun bindSimIdentitySeedRepository(impl: SimIdentitySeedRepositoryImpl): SimIdentitySeedRepository

    @Binds
    @Singleton
    internal abstract fun bindSimIdentityRepository(impl: SimIdentityRepositoryImpl): SimIdentityRepository

    companion object {
        /**
         * Provides [TelephonyManager] for SIM card and signal information.
         * Returns null if the service is unavailable on the device.
         */
        @Provides
        @Singleton
        fun provideTelephonyManager(
            @ApplicationContext context: Context,
        ): TelephonyManager? = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        /**
         * Provides [SubscriptionManager] for multi-SIM support.
         * Returns null if the service is unavailable on the device.
         */
        @Provides
        @Singleton
        fun provideSubscriptionManager(
            @ApplicationContext context: Context,
        ): SubscriptionManager? =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
    }
}
