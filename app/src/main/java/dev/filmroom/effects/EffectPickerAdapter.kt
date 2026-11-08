package dev.filmroom.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import dev.filmroom.ui.FilmroomPalette

/** Two decorative, equal-weight glyphs. Text remains the accessible option label. */
internal class EffectPickerAdapter(private val context: Context, private val names: Array<String>) : BaseAdapter() {
    override fun getCount() = names.size
    override fun getItem(position: Int) = names[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        (convertView as? TextView ?: TextView(context)).apply {
            text = names[position]
            textSize = 17f
            setTextColor(FilmroomPalette.ink)
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(60)
            setPaddingRelative(dp(24), dp(12), dp(24), dp(12))
            compoundDrawablePadding = dp(16)
            setCompoundDrawablesRelative(EffectPickerIcon(position == 0, FilmroomPalette.accent).apply {
                setBounds(0, 0, dp(28), dp(28))
            }, null, null, null)
        }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}

/** Original 24-unit vector artwork; no raster/remote assets or font dependence. */
internal class EffectPickerIcon(private val lightTable: Boolean, private val tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.45f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = tint
        paint.alpha = opacity
        if (lightTable) {
            // Illuminated panel with one photographic slide and its physical switch.
            canvas.drawRoundRect(RectF(2.5f, 4f, 21.5f, 20f), 2f, 2f, paint)
            canvas.drawRoundRect(RectF(6f, 7f, 18f, 15f), .75f, .75f, paint)
            canvas.drawLine(8.5f, 17.5f, 13.5f, 17.5f, paint)
            canvas.drawCircle(18f, 17.5f, .65f, paint)
        } else {
            // Four finder corners and the thin split-image focusing circle.
            val corners = Path().apply {
                moveTo(7f,4f);lineTo(3f,4f);lineTo(3f,8f)
                moveTo(17f,4f);lineTo(21f,4f);lineTo(21f,8f)
                moveTo(3f,16f);lineTo(3f,20f);lineTo(7f,20f)
                moveTo(17f,20f);lineTo(21f,20f);lineTo(21f,16f)
            }
            canvas.drawPath(corners, paint)
            canvas.drawCircle(12f, 12f, 4.1f, paint)
            canvas.drawLine(8f, 12f, 16f, 12f, paint)
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0,255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
