package dev.filmroom.codec

import androidx.exifinterface.media.ExifInterface
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.io.ExportFormat
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.io.PhotoMetadataContainer
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in real Linux JNI regression using explicit caller-provided companded and large-strip DNG samples.
 * This does not assert Android device behavior or manufacturer rendering parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RealLinearizedDngCodecHostTest {
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)} }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    @Test fun realCompandedAndLargeStripDngImportExportKeepSourceAndMetadata()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        val samples=listOf("LUT_DNG","STRIP_DNG").map { format->
            val path=System.getenv("FILMROOM_${format}_SAMPLE");assumeTrue(path!=null)
            format to File(checkNotNull(path))
        }
        assertTrue(NativeCodecBridge.available)
        val root=kotlin.io.path.createTempDirectory("real-linearized-dng").toFile().canonicalFile
        val codec=PhotoCodecs(root,memorySnapshot={NativeMemorySnapshot(4096L*1024*1024,128L*1024*1024,false)})
        try {
            for((format,original) in samples) {
                val input=original.copyTo(File(root,"$format.misleading.jpeg"));val before=hash(input)
                val capture=PhotoMetadata.read(input)
                val fields=JSONObject(capture.toJson()).getJSONObject("attributes")
                assertTrue("Capture make preserved for $format",fields.optString("Make").isNotEmpty())
                assertTrue("Capture model preserved for $format",fields.optString("Model").isNotEmpty())
                assertEquals(RawFormat.DNG,RawMetadata.parse(codec.probe(input)).format)
                val output=File(root,"$format.rgb");val decoded=RawMetadata.parse(codec.decode(input,output))
                assertEquals("DNG",decoded.format.name);assertEquals(decoded.bytes,output.length())
                val jpeg=File(root,"$format.export.jpg")
                assertTrue(codec.encode(output,jpeg,decoded.width,decoded.height,"JPEG",90).getBoolean("ok"))
                PhotoMetadataContainer.inject(jpeg,ExportFormat.JPEG,capture.exifBytes(decoded.width,decoded.height,"sRGB",0))
                val exif=ExifInterface(jpeg.absolutePath)
                for(key in listOf("Make","Model","DateTimeOriginal","ExposureTime","FNumber","FocalLength")) {
                    if(fields.has(key)) {
                        // Rational text may be normalized by ExifInterface; compare typed capture snapshots.
                        val exported=JSONObject(PhotoMetadata.read(jpeg).toJson()).getJSONObject("attributes")
                        assertTrue("Export retains $key for $format",exported.has(key))
                        if(key in listOf("Make","Model","DateTimeOriginal"))assertEquals(fields.getString(key),exported.getString(key))
                    }
                }
                assertEquals(1,exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,0))
                assertEquals(before,hash(input));assertTrue(NativeWorkReservations.detailRetryIdle())
                // Sequential cases never retain two large float spools.
                output.delete();jpeg.delete();input.delete()
            }
        }finally{root.deleteRecursively()}
    }
}
