package dev.filmroom.engine

import org.json.JSONObject

/** ABI negotiation is explicit. A stale library is unavailable, never a cosmetic CPU substitute. */
internal object PreviewNativeContract {
    fun supportsBitmap(capabilities:JSONObject):Boolean {
        val revision=when(val raw=capabilities.opt("nativeAbiRevision")){is Int->raw.toLong();is Long->raw;else->return false}
        return revision>=2 && capabilities.opt("bitmapPreview")==true
    }
    fun outputMatches(result:JSONObject,width:Int,height:Int):Boolean =
        result.opt("ok")==true && result.opt("outputValid")==true &&
            result.opt("width")==width && result.opt("height")==height &&
            result.opt("outputEncoding")=="srgb-rgba8"
}
