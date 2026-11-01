package dev.filmroom.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A local preview, not a simulation of the final result or a completion percentage. */
class ProcessingAnimationView(context: Context) : View(context) {
    private val amber = Color.rgb(229, 163, 93)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val card = RectF(76f, 12f, 204f, 140f)
    private val imageBounds = RectF()
    private val clip = Path().apply { addRoundRect(card, 14f, 14f, Path.Direction.CW) }
    private val path = Path()
    private var photo: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var phase = 0f
    var stage: ProcessingVisualStage = ProcessingVisualStage.PREPARING
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    /** Borrows the existing small preview. Never decodes, copies, or recycles the host bitmap. */
    fun setPhoto(bitmap: Bitmap?) {
        if (photo !== bitmap) { photo = bitmap; invalidate() }
    }

    fun updateStage(message: String) {
        updateStage(ProcessingVisualStage.fromMessage(message))
    }

    fun updateStage(next:ProcessingVisualStage) {
        if (next != stage) { stage = next; phase = 0f; invalidate() }
        updateMotion()
    }

    private fun shouldAnimate() = isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()

    private fun updateMotion() {
        if (!shouldAnimate()) {
            animator?.cancel()
            phase = 0f
            invalidate()
            return
        }
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2800L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    if (!shouldAnimate()) { cancel(); phase = 0f }
                    else phase = it.animatedValue as Float
                    invalidate()
                }
            }
        }
        if (animator?.isStarted != true) animator?.start()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator?.removeAllUpdateListeners()
        animator = null
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View's constructor can dispatch this before Kotlin field initialization.
        if (isAttachedToWindow) updateMotion()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateMotion()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isAttachedToWindow) updateMotion()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 280f, height / 160f)
        if (scale <= 0f) return
        val saved = canvas.save()
        canvas.translate((width - 280f * scale) / 2f, (height - 160f * scale) / 2f)
        canvas.scale(scale, scale)
        val pulse = (sin(phase * Math.PI * 2.0).toFloat() + 1f) * .5f
        // A quiet paper edge under the photo gives depth without a decorative film cartoon.
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = Color.rgb(52, 49, 43); paint.alpha = 255
        canvas.drawRoundRect(80f, 17f, 200f, 145f, 14f, 14f, paint)
        val imageSave = canvas.save()
        if (stage == ProcessingVisualStage.SAVING) canvas.translate(0f, pulse * 2f)
        canvas.clipPath(clip)
        val bitmap = photo?.takeUnless { it.isRecycled }
        if (bitmap != null) {
            val factor = max(card.width() / bitmap.width, card.height() / bitmap.height)
            val w = bitmap.width * factor; val h = bitmap.height * factor
            imageBounds.set(card.centerX() - w / 2, card.centerY() - h / 2,
                card.centerX() + w / 2, card.centerY() + h / 2)
            paint.color = Color.WHITE; paint.alpha = 255
            canvas.drawBitmap(bitmap, null, imageBounds, paint)
        } else {
            // No fabricated landscape: a restrained photographic color field while preview is absent.
            paint.shader = LinearGradient(76f, 12f, 204f, 140f,
                intArrayOf(Color.rgb(128, 88, 58), Color.rgb(58, 64, 56), Color.rgb(30, 37, 35)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(card, paint); paint.shader = null
        }
        when (stage) {
            ProcessingVisualStage.READING, ProcessingVisualStage.PRINTING -> {
                // A recurring light pass denotes activity, never a completion bar.
                val x = 40f + phase * 220f
                paint.shader = LinearGradient(x - 35f, 0f, x + 35f, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(48, 255, 239, 215), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                canvas.drawRect(card, paint); paint.shader = null
            }
            ProcessingVisualStage.GRAIN -> {
                paint.color = Color.rgb(255, 242, 220)
                for (i in 0 until 100) {
                    paint.alpha = (20 + 48 * ((sin(i * 2.7 + phase * 6.28) + 1) * .5)).toInt()
                    canvas.drawCircle(78f + (i * 53 % 124), 14f + (i * 37 % 124), .65f, paint)
                }
            }
            ProcessingVisualStage.HALATION -> {
                paint.shader = RadialGradient(170f, 45f, 90f,
                    intArrayOf(Color.argb((20 + pulse * 28).toInt(), 255, 176, 92), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                canvas.drawRect(card, paint); paint.shader = null
            }
            ProcessingVisualStage.SAVING, ProcessingVisualStage.CLEANING -> Unit
            else -> {
                paint.color = amber; paint.alpha = (8 + pulse * 16).toInt()
                canvas.drawRect(card, paint)
            }
        }
        canvas.restoreToCount(imageSave)
        // Small separate activity accent. It never fills up or turns into a success check.
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND; paint.color = amber; paint.alpha = 210
        if (stage == ProcessingVisualStage.SAVING) {
            val y = 92f + pulse * 3f
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(29, 32, 29); paint.alpha = 245
            canvas.drawCircle(198f, 126f, 17f, paint)
            paint.style = Paint.Style.STROKE; paint.color = amber; paint.alpha = 255
            canvas.drawLine(198f, y + 25f, 198f, y + 38f, paint)
            path.rewind(); path.moveTo(193f, y + 33f); path.lineTo(198f, y + 38f); path.lineTo(203f, y + 33f)
            canvas.drawPath(path, paint)
        } else {
            for (i in 0..2) {
                paint.style = Paint.Style.FILL
                paint.alpha = (65 + 140 * ((sin(phase * 6.28 - i * 1.0) + 1) * .5)).toInt()
                canvas.drawCircle(132f + i * 8f, 155f, 1.7f, paint)
            }
        }
        paint.alpha = 255; paint.style = Paint.Style.FILL
        canvas.restoreToCount(saved)
    }
}
