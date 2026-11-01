package dev.filmroom.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** A fixed camera selection window. Content moves; only a completed snap changes the model. */
class CenteredChoiceRail(context: Context) : HorizontalScrollView(context) {
    val items = LinearLayout(context)
    var selectedIndex = 0; private set
    var itemWidth = 1; private set
    private var touching = false
    private var ignoreTouchUntilDown = false
    private var userScroll = false
    private var snapping: ValueAnimator? = null
    private var listener: ((Int) -> Unit)? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229,163,93); style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var fade: LinearGradient? = null
    private val window = RectF()
    private val projection = OverScroller(context)
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(items, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun configure(width: Int, index: Int, onSelected: (Int) -> Unit) {
        cancelPending()
        itemWidth = width.coerceAtLeast(1)
        selectedIndex = index.coerceIn(0, (items.childCount-1).coerceAtLeast(0))
        listener = onSelected
        super.fling(0); super.computeScroll(); scrollTo(selectedIndex*itemWidth,0)
        requestLayout()
        invalidate()
    }

    fun select(index: Int) {
        if (!isEnabled) return
        if (touching) {
            // Keyboard/accessibility selection takes ownership from an active finger.
            val time=android.os.SystemClock.uptimeMillis()
            MotionEvent.obtain(time,time,MotionEvent.ACTION_CANCEL,0f,0f,0).also{
                super.dispatchTouchEvent(it);it.recycle()
            }
            touching=false
            ignoreTouchUntilDown=true
        }
        snapTo(index)
    }

    private fun stopAnimation() {
        val old = snapping
        snapping = null
        old?.removeAllListeners()
        old?.cancel()
    }
    private fun cancelPending() {
        stopAnimation()
        touching = false
        ignoreTouchUntilDown = false
        userScroll = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN)ignoreTouchUntilDown=false
        else if(ignoreTouchUntilDown)return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stopAnimation(); touching = true; userScroll = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        // Native touch handling decides whether UP is a fling. Our override below
        // owns that motion, so no native inertia/debounce/second animation chain exists.
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> if (userScroll && snapping == null)
                snapTo((scrollX.toFloat()/itemWidth).roundToInt())
            MotionEvent.ACTION_CANCEL -> { userScroll = false; snapTo(selectedIndex, false) }
        }
        return handled
    }

    override fun fling(velocityX: Int) {
        if (velocityX == 0) { super.fling(0); return }
        if (!isEnabled || items.childCount == 0) return
        // Android's density/friction-aware prediction, bounded by the real option range.
        // Quantize the destination before starting: one continuous release-to-center motion.
        projection.forceFinished(true)
        projection.fling(scrollX,0,velocityX.coerceIn(-maximumVelocity,maximumVelocity),0,
            0,(items.childCount-1)*itemWidth,0,0)
        val target=(projection.finalX.toFloat()/itemWidth).roundToInt()
        // OverScroller exposes the projected position, not its duration in the public API.
        // For our cubic ease-out, initial speed = 3 * distance / duration. Match release
        // velocity rather than adding a second, fixed-length animation after inertia.
        val speed=abs(velocityX.coerceIn(-maximumVelocity,maximumVelocity)).coerceAtLeast(1)
        val motionDuration=(3000.0*abs(target*itemWidth-scrollX)/speed).toLong().coerceIn(160L,1000L)
        projection.forceFinished(true)
        snapTo(target, durationOverride=motionDuration)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l,t,oldl,oldt)
        invalidate()
    }

    private fun snapTo(index: Int, commit: Boolean = true, durationOverride:Long?=null) {
        if (items.childCount == 0) return
        stopAnimation(); userScroll = false
        val target = index.coerceIn(0,items.childCount-1)
        val start = scrollX
        // Stop native inertia before the explicit snap animation owns the scroll position.
        super.fling(0); super.computeScroll(); scrollTo(start,0)
        val end = target*itemWidth
        fun finish() {
            scrollTo(end,0)
            if (commit && isEnabled && target != selectedIndex) {
                selectedIndex = target
                listener?.invoke(target)
            }
        }
        if (start == end || width == 0 || !ValueAnimator.areAnimatorsEnabled()) { finish(); return }
        snapping = ValueAnimator.ofInt(start,end).apply {
            duration = durationOverride ?: (160L + (abs(end-start).toFloat()/itemWidth*70).roundToInt()).coerceAtMost(300L)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { scrollTo(it.animatedValue as Int,0) }
            addListener(object: AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (snapping !== animation) return
                    snapping = null
                    finish()
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewport = MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight
        val inset = ((viewport-itemWidth)/2).coerceAtLeast(0)
        // An odd remaining pixel belongs at the end, keeping the final item centerable too.
        items.setPadding(inset,0,(viewport-itemWidth-inset).coerceAtLeast(0),0)
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    override fun onLayout(changed: Boolean,l:Int,t:Int,r:Int,b:Int) {
        super.onLayout(changed,l,t,r,b)
        if (!touching && !userScroll && snapping == null) scrollTo(selectedIndex*itemWidth,0)
        window.set((width-itemWidth)/2f+dp(2f),dp(2f),(width+itemWidth)/2f-dp(2f),height-dp(2f))
        val fraction = (minOf(dp(32f), ((width-itemWidth)/2f-dp(4f)).coerceAtLeast(0f))/width.coerceAtLeast(1)).coerceIn(0f,.25f)
        fade = LinearGradient(0f,0f,width.toFloat(),0f,
            intArrayOf(Color.TRANSPARENT,Color.BLACK,Color.BLACK,Color.TRANSPARENT),
            floatArrayOf(0f,fraction,1f-fraction,1f),Shader.TileMode.CLAMP)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Android parent drawing translates a scrolled child canvas by -scrollX.
        // Cancel that translation only for the mask/window, not for the moving content.
        val save = canvas.saveLayer(scrollX.toFloat(),0f,(scrollX+width).toFloat(),height.toFloat(),null)
        super.dispatchDraw(canvas)
        canvas.save(); canvas.translate(scrollX.toFloat(),0f)
        mask.shader = fade
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),mask)
        canvas.restore(); canvas.restoreToCount(save)
        canvas.save(); canvas.translate(scrollX.toFloat(),0f)
        ink.alpha = if (isEnabled) 255 else 90
        canvas.drawRoundRect(window,dp(12f),dp(12f),ink)
        canvas.restore()
    }
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) { cancelPending(); super.fling(0); super.computeScroll(); scrollTo(selectedIndex*itemWidth,0) }
        invalidate()
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) when(action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { select(selectedIndex+1); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { select(selectedIndex-1); return true }
        }
        return super.performAccessibilityAction(action,arguments)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(isEnabled) when(keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { select(selectedIndex+1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { select(selectedIndex-1); return true }
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onDetachedFromWindow() { cancelPending(); super.onDetachedFromWindow() }
    private fun dp(value:Float) = value*resources.displayMetrics.density
}
