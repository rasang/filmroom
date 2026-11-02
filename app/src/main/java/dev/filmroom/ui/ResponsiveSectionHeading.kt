package dev.filmroom.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Keep complete labels at any font/window size; stack rather than squeeze the title. */
class ResponsiveSectionHeading(context: Context, private val stretchLeading: Boolean = true) : LinearLayout(context) {
    init { gravity = Gravity.CENTER_VERTICAL; orientation = HORIZONTAL }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childCount == 2 && View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
            orientation = HORIZONTAL
            for (index in 0 until childCount) getChildAt(index).layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        } else if (childCount == 2) {
            val available = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val natural = (0 until childCount).sumOf { index ->
                getChildAt(index).also {
                    it.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                }.measuredWidth
            }
            val stacked = natural > available
            orientation = if (stacked) VERTICAL else HORIZONTAL
            getChildAt(0).layoutParams = LayoutParams(
                if (!stretchLeading) LayoutParams.WRAP_CONTENT else if (stacked) LayoutParams.MATCH_PARENT else 0,
                LayoutParams.WRAP_CONTENT, if (stacked || !stretchLeading) 0f else 1f).apply {
                if (!stretchLeading) gravity = Gravity.END
            }
            getChildAt(1).layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
