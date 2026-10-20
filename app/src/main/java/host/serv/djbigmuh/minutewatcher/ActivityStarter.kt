package host.serv.djbigmuh.minutewatcher

import android.content.Context
import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton

interface ActivityStarter {
    fun startActivity(
        context: Context,
        intent: Intent,
    )
}

@Singleton
class ContextActivityStarter
    @Inject
    constructor() : ActivityStarter {
        override fun startActivity(
            context: Context,
            intent: Intent,
        ) {
            context.startActivity(intent)
        }
    }
