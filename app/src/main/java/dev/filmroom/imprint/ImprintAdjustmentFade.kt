package dev.filmroom.imprint

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup

/** Keeps the tracked slider in place while revealing the photo beneath a modal editor. */
internal class ImprintAdjustmentFade(private val dialog: AlertDialog) {
    private data class Hidden(val view: View, val alpha: Float, val accessibility: Int)
    private val hidden = mutableListOf<Hidden>()
    private val enabled = mutableListOf<Pair<View, Boolean>>()
    private val backgrounds = mutableListOf<Pair<Drawable, Int>>()
    private var animator: ValueAnimator? = null
    private var dim = 0f
    private var fraction = 1f
    private var active: View? = null

    fun begin(slider: View) {
        if (active === slider) { animateTo(0f); return }
        finishImmediately()
        active = slider
        val window = dialog.window ?: return
        dim = window.attributes.dimAmount
        var child = slider
        fun disable(view: View) {
            enabled += view to view.isEnabled
            view.isEnabled = false
            if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
        }
        while (true) {
            val parent = child.parent as? ViewGroup ?: break
            parent.background?.mutate()?.let { backgrounds += it to it.alpha }
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChildAt(i)
                if (sibling === child) continue
                hidden += Hidden(sibling, sibling.alpha, sibling.importantForAccessibility)
                sibling.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                disable(sibling)
            }
            child = parent
        }
        animateTo(0f)
    }

    fun end() { if (active != null) animateTo(1f) }

    private fun setFraction(value: Float) {
        fraction = value
        hidden.forEach { it.view.alpha = it.alpha * value }
        backgrounds.forEach { (drawable, alpha) -> drawable.alpha = (alpha * value).toInt() }
        dialog.window?.let { window -> window.attributes = window.attributes.apply { dimAmount = dim * value } }
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 160L
            addUpdateListener {
                setFraction(it.animatedValue as Float)
                if (target == 1f && it.animatedFraction == 1f) restore()
            }
            start()
        }
    }

    private fun restore() {
        hidden.forEach { it.view.alpha = it.alpha; it.view.importantForAccessibility = it.accessibility }
        enabled.forEach { (view, wasEnabled) -> view.isEnabled = wasEnabled }
        backgrounds.forEach { (drawable, alpha) -> drawable.alpha = alpha }
        if (active != null) dialog.window?.let { it.attributes = it.attributes.apply { dimAmount = dim } }
        hidden.clear(); enabled.clear(); backgrounds.clear(); active = null; fraction = 1f
    }

    fun finishImmediately() { animator?.cancel(); animator = null; restore() }
}
