package dev.filmroom.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** A camera-style choice rail. Only a settled user gesture commits; layout/restoration is silent. */
class SettledChoiceSelector(context: Context) : LinearLayout(context) {
    private val ink = Color.rgb(239, 235, 225)
    private val accent = Color.rgb(224, 178, 110)
    private val rail get() = scroll.items
    private var labels = emptyList<String>()
    private var selected = 0
    private var itemWidth = 1
    private var changed: ((Int) -> Unit)? = null
    private val scroll = CenteredChoiceRail(context)
    private val previous = arrow("‹", dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b88f02974ea0), -1)
    private val next = arrow("›", dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f8baad25a051), 1)
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        addView(previous, LayoutParams(dp(48), dp(48)))
        addView(scroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(next, LayoutParams(dp(48), dp(48)))
        // Large accessibility text keeps the whole label visible; swipe and accessibility
        // actions remain available without reserving two redundant arrow columns.
        if (resources.configuration.fontScale > 1.35f) { previous.visibility = GONE; next.visibility = GONE }
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun bind(title: String, options: List<String>, index: Int, onChanged: (Int) -> Unit) {
        require(options.isNotEmpty())
        labels = options
        selected = index.coerceIn(options.indices)
        changed = onChanged
        tag = title
        rail.removeAllViews()
        val sample = TextView(context).apply { textSize = 15f }
        itemWidth = (options.maxOf { sample.paint.measureText(it) } + dp(32)).roundToInt().coerceAtLeast(dp(88))
        options.forEachIndexed { i, label ->
            rail.addView(TextView(context).apply {
                text = label; textSize = 15f; gravity = Gravity.CENTER
                minHeight = dp(48)
                maxLines = 1; setHorizontallyScrolling(false)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                // Tapping is an alternative, never a required confirmation of a swipe.
                setOnClickListener { if (this@SettledChoiceSelector.isEnabled) commit(i) }
            }, LayoutParams(itemWidth, LayoutParams.WRAP_CONTENT))
        }
        scroll.configure(itemWidth, selected) { target ->
            selected = target
            updateAppearance()
            changed?.invoke(target)
        }
        updateAppearance()
        scroll.requestLayout()
    }
    private fun arrow(glyph: String, description: String, delta: Int) = TextView(context).apply {
        text = glyph; textSize = 28f; gravity = Gravity.CENTER; setTextColor(ink)
        contentDescription = description; isFocusable = true
        setOnClickListener { if (this@SettledChoiceSelector.isEnabled) commit(selected + delta) }
    }
    private fun commit(index: Int) {
        if (isEnabled && labels.isNotEmpty()) scroll.select(index)
    }
    private fun updateAppearance() {
        for (i in labels.indices) (rail.getChildAt(i) as TextView).apply {
            setTextColor(if (i == selected) accent else ink)
            isSelected = i == selected
            // The outline belongs to the stationary viewport, never to a moving label.
            background = null
        }
        contentDescription = "${tag}，${labels[selected]}，${selected + 1}/${labels.size}"
        previous.isEnabled = isEnabled && selected > 0
        next.isEnabled = isEnabled && selected < labels.lastIndex
        previous.alpha = if (previous.isEnabled) 1f else .3f
        next.alpha = if (next.isEnabled) 1f else .3f
    }
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        scroll.isEnabled = enabled
        if (labels.isNotEmpty()) updateAppearance()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && labels.isNotEmpty()) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { commit(selected - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { commit(selected + 1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        if (labels.isNotEmpty()) info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, labels.lastIndex.toFloat(), selected.toFloat())
        if (isEnabled && selected > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (isEnabled && selected < labels.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled && labels.isNotEmpty()) when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { commit(selected + 1); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { commit(selected - 1); return true }
        }
        return super.performAccessibilityAction(action, arguments)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
