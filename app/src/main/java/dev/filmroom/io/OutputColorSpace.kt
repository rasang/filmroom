package dev.filmroom.io

import java.io.IOException
import kotlin.math.pow
import kotlin.math.roundToInt

/** File-boundary encoding only. Engine output must already use these primaries. */
enum class OutputColorSpace(val id:String,val iccAsset:String,val profileSha256:String) {
    SRGB("sRGB","srgb.icc","5dc910f30a690a4858c212e701c90d0224933dec5363044aa9e6f9b3de35f1b1"),
    DISPLAY_P3("Display P3","display-p3.icc","eb86621701124eec88381738870d7562031423c11b6f1c1cbd6b8db72ca68748"),
    ADOBE_RGB("Adobe RGB (1998)","adobe-rgb-1998.icc","f390459ab91ba6544934c040cc937b02cb6ba5b7b351f98ae617eeb0919bc007");
    fun validateProfile(bytes:ByteArray) {
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        if(digest!=profileSha256)throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9f7eee77bb73))
    }
    fun encode8(value:Float):Int {
        if(!value.isFinite())throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a5d702fd2654))
        val v=value.toDouble().coerceIn(0.0,1.0)
        val encoded=if(this==ADOBE_RGB)v.pow(256.0/563.0) else if(v<=0.0031308)12.92*v else 1.055*v.pow(1.0/2.4)-0.055
        return (encoded*255.0).roundToInt().coerceIn(0,255)
    }
    companion object {
        fun parse(id:String):OutputColorSpace = entries.firstOrNull{it.id==id}
            ?: throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7d7e6c47e8d9))
    }
}
