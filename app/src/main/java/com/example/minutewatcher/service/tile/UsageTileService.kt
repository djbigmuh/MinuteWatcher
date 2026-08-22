package com.example.minutewatcher.service.tile

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
                } catch (e: CancellationException) {
                    throw e
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

    override fun onClick() {
        super.onClick()
        val intent =
            Intent().setClassName(packageName, navigationProvider.mainActivityClassName).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        // Strict TileService/PendingIntent API: only documented constants are valid.
        // Passing undocumented values causes SystemUI crashes on some OEM ROMs.
        // See: https://developer.android.com/reference/android/service/quicksettings/Tile#setState(int)
        // See: https://developer.android.com/reference/android/app/PendingIntent#flags
        // Do NOT use @SuppressLint("WrongConstant") — fix the constant instead.
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

        // Filter active SIMs and pick the one with most critical status or just slot 0
        val activeSims = snapshot.simSnapshots.filter { it.isActive }
        if (activeSims.isEmpty()) {
            tile.applyState(TileState.UNAVAILABLE)
            tile.label = getString(R.string.no_sim_found)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.no_sim_found)
            }
            tile.updateTile()
            return
        }

        // Simple policy: show the first active SIM for now, or the one with lowest minutes
        val activeSim =
            activeSims.minByOrNull {
                it.entities[PackageType.MINUTES]?.remaining ?: Long.MAX_VALUE
            } ?: activeSims[0]

        val operatorName = SimUtils.getShortOperatorName(this, activeSim.operatorName)
        val minutes = activeSim.entities[PackageType.MINUTES]

        if (minutes != null) {
            val remaining = minutes.remaining.toInt()
            val remainingText = resources.getQuantityString(R.plurals.minutes_plurals, remaining, remaining)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.label = operatorName
                tile.subtitle = remainingText
            } else {
                // For older versions (API 26-28), subtitle is not supported.
                // We use a compact "Name: Value" format in the label.
                tile.label = "$operatorName: $remaining"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tile.stateDescription = remainingText
            }
            tile.contentDescription = "$operatorName: $remainingText"
            tile.applyState(TileState.ACTIVE)
        } else {
            tile.label = operatorName
            tile.applyState(TileState.UNAVAILABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.no_data)
            }
            tile.contentDescription = "$operatorName: ${getString(R.string.no_data)}"
        }

        tile.updateTile()
    }

    private enum class TileState {
        ACTIVE,
        INACTIVE,
        UNAVAILABLE,
    }

    private fun Tile.applyState(state: TileState) {
        // Strict TileService/PendingIntent API: only documented constants are valid.
        // Passing undocumented values causes SystemUI crashes on some OEM ROMs.
        // See: https://developer.android.com/reference/android/service/quicksettings/Tile#setState(int)
        // See: https://developer.android.com/reference/android/app/PendingIntent#flags
        // Do NOT use @SuppressLint("WrongConstant") — fix the constant instead.
        this.state =
            when (state) {
                TileState.ACTIVE -> Tile.STATE_ACTIVE
                TileState.INACTIVE -> Tile.STATE_INACTIVE
                TileState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }
}
