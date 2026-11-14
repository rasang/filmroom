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

    @Test fun lensfunCorrectionIsAllowlistedDistinctAndLocalized() {
        val p=parse("""{"code":"LENSFUN_DISTORTION_APPLIED","message":"PRIVATE lens serial GPS"},{"code":"LENSFUN_DISTORTION_APPLIED"}""")
        assertTrue(p.lensfunDistortionApplied)
        assertFalse(p.embeddedLensDistortionApplied)
        assertFalse(p.embeddedLensDistortionNotApplied)
        assertEquals(1,p.displayMessages.size)
        assertEquals("已应用匹配镜头配置的畸变校正。",p.displayWarning)
        val message=p.displayMessages.single()
        assertEquals("lensfun_distortion_applied",message.key)
        val restored=dev.filmroom.i18n.UiMessage.fromJson(message.toJson())!!
        val translations=mapOf(
            "zh" to "已应用匹配镜头配置的畸变校正。",
            "en" to "Applied distortion correction from the matched lens profile.",
            "ja" to "一致するレンズプロファイルの歪曲補正を適用しました。",
            "ko" to "일치하는 렌즈 프로파일의 왜곡 보정을 적용했습니다.")
        try {
            for((language,expected) in translations) {
                dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),language)
                assertEquals(expected,restored.render())
                assertFalse(restored.render()!!.contains("PRIVATE"))
            }
        } finally { chosenChineseFixture() }
        val unknown=parse("""{"code":"LENSFUN_DISTORTION_APPLIED_PRIVATE"},{"code":"lensfun_distortion_applied"}""")
        assertFalse(unknown.lensfunDistortionApplied)
        assertNull(unknown.displayWarning)
    }

    @Test fun developmentPolicyIsBoundedAndMissingLegacyValuesCannotCertifyCache() {
        val json=JSONObject("""{"ok":true,"schemaVersion":1,"format":"CR3","width":8,"height":8,"outputOrientation":1,"orientationApplied":true,"outputBytes":768}""")
        assertNull(RawMetadata.parse(json).developmentPolicy)
        val policy="camera-matrix-linear-srgb-lensfun-v1"
        assertEquals(policy,RawMetadata.parse(json.put("developmentPolicy",policy)).developmentPolicy)
        val legacyDng="rawler0.7.2 ColorMatrix(D65)+AsShotNeutral; not full Adobe/Camera2 processing"
        assertEquals(legacyDng,RawMetadata.parse(json.put("developmentPolicy",legacyDng)).developmentPolicy)
        for(value in listOf<Any>(true,123,"", " ", "invalid\npolicy", "x".repeat(257))) {
            assertNull(RawMetadata.parse(json.put("developmentPolicy",value)).developmentPolicy)
        }
        assertNull(RawMetadata(8,8,768,false).developmentPolicy)
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

    private val opticsCodes=listOf(
        "EMBEDDED_LENS_VIGNETTING_APPLIED", "EMBEDDED_LENS_VIGNETTING_NOT_APPLIED",
        "EMBEDDED_LENS_TCA_APPLIED", "EMBEDDED_LENS_TCA_NOT_APPLIED",
        "LENSFUN_VIGNETTING_APPLIED", "LENSFUN_VIGNETTING_NOT_APPLIED",
        "LENSFUN_TCA_APPLIED", "LENSFUN_TCA_NOT_APPLIED")
    private fun opticsFlags(p:RawMetadata)=listOf(
        p.embeddedLensVignettingApplied,p.embeddedLensVignettingNotApplied,
        p.embeddedLensTcaApplied,p.embeddedLensTcaNotApplied,
        p.lensfunVignettingApplied,p.lensfunVignettingNotApplied,
        p.lensfunTcaApplied,p.lensfunTcaNotApplied)

    @Test fun allEightOpticsCodesAreIndependentExactAllowlistedAndDeduplicated() {
        for((index,code) in opticsCodes.withIndex()) {
            val p=parse("""{"code":"$code","message":"PRIVATE content://source lens serial GPS"},{"code":"$code"},{"code":"${code}_PRIVATE"}""")
            assertEquals(code,opticsCodes.indices.map{it==index},opticsFlags(p))
            assertFalse(p.embeddedLensDistortionApplied || p.embeddedLensDistortionNotApplied || p.lensfunDistortionApplied)
            assertFalse(p.optionalCorrectionWarning || p.cameraToneCurveWarning || p.cameraHsvLookWarning)
            assertEquals(code,1,p.displayMessages.size)
            val message=p.displayMessages.single()
            assertEquals(code.lowercase(java.util.Locale.ROOT),message.key)
            assertEquals(emptyList<String>(),message.args)
            assertFalse(message.toJson().toString().contains("PRIVATE"))
            assertFalse(p.displayWarning!!.contains("PRIVATE"))
            assertEquals(message,dev.filmroom.i18n.UiMessage.fromJson(message.toJson()))
        }
    }

    @Test fun missingUnknownAndMalformedOpticsCodesDoNotInventCorrections() {
        val entries=opticsCodes.flatMap { code ->
            listOf("""{"code":"${code}_PRIVATE"}""","""{"code":"${code.lowercase(java.util.Locale.ROOT)}"}""","""{"code":" $code"}""")
        }.joinToString(",")+""",null,false,"EMBEDDED_LENS_TCA_APPLIED",{"code":42},{"message":"LENSFUN_TCA_APPLIED"}"""
        for(p in listOf(parse(entries),parse(""))) {
            assertTrue(opticsFlags(p).none{it})
            assertNull(p.displayWarning)
        }
        // All twelve previous positional parameters retain their original meaning.
        val legacy=RawMetadata(8,8,768,false,true,false,dev.filmroom.codec.RawFormat.ARW,true,true,false,false,"camera-matrix-linear-srgb-v1")
        assertTrue(legacy.cameraToneCurveWarning && legacy.cameraBasicDevelopmentWarning && legacy.embeddedLensDistortionApplied)
        assertEquals("camera-matrix-linear-srgb-v1",legacy.developmentPolicy)
        assertTrue(opticsFlags(legacy).none{it})
    }

    @Test fun independentOpticsSuccessAndFailureCanCoexistWithoutReplacingDistortion() {
        val p=parse("""{"code":"EMBEDDED_LENS_DISTORTION_APPLIED"},{"code":"EMBEDDED_LENS_VIGNETTING_APPLIED"},{"code":"EMBEDDED_LENS_TCA_NOT_APPLIED"},{"code":"LENSFUN_TCA_APPLIED"}""")
        assertTrue(p.embeddedLensDistortionApplied && p.embeddedLensVignettingApplied && p.embeddedLensTcaNotApplied && p.lensfunTcaApplied)
        assertFalse(p.embeddedLensTcaApplied || p.embeddedLensVignettingNotApplied || p.lensfunVignettingApplied || p.optionalCorrectionWarning)
        assertEquals(listOf("embedded_lens_distortion_applied","embedded_lens_vignetting_applied","embedded_lens_tca_not_applied","lensfun_tca_applied"),p.displayMessages.map{it.key})
    }

    @Test fun allOpticsMessagesRestoreAsKeysAndRerenderInFourLanguages() {
        val expected=mapOf(
            "zh" to listOf("已应用内嵌镜头暗角校正。","检测到内嵌镜头暗角参数，但无法应用校正。","已应用内嵌镜头色差校正。","检测到内嵌镜头色差参数，但无法应用校正。","已应用匹配镜头配置的暗角校正。","已匹配镜头配置，但无法应用暗角校正。","已应用匹配镜头配置的色差校正。","已匹配镜头配置，但无法应用色差校正。"),
            "en" to listOf("Applied embedded lens vignetting correction.","Embedded lens vignetting parameters were detected, but correction could not be applied.","Applied embedded lens chromatic aberration correction.","Embedded lens chromatic aberration parameters were detected, but correction could not be applied.","Applied vignetting correction from the matched lens profile.","A matching lens profile was found, but vignetting correction could not be applied.","Applied chromatic aberration correction from the matched lens profile.","A matching lens profile was found, but chromatic aberration correction could not be applied."),
            "ja" to listOf("埋め込みのレンズ周辺光量補正を適用しました。","埋め込みのレンズ周辺光量補正パラメーターを検出しましたが、補正を適用できませんでした。","埋め込みのレンズ色収差補正を適用しました。","埋め込みのレンズ色収差補正パラメーターを検出しましたが、補正を適用できませんでした。","一致するレンズプロファイルの周辺光量補正を適用しました。","一致するレンズプロファイルが見つかりましたが、周辺光量補正を適用できませんでした。","一致するレンズプロファイルの色収差補正を適用しました。","一致するレンズプロファイルが見つかりましたが、色収差補正を適用できませんでした。"),
            "ko" to listOf("파일에 포함된 렌즈 비네팅 보정을 적용했습니다.","파일에 포함된 렌즈 비네팅 보정 매개변수를 감지했지만 보정을 적용할 수 없었습니다.","파일에 포함된 렌즈 색수차 보정을 적용했습니다.","파일에 포함된 렌즈 색수차 보정 매개변수를 감지했지만 보정을 적용할 수 없었습니다.","일치하는 렌즈 프로파일의 비네팅 보정을 적용했습니다.","일치하는 렌즈 프로파일을 찾았지만 비네팅 보정을 적용할 수 없었습니다.","일치하는 렌즈 프로파일의 색수차 보정을 적용했습니다.","일치하는 렌즈 프로파일을 찾았지만 색수차 보정을 적용할 수 없었습니다."))
        val restored=opticsCodes.map { code ->
            val message=parse("""{"code":"$code","message":"PRIVATE raw path"}""").displayMessages.single()
            dev.filmroom.i18n.UiMessage.fromJson(message.toJson())!!
        }
        try {
            for((language,translations) in expected) {
                dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),language)
                assertEquals(language,translations,restored.map{it.render()})
            }
        } finally { chosenChineseFixture() }
        assertEquals(expected.getValue("zh"),restored.map{it.render()})
    }

    @Test fun opticsV2PoliciesRemainExactNativeCacheIdentities() {
        val json=JSONObject("""{"ok":true,"schemaVersion":1,"format":"ARW","width":8,"height":8,"outputOrientation":1,"orientationApplied":true,"outputBytes":768}""")
        for(policy in listOf("camera-matrix-linear-srgb-embedded-v2","raf-xtrans-linear-srgb-embedded-v2","camera-matrix-linear-srgb-lensfun-v2")) {
            val p=RawMetadata.parse(json.put("developmentPolicy",policy))
            assertEquals(policy,p.developmentPolicy)
            assertNull(p.displayWarning)
            assertTrue(opticsFlags(p).none{it})
        }
    }

}
