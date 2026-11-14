package dev.filmroom.effects

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

internal class HomeMotionGestures(context: Context, private val viewport: HomeMotionViewport,
    private val changed: (Float, Boolean) -> Unit) {
    private var multiple = false
    private val scale = ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewport.zoomBy(detector.scaleFactor,detector.focusX,detector.focusY)
            changed(viewport.scale(),true); return true
        }
    }).apply { isQuickScaleEnabled=false; isStylusScaleEnabled=false }
    private val gestures = GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            viewport.doubleTap(e.x,e.y); changed(viewport.scale(),false); return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!multiple && !scale.isInProgress) {
                viewport.pan(-dx,-dy); changed(viewport.scale(),true)
            }
            return true
        }
    }).apply { setIsLongpressEnabled(false) }
    fun touch(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) multiple=false
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) { multiple=true; changed(viewport.scale(),true) }
        scale.onTouchEvent(event); gestures.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
            multiple=false; changed(viewport.scale(),false)
        }
        return true
    }
    fun cancel() {
        val now=android.os.SystemClock.uptimeMillis()
        val event=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0f,0f,0)
        try { touch(event) } finally { event.recycle() }
    }
    fun reset() { cancel(); viewport.reset(); multiple=false; changed(viewport.scale(),false) }
}
