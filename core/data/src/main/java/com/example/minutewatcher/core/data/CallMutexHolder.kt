package com.example.minutewatcher.core.data

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Владелец мьютекса для сериализации операций звонков.
 * Единая точка владения предотвращает передачу мьютекса как параметра
 * и гарантирует консистентность между всеми компонентами.
 */
@Singleton
class CallMutexHolder
    @Inject
    constructor() {
        val mutex = Mutex()
    }
