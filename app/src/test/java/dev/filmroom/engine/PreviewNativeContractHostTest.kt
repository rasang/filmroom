package dev.filmroom.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PreviewNativeContractHostTest {
    @Test fun capabilitiesMustExplicitlyAdvertiseNewBitmapAbi() {
        assertTrue(PreviewNativeContract.supportsBitmap(JSONObject().put("nativeAbiRevision",2).put("bitmapPreview",true)))
        for(c in listOf(JSONObject(),JSONObject().put("nativeAbiRevision",1).put("bitmapPreview",true),JSONObject().put("nativeAbiRevision",2).put("bitmapPreview","true")))assertFalse(PreviewNativeContract.supportsBitmap(c))
    }
    @Test fun incompleteOrWrongShapeOutputIsNeverPublished() {
        val valid=JSONObject().put("ok",true).put("outputValid",true).put("width",512).put("height",384).put("outputEncoding","srgb-rgba8")
        assertTrue(PreviewNativeContract.outputMatches(valid,512,384))
        for((key,value) in listOf("ok" to false,"outputValid" to false,"width" to 511,"height" to "384","outputEncoding" to "linear-rgb-f32")) {
            assertFalse(PreviewNativeContract.outputMatches(JSONObject(valid.toString()).put(key,value),512,384))
        }
    }
}
