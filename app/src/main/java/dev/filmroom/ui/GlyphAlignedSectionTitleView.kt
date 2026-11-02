package dev.filmroom.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.TextView

/** A heading icon belongs on the first text line, aligned to visible ink, not font padding.
 * The transparent compound drawable reserves normal TextView measurement/RTL spacing; the
 * real vector is drawn against the actual first-line baseline (also correct when wrapped).
 */
class GlyphAlignedSectionTitleView(context: Context) : TextView(context) {
    private var headingIcon: Drawable? = null
    private var iconSize = 0
    private var iconInkCenterY = 0f
    private val glyphInk = Rect()

    fun setHeadingIcon(drawable: Drawable, size: Int, gap: Int) {
        headingIcon = drawable
        iconSize = size
        // Sample vector ink once at high resolution; asymmetric glyph assets must not be
        // aligned by their nominal viewport center. No bitmap is retained or drawn in UI.
        val edge = maxOf(64, size * 4)
        val sample = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, edge, edge)
            drawable.draw(Canvas(sample))
            val pixels = IntArray(edge * edge)
            sample.getPixels(pixels, 0, edge, 0, 0, edge, edge)
            var first = edge
            var last = -1
            for (y in 0 until edge) for (x in 0 until edge) {
                if (Color.alpha(pixels[y * edge + x]) >= 16) {
                    first = minOf(first, y)
                    last = maxOf(last, y)
                }
            }
            iconInkCenterY = if (last >= first) (first + last + 1) * .5f * size / edge else size / 2f
        } finally { sample.recycle() }
        drawable.setBounds(0, 0, size, size)
        val spacer = ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, size, size) }
        setCompoundDrawablesRelative(spacer, null, null, null)
        compoundDrawablePadding = gap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = headingIcon ?: return
        val textLayout = layout ?: return
        if (textLayout.lineCount == 0) return
        val label = text.toString()
        val start = textLayout.getLineStart(0).coerceAtMost(label.length)
        val end = textLayout.getLineVisibleEnd(0).coerceAtMost(label.length)
        paint.getTextBounds(label, start, end, glyphInk)
        val textInkCenter = if (glyphInk.isEmpty) (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
            else (glyphInk.top + glyphInk.bottom) / 2f
        val top = baseline + textInkCenter - iconInkCenterY
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - iconSize else paddingLeft
        val saved = canvas.save()
        canvas.translate(left.toFloat(), top)
        drawable.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
