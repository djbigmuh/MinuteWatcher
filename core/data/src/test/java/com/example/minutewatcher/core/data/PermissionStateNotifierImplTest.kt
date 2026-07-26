package com.example.minutewatcher.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionStateNotifierImplTest {

    @Test
    fun `initial value is emitted`() = runTest {
        val notifier = PermissionStateNotifierImpl()
        val firstValue = notifier.changes.first()
        assertEquals(Unit, firstValue)
    }

    @Test
    fun `notifyChanged emits new value`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        val notifier = PermissionStateNotifierImpl()
        val results = mutableListOf<Unit>()
        
        val job = launch(testDispatcher) {
            notifier.changes.take(2).toList(results)
        }
        
        notifier.notifyChanged()
        
        assertEquals(2, results.size)
        job.cancel()
    }
}
