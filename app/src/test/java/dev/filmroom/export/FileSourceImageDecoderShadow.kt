package dev.filmroom.export

import android.graphics.ImageDecoder
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNativeImageDecoder
import java.io.File
import java.nio.ByteBuffer

/** Robolectric cannot decode Android FileDescriptor sources. Only substitute source transport:
 * keep the real ImageDecoder/native pixel decode and production decodeFile callbacks intact. */
@Implements(value=ImageDecoder::class, callNativeMethodsByDefault=true)
class FileSourceImageDecoderShadow : ShadowNativeImageDecoder() {
    companion object {
        @Implementation
        @JvmStatic
        fun createSource(file: File): ImageDecoder.Source =
            ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))
    }
}
