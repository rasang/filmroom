package dev.filmroom.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.formats.PhotoFormat
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectInputFormatTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Test fun realPngBytesWithMisleadingFilenameMapFromDecodedHeader() {
        val file=File.createTempFile("format-",".jpg",context.cacheDir)
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            assertTrue(file.length()>8)
            var format=PhotoFormat.UNKNOWN
            val decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))) { _,info,_->
                format=EffectInputFormat.fromDecodedHeader(info.mimeType)
            }
            assertEquals(2,decoded.width)
            assertEquals(2,decoded.height)
            decoded.recycle()
            assertEquals(PhotoFormat.PNG,format)
        }finally{bitmap.recycle();file.delete()}
    }
    @Test fun nativeValidatedRawIdentitiesAreFixedVocabulary() {
        assertEquals(PhotoFormat.DNG,EffectInputFormat.fromDecodedHeader("image/x-adobe-dng"))
        for(mime in listOf("image/x-nikon-nef","image/x-fuji-raf","image/x-canon-cr3"))
            assertEquals(PhotoFormat.CAMERA_RAW,EffectInputFormat.fromDecodedHeader(mime))
    }
    @Test fun unrecognizedHeaderMimeCannotBecomeFreeText() {
        for(value in listOf(null,"content://private/photo.jpg","/private.jpg","image/my-secret","image/jpeg;secret"))
            assertEquals(PhotoFormat.UNKNOWN,EffectInputFormat.fromDecodedHeader(value))
        assertEquals(PhotoFormat.JPEG,EffectInputFormat.fromDecodedHeader("image/jpeg"))
    }
}
