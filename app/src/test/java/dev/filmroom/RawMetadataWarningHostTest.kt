package dev.filmroom

import android.app.Application
import dev.filmroom.codec.RawMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class RawMetadataWarningHostTest {
    @org.junit.Before fun chosenChineseFixture(){dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")}
    private fun parse(codes:String)=RawMetadata.parse(JSONObject("""{"ok":true,"schemaVersion":1,"format":"DNG","width":8,"height":8,"outputOrientation":1,"orientationApplied":true,"outputBytes":768,"warnings":[$codes]}"""))
    @Test fun cameraCurveWarningIsNotMislabelledLensCorrection() {
        val p=parse("""{"code":"DNG_CAMERA_TONE_CURVE_NOT_APPLIED","message":"PRIVATE FILE PATH"}""")
        assertFalse(p.optionalCorrectionWarning)
        assertTrue(p.cameraToneCurveWarning)
        assertTrue(p.displayWarning,p.displayWarning!!.contains("显示曲线"))
        assertFalse(p.displayWarning!!.contains("镜头"))
        assertFalse(p.displayWarning!!.contains("PRIVATE"))
    }
    @Test fun warningsAreAllowlistedAndCanCoexist() {
        val p=parse("""{"code":"DNG_CAMERA_TONE_CURVE_NOT_APPLIED"},{"code":"DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED"}""")
        assertTrue(p.optionalCorrectionWarning && p.cameraToneCurveWarning)
        assertTrue(p.displayWarning,p.displayWarning!!.contains("镜头"))
        assertNull(parse("""{"code":"UNKNOWN","message":"PRIVATE"}""").displayWarning)
    }
    @Test fun hsvWarningHasItsOwnTypedFlagAndDoesNotInventToneOrLensWarnings() {
        val p=parse("""{"code":"DNG_CAMERA_HSV_LOOK_NOT_APPLIED","message":"PRIVATE content://photo GPS"}""")
        assertTrue(p.cameraHsvLookWarning);assertFalse(p.cameraToneCurveWarning);assertFalse(p.optionalCorrectionWarning)
        assertTrue(p.displayWarning,p.displayWarning!!.contains("色相／饱和度校正表"));assertTrue(p.displayWarning,p.displayWarning!!.contains("不复刻相机直出"))
        assertFalse(p.displayWarning!!.contains("显示曲线"));assertFalse(p.displayWarning!!.contains("镜头"));assertFalse(p.displayWarning!!.contains("PRIVATE"))
        assertTrue(p.displayWarning,p.displayWarning!!.contains("颜色可能与相机相册不同"));assertFalse(p.displayWarning!!.contains("色彩风格表"))
    }
    @Test fun toneHsvAndLensWarningsRemainDistinctAndDeduplicate() {
        val p=parse("""{"code":"DNG_CAMERA_TONE_CURVE_NOT_APPLIED"},{"code":"DNG_CAMERA_HSV_LOOK_NOT_APPLIED"},{"code":"DNG_CAMERA_HSV_LOOK_NOT_APPLIED"},{"code":"DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED"}""")
        assertTrue(p.cameraHsvLookWarning && p.cameraToneCurveWarning && p.optionalCorrectionWarning)
        assertEquals(1,Regex("原始数据导入").findAll(p.displayWarning!!).count())
        assertEquals(1,Regex("色相／饱和度校正表").findAll(p.displayWarning!!).count())
        assertTrue(p.displayWarning,p.displayWarning!!.contains("显示曲线"));assertTrue(p.displayWarning,p.displayWarning!!.contains("镜头"))
    }
    @Test fun oldMetadataAndMalformedOrUnknownWarningEntriesAreBackwardCompatible() {
        val old=RawMetadata(8,8,768,false)
        assertFalse(old.cameraHsvLookWarning);assertFalse(old.cameraToneCurveWarning);assertNull(old.displayWarning)
        assertFalse(old.embeddedLensDistortionApplied);assertFalse(old.embeddedLensDistortionNotApplied)
        val p=parse("""null,true,"DNG_CAMERA_HSV_LOOK_NOT_APPLIED",{"code":123},{"code":{"private":"secret"}},{"code":"DNG_CAMERA_HSV_LOOK_NOT_APPLIED_PRIVATE","message":"secret"}""")
        assertFalse(p.cameraHsvLookWarning);assertNull(p.displayWarning)
        val missing=JSONObject("""{"ok":true,"schemaVersion":1,"format":"DNG","width":8,"height":8,"outputOrientation":1,"orientationApplied":true,"outputBytes":768}""")
        assertNull(RawMetadata.parse(missing).displayWarning)
    }

    @Test fun embeddedDistortionAppliedIsAnAllowlistedDeduplicatedInformationMessage() {
        val json=JSONObject("""{"ok":true,"schemaVersion":1,"format":"ARW","width":8,"height":8,"outputOrientation":1,"orientationApplied":true,"outputBytes":768,"warnings":[{"code":"EMBEDDED_LENS_DISTORTION_APPLIED","message":"PRIVATE lens serial GPS"},{"code":"EMBEDDED_LENS_DISTORTION_APPLIED"}]}""")
        for(format in listOf("DNG","ARW","RAF","ORF")) {
            val p=RawMetadata.parse(json.put("format",format))
            assertTrue(p.embeddedLensDistortionApplied)
            assertFalse(p.embeddedLensDistortionNotApplied)
            assertFalse(p.optionalCorrectionWarning)
            assertEquals(1,p.displayMessages.size)
            assertEquals("已应用内嵌镜头畸变校正。",p.displayWarning)
            val message=p.displayMessages.single()
            assertEquals("embedded_lens_distortion_applied",message.key)
            assertEquals(message,dev.filmroom.i18n.UiMessage.fromJson(message.toJson()))
        }
    }

    @Test fun unappliedEmbeddedDistortionIsDistinctFromDngOptionalAndBasicDevelopmentWarnings() {
        val p=parse("""{"code":"EMBEDDED_LENS_DISTORTION_NOT_APPLIED","message":"PRIVATE coefficients"},{"code":"EMBEDDED_LENS_DISTORTION_NOT_APPLIED"},{"code":"CAMERA_RAW_BASIC_DEVELOPMENT"},{"code":"DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED"}""")
        assertTrue(p.embeddedLensDistortionNotApplied)
        assertFalse(p.embeddedLensDistortionApplied)
        assertTrue(p.cameraBasicDevelopmentWarning && p.optionalCorrectionWarning)
        assertEquals(3,p.displayMessages.size)
        assertTrue(p.displayWarning!!.contains("检测到内嵌镜头畸变参数但未应用，可能保留畸变。"))
        assertFalse(p.displayWarning!!.contains("PRIVATE"))
        val malformed=parse("""{"code":"EMBEDDED_LENS_DISTORTION_APPLIED_PRIVATE"},{"code":"embedded_lens_distortion_applied"},{"code":"EMBEDDED_LENS_DISTORTION_NOT_APPLIED_PRIVATE"}""")
        assertFalse(malformed.embeddedLensDistortionApplied)
        assertFalse(malformed.embeddedLensDistortionNotApplied)
        assertNull(malformed.displayWarning)
    }

    @Test fun embeddedDistortionMessagesPersistAsKeysAndRerenderAfterLocaleChange() {
        val applied=parse("""{"code":"EMBEDDED_LENS_DISTORTION_APPLIED"}""").displayMessages.single()
        val skipped=parse("""{"code":"EMBEDDED_LENS_DISTORTION_NOT_APPLIED"}""").displayMessages.single()
        val restoredApplied=dev.filmroom.i18n.UiMessage.fromJson(applied.toJson())!!
        val restoredSkipped=dev.filmroom.i18n.UiMessage.fromJson(skipped.toJson())!!
        dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"en")
        assertEquals("Applied embedded lens distortion correction.",restoredApplied.render())
        assertEquals("Embedded lens distortion parameters were detected but not applied. Distortion may remain.",restoredSkipped.render())
        dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")
        assertEquals("已应用内嵌镜头畸变校正。",restoredApplied.render())
        assertEquals("检测到内嵌镜头畸变参数但未应用，可能保留畸变。",restoredSkipped.render())
    }

    @Test fun nativeCameraFormatAndBasicDevelopmentWarningAreStrictlyTyped() {
        val json=JSONObject("""{"ok":true,"schemaVersion":1,"format":"NEF","width":8,"height":8,"outputOrientation":1,"orientationApplied":true,"outputBytes":768,"warnings":[{"code":"CAMERA_RAW_BASIC_DEVELOPMENT","message":"PRIVATE camera serial GPS"},{"code":"CAMERA_RAW_BASIC_DEVELOPMENT"}]}""")
        for(format in dev.filmroom.codec.RawFormat.entries) {
            val p=RawMetadata.parse(json.put("format",format.name))
            assertEquals(format,p.format);assertTrue(p.cameraBasicDevelopmentWarning)
            assertEquals(1,p.displayMessages.size)
            assertFalse(p.displayWarning!!.contains("PRIVATE"))
            assertTrue(p.displayWarning!!.contains("基础开发"))
            assertEquals(p.displayMessages.single(),dev.filmroom.i18n.UiMessage.fromJson(p.displayMessages.single().toJson()))
        }
        assertEquals(dev.filmroom.codec.RawFormat.DNG,RawMetadata(8,8,768,false).format)
    }

    @Test fun cameraFailureCodesAreAllowlistedAndMessagesPersistWithoutNativeText() {
        for((code,resource) in listOf(
            "UNSUPPORTED_CAMERA" to dev.filmroom.R.string.camera_raw_profile_unsupported,
            "UNSUPPORTED_CAMERA_RAW_VARIANT" to dev.filmroom.R.string.camera_raw_variant_unsupported)) {
            assertEquals(code,dev.filmroom.diagnostics.CodecErrorCode.fromId(code).name)
            val message=dev.filmroom.i18n.UiMessage.of(resource)
            assertNotNull(message.render())
            assertEquals(message,dev.filmroom.i18n.UiMessage.fromJson(message.toJson()))
        }
        assertEquals(dev.filmroom.diagnostics.CodecErrorCode.UNKNOWN,dev.filmroom.diagnostics.CodecErrorCode.fromId("UNSUPPORTED_CAMERA_PRIVATE"))
    }

}
