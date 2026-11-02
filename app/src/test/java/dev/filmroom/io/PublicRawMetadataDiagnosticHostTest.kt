package dev.filmroom.io

import androidx.exifinterface.media.ExifInterface
import dev.filmroom.export.*
import dev.filmroom.model.EditorState
import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public sample diagnostic; tiny JPEG validates metadata transport, not RAW pixel development. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class PublicRawMetadataDiagnosticHostTest {
    @Test fun inspectOriginalSnapshotPersistedQueueAndJpegReadback() {
        assumeTrue("Opt-in requires six local public RAW originals",System.getenv("FILMROOM_PUBLIC_RAW_METADATA")=="1")
        val sources=listOf("750_D800.NEF","6887_Z_f.NEF","131_EOS_7D.CR2","130_EOS_7D.CR2","851_ILCE-7M2.ARW")
            .map { File("/workspace/shared/filmroom-camera-raw-samples",it) }+
            File("/workspace/shared/filmroom-raw-variants-development/samples/8846_ILCE-7M5.ARW")
        val out=File("build/public-raw-metadata-diagnostic").apply{mkdirs()}
        val rows=JSONArray()
        val keys=listOf("Make","Model","LensMake","LensModel","LensSpecification","FNumber","ExposureTime","PhotographicSensitivity","DateTimeOriginal","OffsetTimeOriginal","SubSecTimeOriginal","DateTime","Orientation")
        val stamp=Instant.parse("2026-11-02T03:00:00.123Z").toEpochMilli()
        sources.forEach { source ->
            assertTrue(source.path,source.isFile)
            val original=ExifInterface(source.absolutePath)
            val raw=JSONObject();keys.forEach{key->raw.put(key,original.getAttribute(key) ?: JSONObject.NULL)}
            val metadata=PhotoMetadata.read(source)
            val captured=JSONObject(metadata.toJson()).getJSONObject("attributes")
            if(source.name=="750_D800.NEF") {
                assertEquals("50",captured.getString("PhotographicSensitivity"))
                assertEquals("+02:00",captured.getString("OffsetTimeOriginal"))
                assertEquals("240/10,700/10,28/10,28/10",captured.getString("LensSpecification"))
                assertFalse("Focal ranges cannot identify a precise lens model",captured.has("LensModel"))
            }
            if(source.name=="6887_Z_f.NEF") {
                assertEquals("250",captured.getString("PhotographicSensitivity"))
                assertEquals(original.getAttribute("LensModel"),captured.getString("LensModel"))
                assertEquals(original.getAttribute("LensSpecification"),captured.getString("LensSpecification"))
            }
            assertNull("Known public originals must not have placeholder metadata warnings",metadata.warning)
            val omitted=JSONObject()
            val schema=PhotoMetadata::class.java.getDeclaredField("SCHEMA").apply{isAccessible=true}.get(null) as Map<*,*>
            schema.keys.filterIsInstance<String>().forEach { key ->
                original.getAttribute(key)?.let { value ->
                    if(PhotoMetadata.fromAttributes(mapOf(key to value)).warning!=null) omitted.put(key,value)
                }
            }
            val jobRoot=File(out,source.name+"-queue").apply{mkdirs()};val store=ExportJobStore(jobRoot)
            val job=ExportJob(ExportJobStore.newId(),1,EditorState(),32,24,ExportFormat.JPEG,95,state=ExportJobState.PAUSED,photoMetadata=metadata.toJson())
            store.directory(job.id).mkdirs();store.update(job)
            val reopened=ExportJobStore(jobRoot).jobs.value.single{it.id==job.id}
            val durable=PhotoMetadata.fromJson(reopened.photoMetadata!!)
            val imageClass=Class.forName("java.awt.image.BufferedImage")
            val image=imageClass.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(32,24,1)
            val jpeg=File(out,source.name+".jpg")
            assertEquals(true,Class.forName("javax.imageio.ImageIO").getMethod("write",Class.forName("java.awt.image.RenderedImage"),String::class.java,File::class.java).invoke(null,image,"jpeg",jpeg))
            PhotoMetadataContainer.inject(jpeg,ExportFormat.JPEG,durable.exifBytes(32,24,"sRGB",stamp))
            val read=ExifInterface(jpeg.absolutePath);val final=JSONObject();keys.forEach{key->final.put(key,read.getAttribute(key) ?: JSONObject.NULL)}
            rows.put(JSONObject().put("sample",source.name).put("androidxOriginal",raw).put("rejectedIndividually",omitted).put("snapshot",JSONObject(metadata.toJson())).put("persisted",JSONObject(durable.toJson())).put("finalJpeg",final))
            assertEquals("Snapshot survives durable queue",metadata.toJson(),durable.toJson())
            assertEquals(1,read.getAttributeInt("Orientation",0));assertEquals("2026:11:02 03:00:00",read.getAttribute("DateTime"))
        }
        File(out,"readback.json").writeText(rows.toString(2))
    }
}
