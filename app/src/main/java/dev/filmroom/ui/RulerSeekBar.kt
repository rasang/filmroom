package dev.filmroom.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.widget.SeekBar
import kotlin.math.roundToInt

/**
 * A camera-style graduated track with the framework thumb and unchanged SeekBar input.
 * Ticks are visual divisions of the configured range, not labels or invented parameter values.
 */
class RulerSeekBar(context: Context) : SeekBar(context) {
    private val density = resources.displayMetrics.density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 221, 216)
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    init {
        minimumHeight = (48f * density).roundToInt()
        setPadding((16f * density).roundToInt(), paddingTop,
            (16f * density).roundToInt(), paddingBottom)
        // Keep the real thumb, touch/keyboard handling and range accessibility intact.
        // Removing only the stock track leaves our drawn ticks unobstructed.
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        tickMark = null
        thumbTintList = ColorStateList.valueOf(Color.rgb(229, 163, 93))
    }

    override fun onDraw(canvas: Canvas) {
        val span = (width - paddingLeft - paddingRight).toFloat()
        if (span > 0f) {
            val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f
            for (i in 0..20) {
                val x = paddingLeft + span * i / 20f
                val length = when {
                    i == 10 -> 24f
                    i % 5 == 0 -> 18f
                    else -> 11f
                } * density
                // Quiet endpoints preserve a ruler feel, without implying completed progress.
                val alpha = when (i) { 0, 20 -> 75; 1, 19 -> 105; else -> 175 }
                tickPaint.alpha = if (isEnabled) alpha else alpha / 2
                canvas.drawLine(x, centerY - length / 2f, x, centerY + length / 2f, tickPaint)
            }
        }
        // The live value thumb is always drawn above the ruler marks.
        super.onDraw(canvas)
    }
}
