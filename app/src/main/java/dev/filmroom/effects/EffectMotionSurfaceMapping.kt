package dev.filmroom.effects

import dev.filmroom.io.PhotoLimits
import kotlin.math.roundToInt

/** Reconstructed beta27 surface coordinates, before SurfaceTexture's own matrix. */
data class EffectMotionSurfaceMapping(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
    val origin: Pair<Double, Double>,
    val u: Pair<Double, Double>,
    val v: Pair<Double, Double>,
    val rotation: Int,
) {
    fun uprightPoint(x: Double, y: Double): Pair<Double, Double> =
        (origin.first + u.first * x + v.first * y) to (origin.second + u.second * x + v.second * y)

    fun rawPoint(x: Double, y: Double): Pair<Double, Double> = when (rotation) {
        90 -> y to (1.0 - x)
        180 -> (1.0 - x) to (1.0 - y)
        270 -> (1.0 - y) to x
        else -> x to y
    }

    companion object {
        internal fun create(rawWidth: Int, rawHeight: Int, rotationDegrees: Int,
                   composition: EffectSourceComposition?, maxEdge: Int = 720): EffectMotionSurfaceMapping {
            require(rawWidth > 0 && rawHeight > 0)
            val rotation = ((rotationDegrees % 360) + 360) % 360
            require(rotation in setOf(0, 90, 180, 270)) { "Unsupported motion rotation" }
            val uprightWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
            val uprightHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
            val scale = minOf(1.0, maxEdge.coerceIn(240, PhotoLimits.PREVIEW_EDGE).toDouble() / maxOf(uprightWidth, uprightHeight))
            val sourceWidth = (uprightWidth * scale).roundToInt().coerceAtLeast(1)
            val sourceHeight = (uprightHeight * scale).roundToInt().coerceAtLeast(1)
            val snapshot = composition?.snapshot(sourceWidth, sourceHeight)
            val transform = composition?.settings?.effectiveTransform(composition.canonicalWidth, composition.canonicalHeight)
            val origin = transform?.sourcePoint(0.0, 0.0, composition!!.canonicalWidth, composition.canonicalHeight) ?: (0.0 to 0.0)
            val right = transform?.sourcePoint(1.0, 0.0, composition!!.canonicalWidth, composition.canonicalHeight) ?: (1.0 to 0.0)
            val bottom = transform?.sourcePoint(0.0, 1.0, composition!!.canonicalWidth, composition.canonicalHeight) ?: (0.0 to 1.0)
            return EffectMotionSurfaceMapping(sourceWidth, sourceHeight,
                snapshot?.outputWidth ?: sourceWidth, snapshot?.outputHeight ?: sourceHeight,
                origin, (right.first - origin.first) to (right.second - origin.second),
                (bottom.first - origin.first) to (bottom.second - origin.second), rotation)
        }
    }
}
