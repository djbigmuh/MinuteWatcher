package host.serv.djbigmuh.minutewatcher.core.common.util

import android.content.Context
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Utility for initializing [AppLogger] in unit tests with [NoOpLogger].
 * Prevents log spamming and provides a consistent way to set up logging for tests.
 */
fun initTestLogger(context: Context = mock()) {
    // Return null or a mock for filesDir to avoid actual file I/O in tests
    whenever(context.filesDir).thenReturn(null)
    AppLogger.init(context, NoOpLogger())
}

/**
 * Public testFixtures utility to reset [AppLogger] across module boundaries in tests.
 */
fun resetAppLoggerForTest() {
    AppLogger.resetForTest()
}

/**
 * Public testFixtures utility to initialize [AppLogger] for tests across module boundaries.
 */
fun initAppLoggerForTest(dir: File) {
    AppLogger.initForTest(dir)
}
