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
        updater = WidgetUpdaterImpl(context, scope, appSettingsRepository)
    }

    @Test
    fun `isRemoteViewsLimitException returns true for RemoteViews message`() {
        val e = IllegalArgumentException("RemoteViews too large")
        assertTrue(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns true for transaction size message`() {
        val e = IllegalArgumentException("The transaction is too large")
        assertTrue(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns false for other messages`() {
        val e = IllegalArgumentException("Something else went wrong")
        assertFalse(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns true for TransactionTooLargeException cause`() {
        val cause = android.os.TransactionTooLargeException()
        val e = IllegalArgumentException("Failure sending RemoteViews", cause)
        assertTrue(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns true for exceeds message fallback`() {
        val e = IllegalArgumentException("RemoteViews exceeds maximum size")
        assertTrue(updater.isRemoteViewsLimitException(e))
    }
}
