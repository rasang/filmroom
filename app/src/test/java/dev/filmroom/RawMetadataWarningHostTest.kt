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
        val p=parse("""null,true,"DNG_CAMERA_HSV_LOOK_NOT_APPLIED",{"code":123},{"code":{"private":"secret"}},{"code":"DNG_CAMERA_HSV_LOOK_NOT_APPLIED_PRIVATE","message":"secret"}""")
        assertFalse(p.cameraHsvLookWarning);assertNull(p.displayWarning)
        val missing=JSONObject("""{"ok":true,"schemaVersion":1,"format":"DNG","width":8,"height":8,"outputOrientation":1,"orientationApplied":true,"outputBytes":768}""")
        assertNull(RawMetadata.parse(missing).displayWarning)
    }

}
