package dev.filmroom.effects

import kotlin.math.roundToInt

/** UI gesture coordinates are top-left based; GL viewport coordinates are bottom-left based.
 * The logical scene size never changes when zooming, so overlays are not re-rasterized. */
internal class HomeMotionViewport {
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int)
    private var width = 1
    private var height = 1
    private var sceneWidth = 1
    private var sceneHeight = 1
    private var maxScale = 4f
    private var zoom = 1f
    private var x = 0f
    private var y = 0f
    @Synchronized fun scale(): Float = zoom
    @Synchronized fun configure(width: Int, height: Int, sceneWidth: Int, sceneHeight: Int,
        maxWidth: Int = Int.MAX_VALUE, maxHeight: Int = Int.MAX_VALUE) {
        this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        this.sceneWidth = sceneWidth.coerceAtLeast(1); this.sceneHeight = sceneHeight.coerceAtLeast(1)
        maxScale = minOf(4f, maxWidth.toFloat()/this.sceneWidth, maxHeight.toFloat()/this.sceneHeight).coerceAtLeast(.001f)
        zoom = zoom.coerceIn(minOf(1f,maxScale),maxScale); constrain()
    }
    @Synchronized fun reset() { zoom = minOf(1f,maxScale); x=0f; y=0f }
    @Synchronized fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (!factor.isFinite() || factor<=0 || !focusX.isFinite() || !focusY.isFinite()) return
        val next=(zoom*factor).coerceIn(minOf(1f,maxScale),maxScale)
        val ratio=next/zoom
        x=(focusX-width/2f)*(1-ratio)+x*ratio
        y=(focusY-height/2f)*(1-ratio)+y*ratio
        zoom=next; constrain()
    }
    @Synchronized fun doubleTap(focusX: Float, focusY: Float) {
        if (zoom>1.01f) reset() else zoomBy(2f/zoom,focusX,focusY)
    }
    @Synchronized fun pan(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        x+=dx; y+=dy; constrain()
    }
    private fun constrain() {
        val boundX=((sceneWidth*zoom-width)/2f).coerceAtLeast(0f)
        val boundY=((sceneHeight*zoom-height)/2f).coerceAtLeast(0f)
        x=x.coerceIn(-boundX,boundX); y=y.coerceIn(-boundY,boundY)
    }
    @Synchronized fun frame(): Frame {
        val w=(sceneWidth*zoom).roundToInt().coerceAtLeast(1)
        val h=(sceneHeight*zoom).roundToInt().coerceAtLeast(1)
        return Frame(((width-w)/2f+x).roundToInt(),((height-h)/2f-y).roundToInt(),w,h)
    }
}
