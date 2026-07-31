package com.example.minutewatcher.service.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.example.minutewatcher.core.android.util.SimUtils
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.MainImmediateDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.semantic.SemanticRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class UsageTileService : TileService() {
    @Inject lateinit var semanticRepository: SemanticRepository

    @Inject lateinit var usageStateRepository: UsageStateRepository

    @Inject lateinit var navigationProvider: NavigationProvider

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    @MainImmediateDispatcher
    lateinit var mainImmediateDispatcher: CoroutineDispatcher

    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()

        if (semanticRepository.snapshot.value.timestamp == 0L) {
            applicationScope.launch {
                try {
                    usageStateRepository.refresh()
                } catch (e: Exception) {
                    AppLogger.e("Failed to force refresh snapshot for tile", e)
                }
            }
        }

        job?.cancel()
        job =
            applicationScope.launch(mainImmediateDispatcher) {
                semanticRepository.snapshot
                    .collect {
                        try {
                            updateTile()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
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

    // WrongConstant is suppressed because FLAG_ACTIVITY_NEW_TASK is required for starting activity from a service.
    @SuppressLint("WrongConstant")
    override fun onClick() {
        super.onClick()
        val intent =
            Intent().setClassName(packageName, navigationProvider.mainActivityClassName).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        val wrapper =
            PendingIntentActivityWrapper(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                false,
            )
        TileServiceCompat.startActivityAndCollapse(this, wrapper)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val snapshot = semanticRepository.snapshot.value

        // Filter active SIMs and pick the one with most critical status or just slot 0
        val activeSims = snapshot.simSnapshots.filter { it.isActive }
        if (activeSims.isEmpty()) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.no_sim_found)
            tile.updateTile()
            return
        }

        // Simple policy: show the first active SIM for now, or the one with lowest minutes
        val activeSim =
            activeSims.minByOrNull {
                it.entities[PackageType.MINUTES]?.remaining ?: Long.MAX_VALUE
            } ?: activeSims[0]

        val operatorName = SimUtils.getShortOperatorName(this, activeSim.operator)
        val minutes = activeSim.entities[PackageType.MINUTES]

        tile.label = operatorName
        if (minutes != null) {
            val remaining = minutes.remaining.toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle =
                    resources.getQuantityString(
                        R.plurals.minutes_plurals,
                        remaining,
                        remaining,
                    )
            } else {
                // For older versions, put info in label or content description
                tile.label = getString(R.string.tile_operator_value_format, operatorName, remaining.toString())
            }
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.no_data)
            }
        }

        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }
}
