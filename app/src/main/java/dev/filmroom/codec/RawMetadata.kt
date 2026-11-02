package dev.filmroom.codec

import org.json.JSONObject
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.PhotoException

/** Native-validated identities, not file-extension or provider-MIME guesses. */
enum class RawFormat(val mimeType:String) {
    DNG("image/x-adobe-dng"), NEF("image/x-nikon-nef"), CR2("image/x-canon-cr2"), ARW("image/x-sony-arw");
}

/** Exact native JNI contract: unlike CLI output, Probe fields are at the root. */
data class RawMetadata(val width:Int,val height:Int,val bytes:Long,val optionalCorrectionWarning:Boolean,val cameraToneCurveWarning:Boolean=false,val cameraHsvLookWarning:Boolean=false,val format:RawFormat=RawFormat.DNG,val cameraBasicDevelopmentWarning:Boolean=false) {
    val displayMessages:List<dev.filmroom.i18n.UiMessage> get() = listOfNotNull(
        if(cameraBasicDevelopmentWarning) dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.camera_raw_basic_development) else null,
        if(optionalCorrectionWarning) dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_62f3e125a3ce) else null,
        if(cameraToneCurveWarning || cameraHsvLookWarning) dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_95665116d521) else null,
        if(cameraToneCurveWarning) dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_d2d161102e41) else null,
        if(cameraHsvLookWarning) dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_99981a4927ac) else null
    )
    val displayWarning:String? get()=displayMessages.mapNotNull{it.render()}.takeIf{it.isNotEmpty()}?.joinToString("\n")
    companion object {
        fun parse(json:JSONObject):RawMetadata {
            try{
                require(json.getBoolean("ok") && json.getInt("schemaVersion")==1)
                require(json.getInt("outputOrientation")==1 && json.getBoolean("orientationApplied"))
                val format=RawFormat.valueOf(json.getString("format"))
                val w=json.getInt("width");val h=json.getInt("height")
                require(w.toLong()*h in 1..52_000_000L)
                val bytes=LinearRgb.byteCount(w,h)
                require(json.getLong("outputBytes")==bytes)
                val warnings=json.optJSONArray("warnings")
                // Retain only exact known technical codes. Native messages and unknown strings
                // are never displayed or persisted, even when restoring older cached RAW data.
                val codes=buildSet {
                    for(index in 0 until (warnings?.length() ?: 0)) {
                        val code=warnings?.optJSONObject(index)?.opt("code") as? String ?: continue
                        RawDevelopmentWarning.entries.firstOrNull { it.code==code }?.let(::add)
                    }
                }
                return RawMetadata(w,h,bytes,
                    RawDevelopmentWarning.OPTIONAL_LENS_CORRECTIONS in codes,
                    RawDevelopmentWarning.CAMERA_TONE_CURVE in codes,
                    RawDevelopmentWarning.CAMERA_HSV_LOOK in codes,format,
                    RawDevelopmentWarning.CAMERA_BASIC_DEVELOPMENT in codes)
            }catch(e:Exception){throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_5f37317ec18e),e)}
        }
    }
}

private enum class RawDevelopmentWarning(val code:String) {
    CAMERA_BASIC_DEVELOPMENT("CAMERA_RAW_BASIC_DEVELOPMENT"),
    OPTIONAL_LENS_CORRECTIONS("DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED"),
    CAMERA_TONE_CURVE("DNG_CAMERA_TONE_CURVE_NOT_APPLIED"),
    // Legacy wire name: ProfileHueSatMap is a color-correction mapping, not ProfileLookTable.
    CAMERA_HSV_LOOK("DNG_CAMERA_HSV_LOOK_NOT_APPLIED"),
}
