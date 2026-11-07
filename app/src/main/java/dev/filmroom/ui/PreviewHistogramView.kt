package dev.filmroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small, translucent, touch-through readout. No worker or bitmap survives detach/stop. */
internal class PreviewHistogramView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(132, 24, 24, 24) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 241, 241, 241); strokeWidth = density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(84, 241, 241, 241) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 241, 241, 241); style = Paint.Style.STROKE; strokeWidth = density
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 241, 241, 241); style = Paint.Style.STROKE; strokeWidth = density
    }
    private val bounds = RectF()
    private val graph = Path()
    private val area = Path()
    private var input: PreviewHistogramInput? = null
    private var contentKey: String? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var foreground = true
    private var lastSampleAt = -UPDATE_INTERVAL_MS
    internal var histogram: PreviewHistogram? = null
        private set
    internal var displayedInput: PreviewHistogramInput? = null
        private set

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = L10n.text(R.string.preview_histogram_description)
        visibility = GONE
    }

    fun bind(next: PreviewHistogramInput?, key: String?) {
        if (input == next && contentKey == key) return
        val reset = contentKey != key || next == null
        contentKey = key
        input = next
        job?.cancel(); job = null
        if (reset) clearGraph()
        visibility = if (next == null) GONE else VISIBLE
        schedule()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) schedule() else {
            job?.cancel(); job = null
            input = null; contentKey = null; clearGraph(); visibility = GONE
        }
    }

    private fun clearGraph() {
        histogram = null; displayedInput = null
        graph.reset(); area.reset(); invalidate()
    }

    private fun schedule() {
        val expected = input ?: return
        if (!foreground || displayedInput == expected || scope == null) return
        job?.cancel()
        job = scope!!.launch {
            // Throttle, not debounce: a continuous slider gesture cannot postpone every update.
            delay((lastSampleAt + UPDATE_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            lastSampleAt = SystemClock.uptimeMillis()
            val result = withContext(Dispatchers.Default) { PreviewHistogramSampler.sample(expected) }
            if (input == expected && foreground) {
                histogram = result; displayedInput = expected
                rebuildGraph(); invalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        schedule()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel(); scope = null; job = null
        input = null; contentKey = null; clearGraph(); visibility = GONE
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGraph()
    }

    private fun rebuildGraph() {
        graph.reset(); area.reset()
        val data = histogram ?: return
        if (data.peak == 0) return
        val inset = 6 * density
        val left = inset; val right = width - inset
        val top = inset; val bottom = height - inset
        if (right <= left || bottom <= top) return
        data.bins.forEachIndexed { index, count ->
            val x = left + index.toFloat() / (data.bins.size - 1) * (right - left)
            val y = bottom - count.toFloat() / data.peak * (bottom - top)
            if (index == 0) graph.moveTo(x, y) else graph.lineTo(x, y)
        }
        area.set(graph); area.lineTo(right, bottom); area.lineTo(left, bottom); area.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (histogram == null) return
        val half = density / 2
        bounds.set(half, half, width - half, height - half)
        canvas.drawRoundRect(bounds, 6 * density, 6 * density, panel)
        canvas.drawRoundRect(bounds, 6 * density, 6 * density, outline)
        val inset = 6 * density
        val bottom = height - inset
        canvas.drawLine(inset, bottom, width - inset, bottom, grid)
        for (quarter in 1..3) {
            val x = inset + (width - 2 * inset) * quarter / 4f
            canvas.drawLine(x, inset, x, bottom, grid)
        }
        canvas.drawPath(area, fill)
        canvas.drawPath(graph, line)
    }

    companion object { internal const val UPDATE_INTERVAL_MS = 80L }
}
