package host.serv.djbigmuh.minutewatcher.core.model

/**
 * Represents a stable identity for a SIM card.
 *
 * @property stableId Unique stable identifier string for the SIM.
 * @property subscriptionId Subscription ID, or null if unavailable.
 * @property slotIndex SIM slot index, or null if unavailable.
 * @property source Source method used to determine the identity.
 * @property isStable Whether the identity is considered stable across reboots/slots.
 */
data class SimIdentity(
    val stableId: String,
    val subscriptionId: Int?,
    val slotIndex: Int?,
    val source: IdentitySource,
    val isStable: Boolean,
)
