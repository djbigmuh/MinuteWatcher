package host.serv.djbigmuh.minutewatcher.feature.semantic.appfunctions

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

interface AppFunctionStateGateway {
    suspend fun setAppFunctionEnabled(
        functionId: String,
        enabled: Boolean,
    )
}

@Singleton
internal class PlatformAppFunctionStateGateway
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : AppFunctionStateGateway {
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun setAppFunctionEnabled(
        functionId: String,
        enabled: Boolean,
    ) {
        val manager = AppFunctionManager.getInstance(context)
        if (manager == null) {
            AppLogger.w("PlatformAppFunctionStateGateway: AppFunctionManager is null")
            return
        }
        val state =
            if (enabled) {
                AppFunctionManager.APP_FUNCTION_STATE_ENABLED
            } else {
                AppFunctionManager.APP_FUNCTION_STATE_DISABLED
            }
        manager.setAppFunctionEnabled(functionId, state)
    }
}
