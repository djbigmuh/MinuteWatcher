package host.serv.djbigmuh.minutewatcher.feature.semantic

import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticRepository
@Inject
constructor(
    usageStateRepository: UsageStateRepository,
) {
    val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()
}
