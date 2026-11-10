package dev.filmroom.effects

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout

/** exp6 uses one full-size Surface, with a centered GL viewport for the effect scene. */
internal object HomeMotionLayout {
    const val COVER_TIME_MS = 2900L
    fun fit(width: Int, height: Int, kind: EffectKind, resultWidth: Int, resultHeight: Int): EffectExportSize {
        require(resultWidth > 0 && resultHeight > 0)
        val aspect = EffectGeometry.sceneAspect(kind, resultWidth.toFloat() / resultHeight)
        val fittedWidth = minOf(width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1) * aspect)
        return EffectExportSize(fittedWidth.toInt().coerceAtLeast(1), (fittedWidth / aspect).toInt().coerceAtLeast(1))
    }
}
internal class HomeMotionStage(context: Context, private val kind: EffectKind,
    private val resultWidth: Int, private val resultHeight: Int, private val player: View) : FrameLayout(context) {
    init { setBackgroundColor(Color.rgb(24,24,24)); addView(player, LayoutParams(1,1)); isClickable = false }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec); val height = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(width,height)
        player.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = (width-player.measuredWidth)/2; val y = (height-player.measuredHeight)/2
        player.layout(x,y,x+player.measuredWidth,y+player.measuredHeight)
    }
}
