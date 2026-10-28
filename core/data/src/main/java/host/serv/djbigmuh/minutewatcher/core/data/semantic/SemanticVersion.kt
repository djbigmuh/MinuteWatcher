package host.serv.djbigmuh.minutewatcher.core.data.semantic

import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticVersion
    @Inject
    constructor() {
        fun getVersion(): SchemaVersion = SchemaVersion(1, 0, 0)
    }
