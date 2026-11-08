package dev.filmroom.effects

import android.graphics.Bitmap
import dev.filmroom.crop.CropPreview
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.export.ExportJob
import dev.filmroom.model.CropMode
import dev.filmroom.model.CropSettings

/** The original photograph gets the saved presentation geometry, never the film treatment.
 * ImageDecoder has already applied EXIF orientation. Do not rotate it a second time.
 * A border's composition describes its photo aperture, not the outer rail dimensions.
 */
internal data class EffectSourceComposition(val canonicalWidth:Int,val canonicalHeight:Int,val settings:CropSettings) {
    fun snapshot(width:Int,height:Int)=CropSnapshot.create(width,height,canonicalWidth,canonicalHeight,settings)
    fun size(width:Int,height:Int):EffectExportSize=snapshot(width,height).let{EffectExportSize(it.outputWidth,it.outputHeight)}
    /** Caller retains input ownership. Identity returns the same bitmap. */
    fun apply(input:Bitmap,checkActive:()->Unit={}):Bitmap {
        val frozen=snapshot(input.width,input.height)
        return if(frozen.isIdentity)input else CropPreview.compose(input,frozen,checkActive=checkActive)
    }
    companion object {
        fun from(job:ExportJob?):EffectSourceComposition? {
            job ?: return null
            job.border?.let { return fromBorder(it) }
            return job.crop?.let{EffectSourceComposition(it.canonicalWidth,it.canonicalHeight,it.settings)}
        }
        internal fun fromBorder(border:dev.filmroom.border.BorderSnapshot):EffectSourceComposition? {
                if(border.geometryVersion==1)return null // Legacy uncropped borders.
                val transform=border.composition.copy(targetAspect=border.composition.aspect(border.canonicalWidth,border.canonicalHeight))
                return EffectSourceComposition(border.canonicalWidth,border.canonicalHeight,CropSettings(CropMode.FREE,transform))
        }
    }
}
