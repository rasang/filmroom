package dev.filmroom.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Single outer silhouette around the combined 135/120 artwork, matching the existing 240-square film-roll artwork.
 * This is an explicit no-stock selection, never a fallback for a missing asset. */
class NoFilmArtwork : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(166, 168, 164)
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    // One closed contour, with no separate roll outlines or interior ellipses.
    private val outline = Path().apply {
        moveTo(137f, 50f)
        cubicTo(137f, 36f, 196f, 36f, 196f, 50f)
        lineTo(196f, 187f)
        cubicTo(196f, 201f, 157f, 203f, 137f, 195f)
        lineTo(107f, 199f)
        cubicTo(98f, 209f, 49f, 209f, 49f, 195f)
        lineTo(49f, 105f)
        cubicTo(49f, 99f, 58f, 95f, 70f, 94f)
        lineTo(70f, 90f)
        cubicTo(70f, 86f, 85f, 86f, 85f, 90f)
        lineTo(85f, 94f)
        cubicTo(94f, 94f, 101f, 96f, 107f, 99f)
        lineTo(137f, 90f)
        close()
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 240f, bounds.height() / 240f)
        canvas.drawPath(outline, stroke)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { stroke.colorFilter = filter; invalidateSelf() }
    @Deprecated("Required Drawable compatibility override")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 240
    override fun getIntrinsicHeight() = 240
}
