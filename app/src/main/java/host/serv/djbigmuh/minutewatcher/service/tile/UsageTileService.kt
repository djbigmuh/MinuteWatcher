package host.serv.djbigmuh.minutewatcher.service.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import host.serv.djbigmuh.minutewatcher.core.android.util.AppLocaleContexts
import host.serv.djbigmuh.minutewatcher.core.android.util.SimUtils
import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.di.MainImmediateDispatcher
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.domain.NavigationProvider
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.resources.R
import host.serv.djbigmuh.minutewatcher.feature.semantic.SemanticRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Service for the Quick Settings tile showing SIM usage information.
 *
 * **Contract:**
 * - Strict TileService/PendingIntent API: only documented constants are valid.
 *   See: https://developer.android.com/reference/android/service/quicksettings/Tile#setState(int)
 *   See: https://developer.android.com/reference/android/app/PendingIntent#flags
 *   Do NOT use @SuppressLint("WrongConstant") — fix the constant instead.
 */
@AndroidEntryPoint
class UsageTileService : TileService() {
    @Inject
    lateinit var semanticRepository: SemanticRepository

    @Inject
    lateinit var usageStateRepository: UsageStateRepository

    @Inject
    lateinit var navigationProvider: NavigationProvider

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    @MainImmediateDispatcher
    lateinit var mainImmediateDispatcher: CoroutineDispatcher

    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()

        if (semanticRepository.snapshot.value.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
            applicationScope.launch {
                @Suppress("TooGenericExceptionCaught")
                try {
                    usageStateRepository.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for tile service reliability.
                    AppLogger.e("Failed to force refresh snapshot for tile", e)
                }
            }
        }

        job?.cancel()
        job =
            applicationScope.launch(mainImmediateDispatcher) {
                semanticRepository.snapshot
                    .collectLatest {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            updateTile()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Operation must complete without propagating exception for tile service reliability.
                            AppLogger.e("Failed to update quick settings tile", e)
                        }
                    }
            }
    }

    override fun onStopListening() {
        super.onStopListening()
        job?.cancel()
        job = null
    }

    override fun onClick() {
        super.onClick()
        val intent =
            Intent().setClassName(packageName, navigationProvider.mainActivityClassName).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val wrapper =
            PendingIntentActivityWrapper(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )
        TileServiceCompat.startActivityAndCollapse(this, wrapper)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val snapshot = semanticRepository.snapshot.value
        val appLocaleContext =
            AppLocaleContexts
                .forAppLocale(this)

        val content =
            TileContentResolver.resolve(
                sims = snapshot.simSnapshots,
                noSimText = appLocaleContext.getString(R.string.no_sim_found),
                noDataText = appLocaleContext.getString(R.string.no_data),
                supportsSubtitle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                operatorNameOf = { sim ->
                    SimUtils.getShortOperatorName(appLocaleContext, sim.operatorName, sim.slot)
                },
                remainingTextOf = { sim ->
                    val remaining = (sim.entities[PackageType.MINUTES]?.remaining ?: 0L).toInt()
                    appLocaleContext.resources.getQuantityString(R.plurals.minutes_plurals, remaining, remaining)
                },
                formatOperatorValue = { operator, value ->
                    appLocaleContext.getString(R.string.tile_operator_value_format, operator, value)
                },
            )

        tile.label = content.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = content.subtitle
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = content.stateDescription
        }
        tile.contentDescription = content.contentDescription
        tile.state = tileStateConstant(content.isActive)
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }
}

internal fun tileStateConstant(isActive: Boolean): Int = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
