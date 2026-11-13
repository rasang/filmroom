package dev.filmroom.io

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.DownloadsExportHostTest
import dev.filmroom.effects.EffectCaptureMetadata
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

/** Opt-in public photos plus controlled metadata. Does not simulate vendor gallery behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GpsMatrixHostTest {
    private fun gps(m:PhotoMetadata):Map<String,String> {
        val a=JSONObject(m.toJson()).getJSONObject("attributes")
        return a.keys().asSequence().filter{it.startsWith("GPS")}.associateWith{a.getString(it)}
    }
    @Test fun realAndControlledPhotosRoundTrip()=runBlocking<Unit> {
        val path=System.getenv("FILMROOM_GPS_CASES")
        assumeTrue("Opt-in public fixture directory required",path!=null)
        val photos=File(path!!).listFiles()!!.filter{it.extension.lowercase() in listOf("jpg","jpeg")}.sortedBy{it.name}
        assertTrue("At least three real photos required",photos.size>=3)
        val app=ApplicationProvider.getApplicationContext<Application>()
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val provider=DownloadsExportHostTest.Provider()
        provider.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY;exported=false})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,provider)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val repo=PhotoRepository(app)
        val work=File(app.cacheDir,"gps-matrix").apply{mkdirs()}
        val output=File("build/gps-matrix").apply{mkdirs()}
        val cases=photos.map{it.nameWithoutExtension to it}.toMutableList()
        // Generate valid pixels once; synthetic locations are intentionally not real user data.
        val bitmap=BitmapFactory.decodeFile(photos.first{it.name.startsWith("DSCN")}.absolutePath)
        val small=Bitmap.createScaledBitmap(bitmap,160,120,true)
        for((index,refs) in listOf("N" to "E","N" to "W","S" to "E","S" to "W").withIndex()) {
            val attrs=mapOf("GPSLatitudeRef" to refs.first,"GPSLatitude" to "24/1,30/1,0/1",
                "GPSLongitudeRef" to refs.second,"GPSLongitude" to "118/1,5/1,0/1",
                "GPSAltitudeRef" to if(index%2==0)"0" else "1","GPSAltitude" to "12345/100",
                "GPSDateStamp" to "2024:02:29","GPSTimeStamp" to "12:34:56",
                "DateTimeOriginal" to "2024:02:29 20:34:56","OffsetTimeOriginal" to "+08:00")
            for(format in listOf(ExportFormat.JPEG,ExportFormat.PNG)) {
                val file=File(work,"controlled-${refs.first}${refs.second}-${format.name}")
                file.outputStream().use{assertTrue(small.compress(if(format==ExportFormat.PNG)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,95,it))}
                PhotoMetadataContainer.inject(file,format,PhotoMetadata.fromAttributes(attrs).exifBytes(160,120,"sRGB",0))
                cases += file.name to file
            }
        }
        val noGps=File(work,"no-gps.jpg")
        noGps.outputStream().use{assertTrue(small.compress(Bitmap.CompressFormat.JPEG,95,it))}
        cases += "no-gps" to noGps
        small.recycle();bitmap.recycle()
        val rows=mutableListOf<String>()
        try {
            for((index,pair) in cases.withIndex()) {
                val(label,source)=pair
                val expected=PhotoMetadata.read(source);val sourceExif=ExifInterface(source.absolutePath)
                if(gps(expected).isNotEmpty())assertNotNull("$label valid coordinates",sourceExif.latLong)
                val uri=Uri.parse("content://media/external/images/media/${80000+index}")
                shadowOf(app.contentResolver).registerInputStream(MediaStore.setRequireOriginal(uri),source.inputStream())
                val loaded=repo.import(uri,160)
                try {
                    assertEquals("$label imported GPS",gps(expected),gps(loaded.source.photoMetadata))
                    val frozen=PhotoMetadata.fromJson(loaded.source.photoMetadata.toJson())
                    val live=EffectCaptureMetadata.resolve(frozen.toJson(),emptyList()){error("No fallback needed")}
                    assertEquals("$label Live metadata",gps(expected),gps(live))
                    assertEquals(gps(expected).isNotEmpty(),live.livePhotoCaptureExif().any{it.group==2})
                    val rgb=File(work,"linear-$index.rgb")
                    LinearSpool.fromBitmap(loaded.preview,rgb,kotlinx.coroutines.currentCoroutineContext())
                    val png=repo.exportLinearRgbPng(rgb,loaded.preview.width,loaded.preview.height,photoMetadata=frozen)
                    val jpeg=File(work,"encoded.jpg")
                    jpeg.outputStream().use{assertTrue(loaded.preview.compress(Bitmap.CompressFormat.JPEG,95,it))}
                    val jpg=repo.exportEncodedPayload(jpeg,loaded.preview.width,loaded.preview.height,ExportFormat.JPEG,"sRGB",frozen,0)
                    for((ext,result) in listOf("png" to png,"jpg" to jpg)) {
                        val file=File(app.cacheDir,"provider-${result.uri.lastPathSegment}.bin")
                        assertEquals("$label $ext GPS",gps(expected),gps(PhotoMetadata.read(file)))
                        val actual=ExifInterface(file.absolutePath)
                        sourceExif.latLong?.let{assertArrayEquals("$label $ext location",it,actual.latLong,1e-7)}
                            ?:assertNull(actual.latLong)
                        assertEquals(sourceExif.getAltitude(Double.NaN),actual.getAltitude(Double.NaN),1e-7)
                        assertEquals(sourceExif.getAttribute("DateTimeOriginal"),actual.getAttribute("DateTimeOriginal"))
                        val decoded=BitmapFactory.decodeFile(file.absolutePath);assertNotNull(decoded)
                        assertEquals(loaded.preview.width,decoded.width);assertEquals(loaded.preview.height,decoded.height)
                        val first=decoded.getPixel(0,0)
                        assertTrue((0 until decoded.height).any{y->(0 until decoded.width).any{x->decoded.getPixel(x,y)!=first}})
                        decoded.recycle();file.copyTo(File(output,"$label.$ext"),overwrite=true)
                    }
                    rows += "$label: import, frozen metadata, Live metadata, JPEG and PNG readback PASS; GPS tags=${gps(expected).size}"
                } finally {loaded.preview.recycle();repo.discard(loaded.source)}
            }
            File(output,"results.txt").writeText(rows.joinToString("\n")+"\nHost simulation only; not device/vendor-gallery or full film/native JPEG pipeline.\n")
        } finally {work.deleteRecursively()}
    }
}
