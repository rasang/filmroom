package dev.filmroom.engine

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real org.json objects recreated across the Kotlin/native JSON boundary, not identical references. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class DetailMeteringIdentityHostTest {
    private fun parameters()=JSONObject().put("autoExposure",true).put("filmExposureEv",0.5)
        .put("previewMetering",PreviewMeteringResult(
            "snapshot:24:1000","oriented-original-v1:image/png:2x1:linear=false:snapshot=snapshot:24:1000:meter-grid:image-decoder-srgb-white-v1:2x1",
            "linear-srgb-matrix-v1:assets","source-owner","partial",2,1,0.75
        ).json())

    @Test fun independentlyParsedNestedMeteringIdentityIsEquivalentAcrossNumericTypesAndKeyOrder() {
        val expected=parameters();val original=expected.getJSONObject("previewMetering")
        val reversed=JSONObject();original.keys().asSequence().toList().reversed().forEach{reversed.put(it,original.get(it))}
        reversed.put("width",2L).put("height",1.0)
        val actual=JSONObject(expected.toString()).put("previewMetering",reversed)
        assertNotSame(expected,actual);assertNotSame(original,actual.getJSONObject("previewMetering"))
        assertTrue(jsonEquivalent(expected,actual));assertTrue(jsonEquivalent(actual,expected))
        assertTrue(jsonEquivalent(expected,JSONObject(expected.toString())))
    }

    @Test fun nestedExposureSourceOwnerMappingDataMethodAndAlgorithmChangesAreRejected() {
        val expected=parameters()
        val changes=mapOf<String,Any>(
            "ev" to 1.75,"sourceRevision" to "other-snapshot","owner" to "other-owner",
            "mappingRevision" to "rotated-or-cropped","dataRevision" to "other-assets",
            "method" to "average","algorithm" to "other-algorithm","width" to 3
        )
        changes.forEach{(key,value)->
            val actual=JSONObject(expected.toString());actual.getJSONObject("previewMetering").put(key,value)
            assertFalse("Changed $key was accepted",jsonEquivalent(expected,actual))
        }
        val compensation=JSONObject(expected.toString()).put("filmExposureEv",1.5)
        assertFalse("User compensation is distinct request identity",jsonEquivalent(expected,compensation))
    }

    @Test fun nestedMissingExtraAndWrongTypeFieldsAreRejected() {
        val expected=parameters()
        val missing=JSONObject(expected.toString());missing.getJSONObject("previewMetering").remove("owner")
        val extra=JSONObject(expected.toString());extra.getJSONObject("previewMetering").put("crop","roi")
        val stringEv=JSONObject(expected.toString());stringEv.getJSONObject("previewMetering").put("ev","0.75")
        val stringObject=JSONObject(expected.toString()).put("previewMetering",expected.getJSONObject("previewMetering").toString())
        for(actual in listOf(missing,extra,stringEv,stringObject))assertFalse(jsonEquivalent(expected,actual))
    }
}
