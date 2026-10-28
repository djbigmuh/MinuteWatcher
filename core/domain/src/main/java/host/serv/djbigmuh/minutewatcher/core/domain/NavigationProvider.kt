package host.serv.djbigmuh.minutewatcher.core.domain

/**
 * Interface to provide navigation targets, avoiding hardcoded class names.
 *
 * Safe to access from any thread.
 */
interface NavigationProvider {
    /**
     * The full class name of the MainActivity.
     */
    val mainActivityClassName: String
}
