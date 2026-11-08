package dev.filmroom.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import dev.filmroom.R

/** Profile identity, never carousel position, chooses the packaging model. */
internal object PrintMediumArtwork {
    val profileIds = setOf(
        "fujifilm_crystal_archive_typeii", "kodak_2383", "kodak_2393",
        "kodak_ektacolor_edge", "kodak_endura_premier", "kodak_portra_endura",
        "kodak_supra_endura", "kodak_ultra_endura", "kodak_2302", "ilford_multigrade_iv_rc",
    )
    private data class Artwork(val bitmap: Bitmap, val visible: Rect)
    private val cache = mutableMapOf<String, Artwork>()
    fun assetPath(id: String): String? = id.takeIf { it in profileIds }?.let { "print-media/$it.webp" }
    fun drawable(context: Context, id: String): Drawable {
        if (id == "none") return EmptyPaperDrawable()
        val bitmap = assetPath(id)?.let { path -> synchronized(cache) {
            cache[path] ?: runCatching { context.assets.open(path).use { input -> BitmapFactory.decodeStream(input) } }
                .getOrNull()?.let { Artwork(it, visibleBounds(it)) }?.also { cache[path] = it }
        } }
        return bitmap?.let { CenteredArtworkDrawable(it) }
            ?: context.getDrawable(R.drawable.ic_tool_paper)!!.mutate()
    }
    /** Exclude transparent render padding, not the printed artwork, from layout geometry. */
    private fun visibleBounds(bitmap: Bitmap): Rect {
        var left=bitmap.width;var top=bitmap.height;var right=-1;var bottom=-1
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            if(Color.alpha(bitmap.getPixel(x,y))>8) {
                left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
            }
        }
        return if(right>=left)Rect(left,top,right+1,bottom+1) else Rect(0,0,bitmap.width,bitmap.height)
    }
    private class CenteredArtworkDrawable(private val art: Artwork): Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas:Canvas) {
            val size=minOf(bounds.width(),bounds.height())*48f/72f
            val scale=size/maxOf(art.visible.width(),art.visible.height())
            val w=art.visible.width()*scale;val h=art.visible.height()*scale
            val cx=bounds.exactCenterX();val cy=bounds.exactCenterY()
            canvas.drawBitmap(art.bitmap,art.visible,RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2),paint)
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private class EmptyPaperDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(5f,4f),0f)
        }
        override fun draw(canvas: Canvas) {
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width()/72f,bounds.height()/72f)
            // Mirror the outline so its visible side faces the same way as the paper boxes.
            canvas.translate(36f,36f);canvas.scale(-48f/53f,48f/53f);canvas.translate(-36.5f,-36.5f)
            val path=Path().apply { moveTo(14f,14f);lineTo(53f,10f);lineTo(59f,16f);lineTo(59f,59f);lineTo(20f,63f);lineTo(14f,57f);close();moveTo(14f,14f);lineTo(20f,20f);lineTo(59f,16f);moveTo(20f,20f);lineTo(20f,63f) }
            canvas.drawPath(path,paint);canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
