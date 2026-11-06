package dev.filmroom.imprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.Button
import dev.filmroom.R
import dev.filmroom.io.OutputColorSpace
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A style specimen, not another text label. Uses the export glyphs and linear-light compositor. */
internal class ImprintDatePreviewButton(
    context: Context,
    val displayedDate: String?,
    val dateStyle: String,
    size: Double,
    glow: Double,
    backgroundColor: Int,
    iconColor: Int,
) : Button(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val bitmap: Bitmap? = displayedDate?.let { date ->
        // Bounded to a small control-sized stamp, independent of imported photo dimensions.
        val raster = ImprintRaster.create(date, dateStyle,
            (18.0 * resources.displayMetrics.density * size).coerceIn(1.0, 64.0), glow)
        val image = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
        fun linear(value: Int): Float {
            val v = value / 255.0
            return (if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)).toFloat()
        }
        val background = floatArrayOf(linear(Color.red(backgroundColor)),
            linear(Color.green(backgroundColor)), linear(Color.blue(backgroundColor)))
        val row = FloatArray(raster.width * 3)
        val pixels = IntArray(raster.width)
        for (y in 0 until raster.height) {
            for (x in 0 until raster.width) for (c in 0..2) row[x * 3 + c] = background[c]
            raster.blendRow(y, row, 0, OutputColorSpace.SRGB)
            for (x in pixels.indices) pixels[x] = Color.rgb(OutputColorSpace.SRGB.encode8(row[x * 3]),
                OutputColorSpace.SRGB.encode8(row[x * 3 + 1]), OutputColorSpace.SRGB.encode8(row[x * 3 + 2]))
            image.setPixels(pixels, 0, raster.width, 0, y, raster.width, 1)
        }
        image
    }
    private val missingIcon = if (displayedDate == null) context.getDrawable(R.drawable.ic_imprint)?.mutate()?.apply {
        setTint(iconColor)
    } else null

    init {
        text = ""
        isAllCaps = false
        minWidth = dp(48)
        minimumWidth = dp(48)
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val image = bitmap
        if (image != null) {
            // FIT_CENTER keeps the entire date and its halo visible, including on narrow screens.
            val scale = min(1f, min(availableWidth.toFloat() / image.width, availableHeight.toFloat() / image.height))
            val w = image.width * scale
            val h = image.height * scale
            val left = paddingLeft + (availableWidth - w) / 2f
            val top = paddingTop + (availableHeight - h) / 2f
            destination.set(left, top, left + w, top + h)
            canvas.drawBitmap(image, null, destination, paint)
        } else {
            val side = min(dp(24), min(availableWidth, availableHeight))
            val left = paddingLeft + (availableWidth - side) / 2
            val top = paddingTop + (availableHeight - side) / 2
            missingIcon?.setBounds(left, top, left + side, top + side)
            missingIcon?.draw(canvas)
        }
    }
}
