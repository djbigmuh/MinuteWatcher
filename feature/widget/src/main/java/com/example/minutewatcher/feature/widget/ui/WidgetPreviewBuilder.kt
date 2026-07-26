package com.example.minutewatcher.feature.widget.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.minutewatcher.core.model.*
import com.example.minutewatcher.core.model.R

class WidgetPreviewBuilder(
    private val context: Context,
) {
    private val simViewFactory = SimViewFactory(context)

    fun buildPreview(uiState: WidgetUiState): View {
        val root = createRootContainer(uiState)

        val isHorizontal = uiState.simArrangement == 0
        val layout =
            LinearLayout(context).apply {
                orientation = if (isHorizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = (4 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
        root.addView(layout)

        val simUiStates = uiState.simUiStates
        val show1 = simUiStates.getOrNull(0)?.showSim ?: false
        val show2 = simUiStates.getOrNull(1)?.showSim ?: false

        if (show1) {
            simUiStates.getOrNull(0)?.let { layout.addView(simViewFactory.createSimView(it, uiState.layoutMode)) }
        }

        if (show1 && show2) {
            layout.addView(createDivider(context, isHorizontal))
        }

        if (show2) {
            simUiStates.getOrNull(1)?.let { layout.addView(simViewFactory.createSimView(it, uiState.layoutMode)) }
        }

        if (!show1 && !show2) {
            layout.addView(
                TextView(context).apply {
                    text = context.getString(R.string.no_data)
                    setTextColor(simUiStates.firstOrNull()?.textColor ?: Color.WHITE)
                    gravity = Gravity.CENTER
                },
            )
        }

        return root
    }

    private fun createRootContainer(state: WidgetUiState): LinearLayout =
        LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            val alpha = (255 * state.transparency / 100).coerceIn(0, 255)
            val bgColor = state.backgroundColor

            val bgDrawable = ContextCompat.getDrawable(context, R.drawable.widget_background_rounded)?.mutate()
            if (bgDrawable is GradientDrawable) {
                val backgroundColor = Color.argb(alpha, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
                bgDrawable.setColor(backgroundColor)
            }
            background = bgDrawable
        }
}
