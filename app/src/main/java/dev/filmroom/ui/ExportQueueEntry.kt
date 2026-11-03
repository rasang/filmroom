package dev.filmroom.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState

/** Progress changes are deliberately excluded: the editor only needs membership and running state. */
internal data class ExportQueueSummary(val pending: Int, val running: Boolean, val hasUnreadCompleted: Boolean = false) {
    companion object {
        fun from(jobs: List<ExportJob>) = ExportQueueSummary(
            jobs.count { it.state == ExportJobState.PENDING || it.state == ExportJobState.RETRY || it.state == ExportJobState.RUNNING },
            jobs.any { it.state == ExportJobState.RUNNING },
            jobs.any { it.state == ExportJobState.COMPLETED && !it.completionViewed }
        )
    }
}

/** A single accessible target; the fixed-width count never relays progress into editor layout. */
internal class ExportQueueEntry(context: Context) : Button(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val accent = FilmroomPalette.accent
    private var countLabel = "—"
    private var motionPhase = 0f
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white=FilmroomPalette.ink
    private val countTypeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    private val rearPath=Path().apply {
        moveTo(16f,12f);lineTo(33f,12f)
        quadTo(36f,12f,36f,15f);lineTo(36f,29f)
    }
    private data class RailSegment(val x0:Float,val y0:Float,val x1:Float,val y1:Float,val position:Float)
    // Cache subpixel path samples once. Drawing the gradient allocates no Paths,
    // PathMeasures, shaders, coordinate arrays, or Typefaces on animation frames.
    private val rearSegments:List<RailSegment> = PathMeasure(rearPath,false).let { measure ->
        val n=kotlin.math.ceil(measure.length/.25f).toInt()
        val start=FloatArray(2);val end=FloatArray(2)
        List(n) { i ->
            measure.getPosTan(measure.length*i/n,start,null)
            measure.getPosTan(measure.length*(i+1)/n,end,null)
            RailSegment(start[0],start[1],end[0],end[1],(i+.5f)/n)
        }
    }
    private var summary: ExportQueueSummary? = null
    private var foreground = false
    private var animator: ValueAnimator? = null
    internal val animating get() = animator?.isStarted == true

    init {
        text = ""
        textSize = 14f
        isAllCaps = false
        setTextColor(FilmroomPalette.ink)
        minHeight = dp(48); minimumHeight = dp(48)
        minWidth = dp(48); minimumWidth = dp(48)
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply { setColor(FilmroomPalette.raised); cornerRadius = dp(12).toFloat() }
        tooltipText = dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_88b41d5ab211)
        bind(null)
    }

    fun bind(value: ExportQueueSummary?) {
        if (summary == value && contentDescription != null) return
        summary = value
        countLabel = value?.pending?.let { if (it > 99) "99+" else "$it" } ?: "—"
        contentDescription = when {
            value == null -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_945004c4e7d2)
            value.running -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_52e3c85e1339, value.pending)
            value.pending > 0 -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bc35e14dc39d, value.pending)
            else -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_fc6efd9ef551)
        }.let { description -> if(value?.hasUnreadCompleted==true)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9573f0c13d94, description) else description }
        invalidate()
        updateAnimation()
    }

    fun setForeground(value: Boolean) { foreground = value; updateAnimation() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { stopAnimation(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateAnimation() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateAnimation() }
    private fun updateAnimation() {
        if (foreground && isAttachedToWindow && isShown && windowVisibility == View.VISIBLE && summary?.running == true && ValueAnimator.areAnimatorsEnabled()) {
            if (animator == null) animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { motionPhase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else stopAnimation()
    }
    private fun stopAnimation() { animator?.cancel(); animator = null; motionPhase = 0f; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(48), widthMeasureSpec), resolveSize(dp(48), heightMeasureSpec))
    }

    /** One queue glyph: the front job card carries its count, a highlight travels around the rear card outline.
     * No separate badge, landscape pictogram, or spinner competes for the same 48dp target. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate((width-dp(48))/2f, (height-dp(48))/2f)
        canvas.scale(resources.displayMetrics.density, resources.displayMetrics.density)
        val p=iconPaint
        val ink=if(summary?.running==true)accent else white
        p.color=white;p.style=Paint.Style.STROKE;p.strokeWidth=1.8f
        p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        canvas.drawPath(rearPath,p)
        if(summary?.running==true) {
            // Raised-cosine spatial envelope: both head and tail dissolve into the white
            // contour. Circular distance makes the open-path wrap continuous in color.
            var index=0
            while(index<rearSegments.size) {
                val segment=rearSegments[index++]
                val distance=kotlin.math.abs(segment.position-(motionPhase%1f)).let{minOf(it,1f-it)}
                if(distance<.25f) {
                    val strength=((1.0+kotlin.math.cos(distance/.25f*Math.PI))*.5).toFloat()
                    p.color=Color.rgb(
                        (Color.red(white)+(Color.red(accent)-Color.red(white))*strength).toInt(),
                        (Color.green(white)+(Color.green(accent)-Color.green(white))*strength).toInt(),
                        (Color.blue(white)+(Color.blue(accent)-Color.blue(white))*strength).toInt())
                    canvas.drawLine(segment.x0,segment.y0,segment.x1,segment.y1,p)
                }
            }
        }
        // Front card and count stay fixed in geometry and color throughout the cycle.
        p.color=ink
        canvas.drawRoundRect(11f,17f,32f,36f,3f,3f,p)
        p.style=Paint.Style.FILL;p.textSize=if(countLabel.length>2)9f else 12f
        p.typeface=countTypeface;p.textAlign=Paint.Align.CENTER
        canvas.drawText(countLabel,21.5f,26.5f-(p.ascent()+p.descent())/2f,p)
        if(summary?.hasUnreadCompleted==true) {
            p.color=accent;p.style=Paint.Style.FILL
            canvas.drawCircle(39f,8f,2f,p)
        }
        canvas.restore()
    }
}
