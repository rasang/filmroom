package dev.filmroom.io

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** EXIF is read independently of sensor development; no embedded JPEG pixels are imported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class FujifilmRafMetadataHostTest {
    @Test fun publicRafPreservesCaptureAndLensFieldsThroughSnapshot() {
        val root=System.getenv("FILMROOM_RAF_FIXTURES")
        assumeTrue("Opt-in public CC0 RAF originals",root!=null)
        for((id,model) in listOf(1214 to "X-A1",1371 to "X-A3",1191 to "X-Pro1",864 to "X-T2",865 to "X-T2",1178 to "X-T20",6124 to "X-T5",7301 to "X100VI",7302 to "X100VI")) {
            val source=File(root,"$id.RAF");assertTrue(source.isFile)
            val before=source.length();val stamp=source.lastModified()
            val metadata=PhotoMetadata.read(source)
            val attributes=JSONObject(metadata.toJson()).getJSONObject("attributes")
            assertEquals("FUJIFILM",attributes.getString("Make"))
            assertEquals(model,attributes.getString("Model"))
            for(key in listOf("DateTimeOriginal","FNumber","ExposureTime","PhotographicSensitivity","FocalLength"))assertNotNull("$id: $key",attributes.optString(key).takeIf { it.isNotEmpty() })
            val restored=PhotoMetadata.fromJson(metadata.toJson())
            assertEquals(metadata.toJson(),restored.toJson())
            assertEquals(before,source.length());assertEquals(stamp,source.lastModified())
        }
    }
}
