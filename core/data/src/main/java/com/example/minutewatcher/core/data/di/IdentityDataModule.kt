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
@Suppress("unused")
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
         *
         * **Fail-fast Policy:** We use [checkNotNull] because [Context.TELEPHONY_SERVICE]
         * is a core requirement for the app's primary functionality. Its absence indicates
         * a severe device incompatibility or a broken Android environment.
         */
        @Provides
        @Singleton
        fun provideTelephonyManager(
            @ApplicationContext context: Context,
        ): TelephonyManager =
            checkNotNull(context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager) {
                "TELEPHONY_SERVICE unavailable"
            }

        /**
         * Provides [SubscriptionManager] for multi-SIM support.
         *
         * **Fail-fast Policy:** We use [checkNotNull] because [Context.TELEPHONY_SUBSCRIPTION_SERVICE]
         * is required for all identity resolution and tracking logic.
         */
        @Provides
        @Singleton
        fun provideSubscriptionManager(
            @ApplicationContext context: Context,
        ): SubscriptionManager =
            checkNotNull(context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager) {
                "TELEPHONY_SUBSCRIPTION_SERVICE unavailable"
            }
    }
}
