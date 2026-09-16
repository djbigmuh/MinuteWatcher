package com.example.minutewatcher.feature.semantic.search

import android.os.DeadObjectException
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class AppSearchErrorHandlerTest {
    private val timeProvider: TimeProvider = mock()
    private val errorHandler = AppSearchErrorHandler(timeProvider)

    @Test
    fun `handleInitializationError classifies IllegalStateException with not supported as UNKNOWN`() {
        val exception = IllegalStateException("AppSearch is not supported")
        val status = errorHandler.handleInitializationError(exception)
        // String parsing is removed, so it should be UNKNOWN now
        assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
    }

    @Test
    fun `handleInitializationError classifies generic IllegalStateException as UNKNOWN`() {
        val exception = IllegalStateException("Random error")
        val status = errorHandler.handleInitializationError(exception)
        assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
    }

    @Test
    fun `handleInitializationError classifies DeadObjectException as NOT_SUPPORTED with SERVICE_UNAVAILABLE`() {
        val exception = DeadObjectException()
        val status = errorHandler.handleInitializationError(exception)
        assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
        assertEquals(TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE, status.code)
    }

    @Test
    fun `handleInitializationError classifies SecurityException as PERMISSION_MISSING`() {
        val exception = SecurityException()
        val status = errorHandler.handleInitializationError(exception)
        assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (status as TrackingStatus.Error).type)
    }

    @Test
    fun `handleInitializationError classifies AppSearchException with INTERNAL_ERROR as NOT_SUPPORTED`() {
        val exception = AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR)
        val status = errorHandler.handleInitializationError(exception)
        assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
        assertEquals(TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE, status.code)
    }

    @Test
    fun `handleInitializationError classifies generic Exception as UNKNOWN`() {
        val exception = RuntimeException("Unknown error")
        val status = errorHandler.handleInitializationError(exception)
        assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
    }
}
