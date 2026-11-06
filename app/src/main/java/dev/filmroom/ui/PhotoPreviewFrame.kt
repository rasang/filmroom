package dev.filmroom.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.ceil

/** Keep the full image viewport above the unchanged bottom controls and their backgrounds. */
internal class PhotoPreviewFrame(context: Context) : FrameLayout(context) {
    private var photo: View? = null
    private var controls: List<Button> = emptyList()
    fun avoidControls(photo: View, vararg controls: Button) {
        this.photo = photo
        this.controls = controls.toList()
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val photo = photo ?: return
        val gap = ceil(4 * resources.displayMetrics.density).toInt()
        // Reserve the complete button bounds, not just their icons. Keep the band while loading or
        // comparing so transient control visibility never shifts the image or its zoom anchor.
        val clearance = controls.maxOfOrNull { button ->
            val params = button.layoutParams as LayoutParams
            params.bottomMargin + maxOf(button.measuredHeight, params.height.coerceAtLeast(0)) + gap
        } ?: 0
        val params = photo.layoutParams as LayoutParams
        if (params.bottomMargin != clearance) {
            params.bottomMargin = clearance
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
