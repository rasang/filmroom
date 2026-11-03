package dev.filmroom.io.importing

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.PhotoRepository
import dev.filmroom.io.PhotoException
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs

/** Real host NativeGraphics JPEG/PNG decode, simulated Android provider; no device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterRepositoryHostTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun register(bytes:ByteArray):Uri {
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val uri=Uri.parse("content://filmroom.raster-adapter/${System.nanoTime()}.dng")
        shadowOf(app.contentResolver).registerInputStream(uri,bytes.inputStream())
        return uri
    }
    private fun encoded(format:Bitmap.CompressFormat,color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(color);return ByteArrayOutputStream().use { out->assertTrue(bitmap.compress(format,100,out));out.toByteArray() } }
        finally{bitmap.recycle()}
    }
    @Test fun jpegContentRoutesDespiteNameAndExifOrientationIsAppliedOnce()=runBlocking {
        val file=File.createTempFile("adapter-jpeg-",".jpg",app.cacheDir)
        try {
            file.writeBytes(encoded(Bitmap.CompressFormat.JPEG,Color.rgb(40,110,180)))
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_MAKE,"Synthetic")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:10:31 12:34:56")
                saveAttributes()
            }
            val bytes=file.readBytes();val repo=PhotoRepository(app);val loaded=repo.import(register(bytes),128)
            try {
                assertEquals("image/jpeg",loaded.source.mimeType)
                assertEquals(16,loaded.source.originalWidth);assertEquals(32,loaded.source.originalHeight)
                assertEquals(16,loaded.preview.width);assertEquals(32,loaded.preview.height)
                assertArrayEquals(bytes,loaded.source.localFile.readBytes())
                val capture=org.json.JSONObject(loaded.source.photoMetadata.toJson()).getJSONObject("attributes")
                assertEquals("Synthetic",capture.getString("Make"))
                assertEquals("2026:10:31 12:34:56",capture.getString("DateTimeOriginal"))
            }finally{loaded.preview.recycle();repo.discard(loaded.source)}
        }finally{file.delete()}
    }
    @Test fun pngAlphaStillCompositesOnWhiteAndPreservesEncodedSource()=runBlocking {
        val bytes=encoded(Bitmap.CompressFormat.PNG,Color.argb(128,255,0,0))
        val repo=PhotoRepository(app);val loaded=repo.import(register(bytes),128)
        try {
            assertEquals("image/png",loaded.source.mimeType);assertTrue(loaded.source.alphaCompositedOnWhite)
            assertEquals(32,loaded.source.originalWidth);assertEquals(16,loaded.source.originalHeight)
            val pixel=loaded.preview.getPixel(8,8)
            assertEquals(255,Color.alpha(pixel));assertEquals(255,Color.red(pixel))
            assertTrue(Color.green(pixel) in 126..129);assertTrue(Color.blue(pixel) in 126..129)
            assertArrayEquals(bytes,loaded.source.localFile.readBytes())
        }finally{loaded.preview.recycle();repo.discard(loaded.source)}
    }
    @Test fun legacyReopenRereadsOriginalXmpInsteadOfStaleMetadataSnapshot()=runBlocking {
        val dir=File(app.cacheDir,"photo-sources").apply{mkdirs()}
        val file=File(dir,"source-Legacy123.image")
        try {
            file.writeBytes(encoded(Bitmap.CompressFormat.JPEG,Color.rgb(40,110,180)))
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:10:24 15:15:59")
                setAttribute(ExifInterface.TAG_XMP,"""<a xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreateDate="2026-10-24T15:15:59+00:00"/>""")
                saveAttributes()
            }
            val bytes=file.readBytes()
            val loaded=PhotoRepository(app).reopen(file.name,"legacy",128)
            try {
                assertEquals(java.time.Instant.parse("2026-10-24T15:15:59Z").toEpochMilli(),loaded.source.photoMetadata.captureTimeMillis())
                assertArrayEquals(bytes,file.readBytes())
            }finally{loaded.preview.recycle()}
        }finally{file.delete()}
    }
    @Test fun corruptJpegDoesNotFallBackToRawAndRollsBackPrivateCopy()=runBlocking {
        val dir=File(app.cacheDir,"photo-sources");val before=dir.listFiles().orEmpty().map{it.name}.toSet()
        try { PhotoRepository(app).import(register(byteArrayOf(-1,-40,-1,0)),128);fail("Corrupt JPEG") }
        catch(e:PhotoException){
            assertEquals(PhotoException.Reason.UNREADABLE,e.reason)
            assertEquals(dev.filmroom.diagnostics.CodecStage.INPUT_PROCESS,e.codecStage)
            assertEquals(dev.filmroom.io.formats.PhotoFormat.JPEG,e.inputFormatHint)
        }
        assertEquals(before,dir.listFiles().orEmpty().map{it.name}.toSet())
    }
    @Test fun providerReadFailureIsClassifiedWithoutOriginalPhoto()=runBlocking {
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val uri=Uri.parse("content://dev.filmroom.synthetic/broken")
        val source=object : java.io.InputStream() {
            override fun read():Int=throw java.io.IOException("do-not-log-private-path")
            override fun read(b:ByteArray, off:Int, len:Int):Int=throw java.io.IOException("do-not-log-private-path")
        }
        shadowOf(app.contentResolver).registerInputStream(uri,source)
        val stages=mutableListOf<dev.filmroom.diagnostics.CodecStage>()
        try { PhotoRepository(app).import(uri,128) { stage, _ -> stages.add(stage) }; fail("expected I/O failure") }
        catch(e:PhotoException) {
            assertEquals(dev.filmroom.diagnostics.CodecErrorCode.IMPORT_IO,e.codecCode)
            assertEquals(dev.filmroom.diagnostics.CodecStage.SOURCE_COPY,e.codecStage)
            assertNull(e.inputFormatHint)
            assertEquals(listOf(dev.filmroom.diagnostics.CodecStage.SOURCE_OPEN,dev.filmroom.diagnostics.CodecStage.SOURCE_COPY),stages)
        }
    }

}
