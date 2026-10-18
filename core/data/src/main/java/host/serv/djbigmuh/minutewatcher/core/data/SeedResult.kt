package host.serv.djbigmuh.minutewatcher.core.data

/**
 * Result of the SIM identity seed resolution.
 */
internal sealed interface SeedResult {
    data class Real(
        val value: String,
        val isStable: Boolean = true,
    ) : SeedResult

    data class Fallback(
        val seed: String,
    ) : SeedResult
}
