package dev.filmroom.codec

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual generated Linux JNI snapshots, not CLI JSON and not Android execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
class JniContractTest {
    private fun fixture(name:String)=JSONObject(javaClass.getResourceAsStream("/codec-jni/jni-$name.json")!!.bufferedReader().use{it.readText()})
    @Test fun probeAndDecodeUseOrientedCropRootDimensions(){
        for(name in listOf("probe-success","decode-success")){
            val json=fixture(name);val data=RawMetadata.parse(json)
            assertEquals(374,data.width);assertEquals(56,data.height);assertEquals(251328L,data.bytes)
            assertEquals(64,json.getInt("encodedWidth"));assertEquals(384,json.getInt("encodedHeight"));assertEquals(8,json.getInt("sourceOrientation"))
            assertEquals(1,json.getInt("outputOrientation"));assertFalse(json.has("probe"))
        }
    }
    @Test fun encoderAndErrorContractsAreRootFields(){
        val tiff=fixture("encode-TIFF16-success");assertTrue(tiff.getBoolean("ok"));assertEquals(16,tiff.getInt("bitDepth"));assertEquals("image/tiff",tiff.getString("mimeType"));assertEquals(1,tiff.getInt("outputOrientation"))
        val jpeg=fixture("encode-JPEG-success");assertEquals(8,jpeg.getInt("bitDepth"));assertEquals("image/jpeg",jpeg.getString("mimeType"))
        val error=fixture("error");assertFalse(error.getBoolean("ok"));assertTrue(error.has("code"));assertFalse(error.has("error"))
    }
    @Test fun rejectsCliShapeOrInconsistentOrientationAndByteCount(){
        val good=fixture("decode-success")
        for(bad in listOf(JSONObject().put("ok",true).put("probe",good),JSONObject(good.toString()).put("outputBytes",1),JSONObject(good.toString()).put("outputOrientation",8))){
            assertThrows(dev.filmroom.io.PhotoException::class.java){RawMetadata.parse(bad)}
        }
    }
}
