package dev.filmroom.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** Decorative foreground: stays inside the button and never adds a separate touch target. */
class BetaBadgeDrawable(private val density: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FilmroomPalette.accent }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FilmroomPalette.onAccent
        textSize = 8f * density
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    internal fun badgeBounds(): RectF = RectF(
        (bounds.right - 26f * density).coerceAtLeast(bounds.left.toFloat()),
        bounds.top.toFloat(),
        bounds.right.toFloat(),
        (bounds.top + 12f * density).coerceAtMost(bounds.bottom.toFloat())
    )

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val badge = badgeBounds()
        canvas.drawRoundRect(badge, 4f * density, 4f * density, fill)
        val metrics = text.fontMetrics
        canvas.drawText("Beta", badge.centerX(), badge.centerY() - (metrics.ascent + metrics.descent) / 2f, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; text.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter = filter; text.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
