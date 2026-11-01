package dev.filmroom.roi

import android.content.Context
import android.graphics.*
import android.view.*
import java.util.concurrent.Executor

/** UI-only test surface; not wired to MainActivity, EditorViewModel, or FilmEngine. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic-only injected test surface; no XML/default production provider.
class ProgressivePhotoView(
    context: Context,
    provider: TileProvider,
    worker: Executor? = null,
    allowApproximateTestOnly: Boolean = false
) : View(context), AutoCloseable {
    val viewport = Viewport()
    private val scheduler = if (worker == null)
        TileScheduler(provider, allowApproximateTestOnly = allowApproximateTestOnly, onChanged = { invalidate() })
    else TileScheduler(provider, allowApproximateTestOnly = allowApproximateTestOnly, worker = worker, onChanged = { invalidate() })
    private var disposed = false
    private var renderKey: RenderKey? = null
    private var underlay: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fullTarget = RectF()
    private val sampleTarget = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private val scale = ScaleGestureDetector(context, object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewport.pinch(detector.scaleFactor.toDouble(), detector.focusX.toDouble(), detector.focusY.toDouble())
            demand(); return true
        }
    })
    private val taps = GestureDetector(context, object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (viewport.zoom > 1) viewport.reset() else viewport.pinch(2.0, e.x.toDouble(), e.y.toDouble())
            demand(); return true
        }
    })
    /**
     * UI thread; underlay already decoded/rendered OFF thread, for exactly this generation.
     * Exclusive immutable ownership transfers to this view; callers MUST NOT recycle/mutate it.
     * logicalAspect comes from oriented source dimensions, NEVER the rounded preview dimensions.
     */
    fun bind(key: RenderKey, lowResolutionUnderlay: Bitmap, logicalAspect: Double) {
        check(!disposed) { "Disposed view cannot bind; construct a new owner" }
        require(!lowResolutionUnderlay.isRecycled)
        require(maxOf(lowResolutionUnderlay.width, lowResolutionUnderlay.height) <= 768) { "No full-frame high-resolution cache substitution" }
        if (renderKey?.sourceRevision != key.sourceRevision || renderKey?.orientedSourceMapping != key.orientedSourceMapping) viewport.reset()
        renderKey = key
        underlay = lowResolutionUnderlay
        viewport.sourceAspect(logicalAspect)
        demand()
    }
    private fun demand() { renderKey?.let { scheduler.update(it, viewport.visibleTiles(it)) }; invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { viewport.size(w,h); demand() }
    fun pan(dx: Double, dy: Double) { viewport.drag(dx,dy); demand() }
    fun pinch(factor: Double, x: Double, y: Double) { viewport.pinch(factor,x,y); demand() }
    fun placement(rect: PixelRect, key: RenderKey): RectF = RectF().also { placementInto(rect,key,it) }
    private fun placementInto(rect: PixelRect, key: RenderKey, out: RectF) = out.set(
        (viewport.left + rect.left.toDouble() / key.virtualFullWidth * viewport.imageWidth).toFloat(),
        (viewport.top + rect.top.toDouble() / key.virtualFullHeight * viewport.imageHeight).toFloat(),
        (viewport.left + rect.right.toDouble() / key.virtualFullWidth * viewport.imageWidth).toFloat(),
        (viewport.top + rect.bottom.toDouble() / key.virtualFullHeight * viewport.imageHeight).toFloat()
    )
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val key = renderKey ?: return
        fullTarget.set(viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left+viewport.imageWidth).toFloat(),(viewport.top+viewport.imageHeight).toFloat())
        val save = canvas.save()
        canvas.clipRect(0,0,width,height)
        canvas.clipRect(fullTarget)
        underlay?.takeUnless { it.isRecycled }?.let { canvas.drawBitmap(it,null,fullTarget,paint) }
        // One shared matrix avoids per-tile rounded scaling. Full sample bitmap is drawn;
        // only logical coverage is clipped, so bilinear reads can use the neighboring gutter.
        canvas.translate(viewport.left.toFloat(), viewport.top.toFloat())
        canvas.scale((viewport.imageWidth/key.virtualFullWidth).toFloat(), (viewport.imageHeight/key.virtualFullHeight).toFloat())
        scheduler.forEachDrawable { tile ->
            if (tile.key == key && !tile.bitmap.isRecycled) {
                val tileSave = canvas.save()
                val core = tile.coreRect
                canvas.clipRect(core.left, core.top, core.right, core.bottom)
                val sample = tile.sampleRect
                sampleTarget.set(sample.left.toFloat(),sample.top.toFloat(),sample.right.toFloat(),sample.bottom.toFloat())
                // Explicit destination disables Bitmap/Canvas density autoscaling.
                // Null source means ALL gutter pixels, never a core-only source crop.
                canvas.drawBitmap(tile.bitmap,null,sampleTarget,paint)
                canvas.restoreToCount(tileSave)
            }
        }
        canvas.restoreToCount(save)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e); taps.onTouchEvent(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX=e.x; lastY=e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = if (e.actionIndex == 0) 1 else 0
                if(index < e.pointerCount) { lastX=e.getX(index); lastY=e.getY(index) }
            }
            MotionEvent.ACTION_MOVE -> {
                if(!scale.isInProgress && e.pointerCount==1) pan((e.x-lastX).toDouble(),(e.y-lastY).toDouble())
                lastX=e.x; lastY=e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if(e.actionMasked==MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    // Temporary detach retains immutable underlay/cache but pauses queued work. In-flight
    // same-generation completion may populate cache; reattach resumes latest visible demand.
    override fun onDetachedFromWindow() {
        scheduler.setPaused(true); super.onDetachedFromWindow()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!disposed) { demand(); scheduler.setPaused(false) }
    }
    // Host must call close on permanent disposal (Activity/Fragment owner teardown).
    // Detach alone is NOT disposal; a closed view cannot be reused.
    override fun close() {
        if (disposed) return
        disposed = true; scheduler.close(); underlay=null; renderKey=null; invalidate()
    }
}
