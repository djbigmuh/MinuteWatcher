package host.serv.djbigmuh.minutewatcher.core.domain

/**
 * Coordinator for widget updates and observation.
 *
 * This interface combines [DataChangeNotifier] and [TrackingStatusProvider]
 * to provide a unified contract for managing widget synchronization and its status.
 */
interface WidgetUpdateCoordinator :
    DataChangeNotifier,
    TrackingStatusProvider
