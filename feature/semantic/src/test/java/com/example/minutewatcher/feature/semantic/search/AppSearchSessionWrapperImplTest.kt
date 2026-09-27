package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.SearchResult
import androidx.appsearch.app.SearchResults
import androidx.appsearch.app.SearchSpec
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppSearchSessionWrapperImplTest {
    @Test
    fun `query returns first page results and closes searchResults exactly once on success`() =
        runTest {
            withTimeout(5000.milliseconds) {
                val mockSession: AppSearchSession = mock()
                val mockSearchResults: SearchResults = mock()
                val expectedResults: List<SearchResult> = emptyList()

                whenever(mockSession.search(any(), any())).thenReturn(mockSearchResults)
                whenever(mockSearchResults.nextPageAsync).thenReturn(Futures.immediateFuture(expectedResults))

                val wrapper = AppSearchSessionWrapperImpl(mockSession)
                val results = wrapper.query("test", SearchSpec.Builder().build())

                assertEquals(expectedResults, results)
                verify(mockSearchResults, times(1)).close()
            }
        }

    @Test
    fun `query propagates exception from nextPageAsync and closes searchResults exactly once`() =
        runTest {
            withTimeout(5000.milliseconds) {
                val mockSession: AppSearchSession = mock()
                val mockSearchResults: SearchResults = mock()
                val expectedException = RuntimeException("Page fetch failure")

                whenever(mockSession.search(any(), any())).thenReturn(mockSearchResults)
                whenever(mockSearchResults.nextPageAsync).thenReturn(Futures.immediateFailedFuture(expectedException))

                val wrapper = AppSearchSessionWrapperImpl(mockSession)

                try {
                    wrapper.query("test", SearchSpec.Builder().build())
                    fail("Expected exception to be thrown")
                } catch (_: Exception) {
                    // Expect exception
                }

                verify(mockSearchResults, times(1)).close()
            }
        }

    @Test
    fun `query returns main result even when searchResults close throws exception`() =
        runTest {
            withTimeout(5000.milliseconds) {
                val mockSession: AppSearchSession = mock()
                val mockSearchResults: SearchResults = mock()
                val expectedResults: List<SearchResult> = emptyList()

                whenever(mockSession.search(any(), any())).thenReturn(mockSearchResults)
                whenever(mockSearchResults.nextPageAsync).thenReturn(Futures.immediateFuture(expectedResults))
                doThrow(RuntimeException("Error during close")).whenever(mockSearchResults).close()

                val wrapper = AppSearchSessionWrapperImpl(mockSession)
                val results = wrapper.query("test", SearchSpec.Builder().build())

                assertEquals(expectedResults, results)
                verify(mockSearchResults, times(1)).close()
            }
        }
}
