package dev.filmroom.effects

import kotlin.math.max
import kotlin.math.min

/** LCD cell geometry. Keep clear space between the stroked seven-segment glyphs. */
internal data class EffectShutterLayout(
    val glyphWidth: Float,
    val gap: Float,
    val padding: Float,
    val windowWidth: Float,
    val contentWidth: Float,
    val strokeWidth: Float,
) {
    val advance: Float get() = glyphWidth + gap
    fun glyphX(index: Int): Float = windowWidth - padding - contentWidth + index * advance

    companion object {
        fun create(count: Int, height: Float, maxWindowWidth: Float): EffectShutterLayout {
            require(count > 0 && height > 0f && maxWindowWidth > 0f)
            val naturalGlyph = height * .48f
            val naturalGap = height * .28f
            val naturalPadding = max(2.5f, height * .28f)
            val naturalContent = count * naturalGlyph + (count - 1) * naturalGap
            val naturalWindow = max(naturalContent + 2f * naturalPadding, height * 4.5f)
            val scale = min(1f, maxWindowWidth / naturalWindow)
            return EffectShutterLayout(naturalGlyph * scale, naturalGap * scale,
                naturalPadding * scale, naturalWindow * scale, naturalContent * scale,
                height * .09f * scale)
        }
    }
}
