package host.serv.djbigmuh.minutewatcher.core.data

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of the mutex for serializing call operations.
 * A single point of ownership prevents passing the mutex as a parameter
 * and guarantees consistency across all components.
 */
@Singleton
class CallMutexHolder
    @Inject
    constructor() {
        val mutex = Mutex()
    }
