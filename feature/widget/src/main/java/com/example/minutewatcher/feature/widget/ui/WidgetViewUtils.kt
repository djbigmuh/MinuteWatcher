package com.example.minutewatcher.feature.widget.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.SimUiState

fun createIcon(
    context: Context,
    simState: SimUiState,
): ImageView {
    val sizeDp = 16
    return ImageView(context).apply {
        setImageResource(simState.operatorIconRes)
        val size = (sizeDp * context.resources.displayMetrics.density).toInt()
        layoutParams =
            LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
    }
}

fun createName(
    context: Context,
    simState: SimUiState,
    color: Int,
    sizeSp: Float,
): TextView =
    TextView(context).apply {
        text = simState.operatorName
        setTextColor(color)
        textSize = sizeSp
        gravity = Gravity.CENTER
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
    }

fun createValueText(
    context: Context,
    text: String,
    color: Int,
    sizeSp: Float,
    isBold: Boolean,
): TextView =
    TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = sizeSp
        if (isBold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        includeFontPadding = false
    }

fun createVerticalStack(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = Gravity.CENTER
    }

fun createDivider(
    context: Context,
    isHorizontal: Boolean,
): View =
    if (isHorizontal) {
        TextView(context).apply {
            text = context.getString(R.string.divider_text)
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 0)
        }
    } else {
        View(context).apply {
            val height = (1 * context.resources.displayMetrics.density).toInt()
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
                    setMargins(context, 0, 4, 0, 4)
                }
            setBackgroundColor(Color.GRAY)
        }
    }

fun View.setMargins(
    context: Context,
    start: Int,
    top: Int,
    end: Int,
    bottom: Int,
) {
    val d = context.resources.displayMetrics.density
    val lp =
        layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.marginStart = (start * d).toInt()
    lp.topMargin = (top * d).toInt()
    lp.marginEnd = (end * d).toInt()
    lp.bottomMargin = (bottom * d).toInt()
    layoutParams = lp
}
