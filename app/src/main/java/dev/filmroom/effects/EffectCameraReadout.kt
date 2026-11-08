package dev.filmroom.effects

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow
import kotlin.math.round

/** Capture facts, not the simulated film exposure. Null values leave their finder window blank. */
data class EffectCameraReadout(val shutter:String?=null,val aperture:String?=null) {
    companion object {
        private val keys=listOf("ExposureTime","ShutterSpeedValue","FNumber","ApertureValue")

        /** Prefer the export's frozen capture metadata; original URI is a missing-field fallback only. */
        fun read(resolver:ContentResolver,sourceUri:String?,snapshot:String?):EffectCameraReadout {
            val saved=fromSnapshot(snapshot)
            if(saved.shutter!=null && saved.aperture!=null)return saved
            val original=if(sourceUri.isNullOrBlank())EffectCameraReadout()else try {
                resolver.openInputStream(Uri.parse(sourceUri))?.use { stream ->
                    val exif=ExifInterface(stream)
                    fromAttributes(keys.mapNotNull{k->exif.getAttribute(k)?.let{k to it}}.toMap())
                } ?: EffectCameraReadout()
            } catch(_:Exception){EffectCameraReadout()}
            return EffectCameraReadout(saved.shutter ?: original.shutter,saved.aperture ?: original.aperture)
        }

        internal fun fromSnapshot(snapshot:String?):EffectCameraReadout=try {
            if(snapshot==null || snapshot.length>128*1024)EffectCameraReadout() else {
                val attrs=JSONObject(snapshot).optJSONObject("attributes")
                fromAttributes(keys.mapNotNull { k->attrs?.optString(k)?.takeIf{it.isNotBlank()}?.let{k to it} }.toMap())
            }
        } catch(_:Exception){EffectCameraReadout()}

        internal fun fromAttributes(attributes:Map<String,String>):EffectCameraReadout {
            val seconds=number(attributes["ExposureTime"])?.takeIf{it in 1e-9..86400.0}
                ?: number(attributes["ShutterSpeedValue"])?.takeIf{it in -16.0..30.0}?.let{2.0.pow(-it)}
            val fNumber=number(attributes["FNumber"])?.takeIf{it in 0.1..1024.0}
                ?: number(attributes["ApertureValue"])?.takeIf{it in -6.0..20.0}?.let{2.0.pow(it/2.0)}
            return EffectCameraReadout(seconds?.let(::shutterText),fNumber?.let{decimal(it,2)})
        }
        private fun number(value:String?):Double?=runCatching {
            if(value==null || value.length>64)return null
            val parts=value.trim().split('/')
            val n=when(parts.size){1->parts[0].toDouble();2->{val d=parts[1].toDouble();if(d<=0.0)return null;parts[0].toDouble()/d};else->return null}
            n.takeIf{it.isFinite()}
        }.getOrNull()
        private fun shutterText(seconds:Double):String {
            if(seconds>=1.0)return decimal(seconds,2)+"\""
            val denominator=1.0/seconds
            // Normal EXIF rationals may decode just off an integer. Do not snap arbitrary
            // exposures to a stock camera setting or claim an automatic/manual camera mode.
            val near=round(denominator)
            return if(kotlin.math.abs(denominator-near)<0.001)near.toLong().toString() else decimal(denominator,1)
        }
        private fun decimal(n:Double,scale:Int)=BigDecimal.valueOf(n).setScale(scale,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
