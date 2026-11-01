package dev.filmroom.io

/** All eight EXIF transforms. Source coordinates are raw, output is display-oriented. */
class ExifMapping(val rawWidth: Int, val rawHeight: Int, val orientation: Int) {
    init { require(rawWidth > 0 && rawHeight > 0 && orientation in 1..8) }
    val transposed: Boolean get() = orientation >= 5
    val width: Int get() = if (transposed) rawHeight else rawWidth
    val height: Int get() = if (transposed) rawWidth else rawHeight
    fun rawX(x: Int, y: Int): Int = when (orientation) {
        1, 4 -> x
        2, 3 -> rawWidth - 1 - x
        5, 6 -> y
        7, 8 -> rawWidth - 1 - y
        else -> error("Invalid orientation")
    }
    fun rawY(x: Int, y: Int): Int = when (orientation) {
        1, 2 -> y
        3, 4 -> rawHeight - 1 - y
        5, 8 -> x
        6, 7 -> rawHeight - 1 - x
        else -> error("Invalid orientation")
    }
}
