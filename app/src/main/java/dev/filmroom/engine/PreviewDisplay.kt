package dev.filmroom.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Packed opaque sRGB presentation only. No science or transfer-function changes. */
internal object PreviewDisplay {
    fun copyRgba8(bitmap:Bitmap, output:ByteBuffer, checkCurrent:()->Unit) {
        checkCurrent()
        require(bitmap.config==Bitmap.Config.ARGB_8888 && bitmap.isMutable)
        val rowBytes=Math.multiplyExact(bitmap.width,4)
        // copyPixelsFromBuffer copies native rows including padding. Never silently
        // reinterpret a packed image as padded rows on a different Android device.
        check(bitmap.rowBytes==rowBytes) { "Unsupported preview bitmap row stride" }
        val bytes=Math.multiplyExact(rowBytes,bitmap.height)
        require(output.capacity()==bytes && output.limit()==bytes)
        output.rewind()
        checkCurrent()
        bitmap.copyPixelsFromBuffer(output)
        // Cancellation racing memcpy must prevent capture/publication by the caller.
        checkCurrent()
    }
}
