package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface to provide string resources for widget UI mapping.
 * Used to decouple ViewModel from Android Context.
 */
interface WidgetStringProvider {
    fun getString(
        @StringRes resId: Int,
        vararg formatArgs: Any,
    ): String

    fun getShortOperatorName(operatorName: String): String
}

@Singleton
class WidgetStringProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WidgetStringProvider {
        override fun getString(
            @StringRes resId: Int,
            vararg formatArgs: Any,
        ): String = context.getString(resId, *formatArgs)

        override fun getShortOperatorName(operatorName: String): String =
            SimUtils.getShortOperatorName(context, operatorName)
    }
