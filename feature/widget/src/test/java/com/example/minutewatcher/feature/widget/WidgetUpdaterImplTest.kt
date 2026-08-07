package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.domain.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class WidgetUpdaterImplTest {
    private val context: Context = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val scope: CoroutineScope = mock()

    private lateinit var updater: WidgetUpdaterImpl

    @Before
    fun setup() {
        updater =
            WidgetUpdaterImpl(
                context = context,
                scope = scope,
                appSettingsRepository = appSettingsRepository,
            )
    }

    @Test
    fun `isRemoteViewsLimitException returns false for generic IllegalArgumentException`() {
        val e =
            IllegalArgumentException(
                "Something else went wrong",
            )
        assertFalse(
            updater.isRemoteViewsLimitException(
                e,
            ),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns true for TransactionTooLargeException cause`() {
        val cause = android.os.TransactionTooLargeException()
        val e =
            IllegalArgumentException(
                "Failure sending RemoteViews",
                cause,
            )
        assertTrue(
            updater.isRemoteViewsLimitException(
                e,
            ),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns true when stack trace contains markers`() {
        val e =
            IllegalArgumentException(
                "Fake exception",
            )
        // We can't easily mock stackTraceToString() as it's an extension function,
        // but we can set the stack trace of the exception.
        e.stackTrace =
            arrayOf(
                StackTraceElement(
                    "android.widget.RemoteViews",
                    "writeToParcel",
                    "RemoteViews.java",
                    100,
                ),
                StackTraceElement(
                    "android.os.TransactionTooLargeException",
                    "<init>",
                    "TransactionTooLargeException.java",
                    1,
                ),
            )
        assertTrue(
            updater.isRemoteViewsLimitException(
                e,
            ),
        )
    }
}
