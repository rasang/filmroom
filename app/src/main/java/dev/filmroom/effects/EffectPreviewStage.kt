package dev.filmroom.effects

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Fit pages during measurement, not onSizeChanged (which runs after child measurement).
 * This also handles weighted parents measuring the stage at zero height on their first pass.
 */
internal open class EffectPreviewStage(context:Context):FrameLayout(context) {
    class PageParams(val aspect:Float):LayoutParams(MATCH_PARENT,MATCH_PARENT,Gravity.CENTER) {
        init { require(aspect>0f && aspect.isFinite()) }
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            val params=child.layoutParams as? PageParams ?: continue
            if(child.visibility==View.GONE)continue
            val availableWidth=(measuredWidth-paddingLeft-paddingRight).coerceAtLeast(0)
            val availableHeight=(measuredHeight-paddingTop-paddingBottom).coerceAtLeast(0)
            val width=minOf(availableWidth.toFloat(),availableHeight*params.aspect)
            child.measure(MeasureSpec.makeMeasureSpec(width.roundToInt(),MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((width/params.aspect).roundToInt(),MeasureSpec.EXACTLY))
        }
    }
}
