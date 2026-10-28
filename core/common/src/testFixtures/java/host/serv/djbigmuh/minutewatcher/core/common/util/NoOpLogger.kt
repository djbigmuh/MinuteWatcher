package host.serv.djbigmuh.minutewatcher.core.common.util

/**
 * Logger implementation that does nothing. Used for unit tests.
 */
class NoOpLogger : Logger {
    override fun d(
        tag: String,
        message: String,
    ) {
        // No-op by design.
    }

    override fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        // No-op by design.
    }

    override fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        // No-op by design.
    }
}
