package dev.filmroom.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import dev.filmroom.ui.FilmroomPalette

/** Two decorative, equal-weight glyphs. Text remains the accessible option label. */
internal class EffectPickerAdapter(
    private val context: Context,
    private val names: Array<String>,
    private val kinds: List<EffectKind?> = listOf(EffectKind.LIGHT_TABLE, EffectKind.OPTICAL_LEAK),
    private val selectedIndex: Int? = null,
) : BaseAdapter() {
    init { require(names.size == kinds.size); require(selectedIndex == null || selectedIndex in names.indices) }
    override fun getCount() = names.size
    override fun getItem(position: Int) = names[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        (convertView as? TextView ?: TextView(context)).apply {
            text = names[position]
            textSize = 17f
            setTextColor(if (selectedIndex == position) FilmroomPalette.accent else FilmroomPalette.ink)
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(60)
            setPaddingRelative(dp(24), dp(12), dp(24), dp(12))
            compoundDrawablePadding = dp(16)
            setCompoundDrawablesRelative(EffectPickerIcon(kinds[position], if (selectedIndex == null || selectedIndex == position) FilmroomPalette.accent else FilmroomPalette.ink).apply {
                setBounds(0, 0, dp(28), dp(28))
            }, null, null, null)
        }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}

/** User-selected B artwork. Shared by home and history: slide perforations + split-image finder. */
internal class EffectPickerIcon(private val kind: EffectKind?, private val tint: Int) : Drawable() {
    /** Compatibility for callers which used the previous two-option picker. */
    constructor(lightTable: Boolean, tint: Int) : this(
        if (lightTable) EffectKind.LIGHT_TABLE else EffectKind.OPTICAL_LEAK, tint,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
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
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.65f
        when (kind) {
            EffectKind.LIGHT_TABLE -> {
                canvas.drawRoundRect(RectF(3f, 3.5f, 21f, 20.5f), 1.5f, 1.5f, paint)
                paint.strokeWidth = 1.35f
                canvas.drawRoundRect(RectF(7f, 6.5f, 17f, 17.5f), .5f, .5f, paint)
                paint.style = Paint.Style.FILL
                for (x in floatArrayOf(4.4f, 18.2f)) for (y in floatArrayOf(6.2f, 10.4f, 14.6f)) {
                    canvas.drawRoundRect(RectF(x, y, x + 1.4f, y + 2.2f), .25f, .25f, paint)
                }
            }
            EffectKind.OPTICAL_LEAK -> {
                canvas.drawRoundRect(RectF(2.5f, 5f, 21.5f, 19f), 3f, 3f, paint)
                paint.strokeWidth = 1.35f
                canvas.drawCircle(12f, 12f, 4.4f, paint)
                paint.strokeWidth = 1f
                canvas.drawLine(8f, 12f, 16f, 12f, paint)
                paint.strokeWidth = 1.2f
                canvas.drawLine(11f, 8.5f, 11f, 11.7f, paint)
                canvas.drawLine(13f, 12.3f, 13f, 15.5f, paint)
            }
            null -> {
                canvas.drawCircle(12f, 12f, 8f, paint)
                canvas.drawLine(6.4f, 17.6f, 17.6f, 6.4f, paint)
            }
        }
        canvas.restoreToCount(save)
    }
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0,255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
