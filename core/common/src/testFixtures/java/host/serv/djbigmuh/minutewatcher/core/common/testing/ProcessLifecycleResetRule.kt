package host.serv.djbigmuh.minutewatcher.core.common.testing

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * A JUnit [TestWatcher] that manages ProcessLifecycleOwner state for tests.
 */
class ProcessLifecycleResetRule : TestWatcher() {
    override fun starting(description: Description) {
        runCatching<Unit> {
            val registry = ProcessLifecycleOwner.get().lifecycle as? LifecycleRegistry
            registry?.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry?.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry?.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    override fun finished(description: Description) {
        runCatching<Unit> {
            val registry = ProcessLifecycleOwner.get().lifecycle as? LifecycleRegistry
            registry?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
