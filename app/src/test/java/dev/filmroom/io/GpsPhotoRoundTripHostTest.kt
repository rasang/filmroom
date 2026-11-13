package dev.filmroom.io

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.DownloadsExportHostTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/** Real photo/decode and production metadata/export code; Android provider is simulated.
 * Set FILMROOM_GPS_JPEG to a local GPS JPEG. No fixture/photo or coordinates are committed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GpsPhotoRoundTripHostTest {
    private fun gps(m:PhotoMetadata):Map<String,String> {
        val attrs=JSONObject(m.toJson()).getJSONObject("attributes")
        return attrs.keys().asSequence().filter{it.startsWith("GPS")}.associateWith{attrs.getString(it)}
    }
    @Test fun selectedRealGpsPhotoImportAndJpegPngExportReadback()=runBlocking<Unit> {
        val path=System.getenv("FILMROOM_GPS_JPEG")
        assumeTrue("Opt-in local photo required",path!=null)
        val source=File(path!!);assertTrue(source.isFile)
        val sourceGps=gps(PhotoMetadata.read(source));assertTrue(sourceGps.size>=4)
        val expected=ExifInterface(source.absolutePath).latLong;assertNotNull("Valid GPS coordinates required",expected)
        val app=ApplicationProvider.getApplicationContext<Application>()
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val provider=DownloadsExportHostTest.Provider()
        provider.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY;exported=false})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,provider)
        val selected=Uri.parse("content://media/external/images/media/99999")
        val original=MediaStore.setRequireOriginal(selected)
        val redacted=File(app.cacheDir,"provider-redacted-source.jpg")
        source.copyTo(redacted,overwrite=true)
        PhotoMetadataContainer.inject(redacted,ExportFormat.JPEG,PhotoMetadata.fromAttributes(emptyMap()).exifBytes(640,480,"sRGB",0))
        shadowOf(app.contentResolver).registerInputStream(selected,redacted.inputStream())
        // Reproduce the former direct-stream behavior with a redacting provider.
        val oldRead=app.contentResolver.openInputStream(selected)!!.use(PhotoMetadata::readCaptureMetadata)
        assertTrue("Old direct URI read receives no GPS",gps(oldRead).isEmpty())
        shadowOf(app.contentResolver).registerInputStream(selected,redacted.inputStream())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        shadowOf(app.contentResolver).registerInputStream(original,source.inputStream())
        val repo=PhotoRepository(app);val loaded=repo.import(selected,640)
        val outDir=File("build/gps-roundtrip").apply{mkdirs()}
        try {
            assertEquals(sourceGps,gps(loaded.source.photoMetadata))
            val frozen=PhotoMetadata.fromJson(loaded.source.photoMetadata.toJson())
            val rgb=File(app.cacheDir,"gps-source.rgb")
            // Robolectric has no real BitmapRegionDecoder pixels; use the production
            // bitmap-to-linear adapter from the real ImageDecoder output instead.
            LinearSpool.fromBitmap(loaded.preview,rgb,kotlinx.coroutines.currentCoroutineContext())
            val png=repo.exportLinearRgbPng(rgb,loaded.preview.width,loaded.preview.height,photoMetadata=frozen)
            val jpeg=File(app.cacheDir,"gps-encoded.jpg")
            jpeg.outputStream().use{assertTrue(loaded.preview.compress(Bitmap.CompressFormat.JPEG,95,it))}
            val jpg=repo.exportEncodedPayload(jpeg,loaded.preview.width,loaded.preview.height,ExportFormat.JPEG,"sRGB",frozen,0)
            for((label,result)in listOf("png" to png,"jpeg" to jpg)) {
                val actual=File(app.cacheDir,"provider-${result.uri.lastPathSegment}.bin")
                val read=PhotoMetadata.read(actual)
                val pixels=android.graphics.BitmapFactory.decodeFile(actual.absolutePath)
                assertNotNull("$label decodes",pixels)
                try {
                    assertEquals(loaded.preview.width,pixels.width);assertEquals(loaded.preview.height,pixels.height)
                    val first=pixels.getPixel(0,0)
                    assertTrue("$label must contain real image pixels, not a blank host decoder result",(0 until pixels.height).any{y->(0 until pixels.width).any{x->pixels.getPixel(x,y)!=first}})
                } finally {pixels.recycle()}
                assertEquals("$label GPS tags",sourceGps,gps(read))
                assertArrayEquals("$label coordinates",expected,ExifInterface(actual.absolutePath).latLong,1e-7)
                actual.copyTo(File(outDir,"export.$label"),overwrite=true)
            }
            File(outDir,"result.txt").writeText("Real JPEG imported through PhotoRepository; GPS fields preserved in snapshot and JPEG/PNG export readback. GPS tag count=${sourceGps.size}. Former direct URI read returned 0 GPS tags under the same simulated redacting provider. Simulated MediaStore provider, not device end-to-end. JPEG uses platform encoder plus production encoded-payload metadata path; PNG uses production bitmap-to-linear adapter and row encoder. Region decoder and film engine are not exercised.\n")
            rgb.delete();jpeg.delete();redacted.delete()
        } finally {loaded.preview.recycle();repo.discard(loaded.source)}
    }
    @Test fun refusedMediaLocationImportsRedactedPhotoWithoutInventingGps()=runBlocking<Unit> {
        val path=System.getenv("FILMROOM_GPS_JPEG");assumeTrue("Opt-in local photo required",path!=null)
        val app=ApplicationProvider.getApplicationContext<Application>()
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val copy=File(app.cacheDir,"gps-redacted.jpg");File(path!!).copyTo(copy,overwrite=true)
        PhotoMetadataContainer.inject(copy,ExportFormat.JPEG,PhotoMetadata.fromAttributes(emptyMap()).exifBytes(1,1,"sRGB",0))
        val uri=Uri.parse("content://media/external/images/media/99998")
        shadowOf(app.contentResolver).registerInputStream(uri,copy.inputStream())
        val repo=PhotoRepository(app);val loaded=repo.import(uri,128)
        try { assertTrue(gps(loaded.source.photoMetadata).isEmpty()) }
        finally {loaded.preview.recycle();repo.discard(loaded.source);copy.delete()}
    }
}
