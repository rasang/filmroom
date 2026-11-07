package dev.filmroom.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import dev.filmroom.R

/** Profile identity, never carousel position, chooses the packaging model. */
internal object PrintMediumArtwork {
    val profileIds = setOf(
        "fujifilm_crystal_archive_typeii", "kodak_2383", "kodak_2393",
        "kodak_ektacolor_edge", "kodak_endura_premier", "kodak_portra_endura",
        "kodak_supra_endura", "kodak_ultra_endura", "kodak_2302", "ilford_multigrade_iv_rc",
    )
    private val cache = mutableMapOf<String, Bitmap>()
    fun assetPath(id: String): String? = id.takeIf { it in profileIds }?.let { "print-media/$it.webp" }
    fun drawable(context: Context, id: String): Drawable {
        if (id == "none") return EmptyPaperDrawable()
        val bitmap = assetPath(id)?.let { path -> synchronized(cache) {
            cache[path] ?: runCatching { context.assets.open(path).use { input -> BitmapFactory.decodeStream(input) } }
                .getOrNull()?.also { cache[path] = it }
        } }
        return bitmap?.let { BitmapDrawable(context.resources, it) }
            ?: context.getDrawable(R.drawable.ic_tool_paper)!!.mutate()
    }
    private class EmptyPaperDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(5f,4f),0f)
        }
        override fun draw(canvas: Canvas) {
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width()/72f,bounds.height()/72f)
            val path=Path().apply { moveTo(14f,14f);lineTo(53f,10f);lineTo(59f,16f);lineTo(59f,59f);lineTo(20f,63f);lineTo(14f,57f);close();moveTo(14f,14f);lineTo(20f,20f);lineTo(59f,16f);moveTo(20f,20f);lineTo(20f,63f) }
            canvas.drawPath(path,paint);canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
