package host.serv.djbigmuh.minutewatcher.feature.semantic.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "MinuteWatcherAppFunctionService",
    appFunctionXmlFileName = "minute_watcher_app_function_service",
)
abstract class BaseMinuteWatcherAppFunctionService : AppFunctionService() {
    @Inject
    internal lateinit var usageSummaryProvider: UsageSummaryProvider

    /**
     * Returns the remaining call minutes and remaining mobile data for each active SIM card,
     * together with the configured monthly limits and the end of the current billing period.
     * Use this to answer questions like "How many minutes do I have left?" or
     * "How much mobile data is remaining?".
     *
     * @return Summary of remaining minutes and mobile data per SIM card.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getUsageSummary(): UsageSummary = usageSummaryProvider.getUsageSummary()
}
