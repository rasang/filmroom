package dev.filmroom.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Wrap whole controls, never squeeze their labels into narrow weighted columns. */
class WrappingActionLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        var lineWidth = 0
        var lineHeight = 0
        var usedWidth = 0
        var usedHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            // AT_MOST permits long prose buttons to grow vertically, while short actions
            // retain their intrinsic width. Parent height must not clip their text.
            val childWidth = if (available == Int.MAX_VALUE) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                else MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
            child.measure(childWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val spacing = if (lineWidth == 0) 0 else gap
            if (lineWidth > 0 && lineWidth + spacing + child.measuredWidth > available) {
                usedWidth = max(usedWidth, lineWidth)
                usedHeight += lineHeight + gap
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += (if (lineWidth == 0) 0 else gap) + child.measuredWidth
            lineHeight = max(lineHeight, child.measuredHeight)
        }
        usedWidth = max(usedWidth, lineWidth)
        usedHeight += lineHeight
        setMeasuredDimension(resolveSize(usedWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(usedHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val spacing = if (x == 0) 0 else gap
            if (x > 0 && x + spacing + child.measuredWidth > available) {
                x = 0; y += lineHeight + gap; lineHeight = 0
            }
            if (x > 0) x += gap
            val childLeft = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                right - left - paddingRight - x - child.measuredWidth else paddingLeft + x
            child.layout(childLeft, y, childLeft + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth
            lineHeight = max(lineHeight, child.measuredHeight)
        }
    }
}
