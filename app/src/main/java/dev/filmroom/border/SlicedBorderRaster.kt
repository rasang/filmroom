package dev.filmroom.border

import kotlin.math.floor

/** Explicitly authored edge slices. Only the declared repeatable middle is tiled; labels remain
 * in the left/right caps. Scaling is uniform on BOTH axes using photographic short side.
 * A portrait is a 90-degree rotation of this landscape film strip, not stretched artwork.
 * Asset intake must visually validate repeat bounds never cut through text or a hole.
 * ACTIVATION BLOCKER: this prototype uses nearest-neighbor sampling. Area/mipmap filtering and
 * actual-asset preview downscale QA are required before adding this raster to an approved catalog.
 */
class SlicedBorderRaster(
    override val stock:String, override val version:String,
    override val railRatio:Double, override val endRatio:Double,
    override val featherTotalAt3072:Double,
    private val referencePhotoShort:Int,
    private val atlasWidth:Int, private val atlasHeight:Int,
    private val referenceRail:Int, private val referenceEnd:Int,
    private val repeatStart:Int, private val repeatEnd:Int,
    pixels:IntArray,
):BorderRaster {
    override val supportsFixed135=false
    private val pixels=pixels.copyOf()
    override val residentBytes:Long get()=pixels.size*4L
    init {
        require(atlasWidth>0 && atlasHeight>0 && atlasWidth.toLong()*atlasHeight==pixels.size.toLong())
        require(residentBytes in 1..BorderCompositor.MAX_ATLAS_BYTES)
        require(railRatio.isFinite() && railRatio in .001..0.23 && endRatio.isFinite() && endRatio in .001..0.04)
        require(referencePhotoShort>0 && referenceRail>0 && referenceEnd>0)
        require(atlasHeight==referencePhotoShort+2*referenceRail)
        require(kotlin.math.abs(referenceRail-referencePhotoShort*railRatio)<=.500001)
        require(kotlin.math.abs(referenceEnd-referencePhotoShort*endRatio)<=.500001)
        require(repeatStart>=referenceEnd && repeatEnd>repeatStart && repeatEnd<=atlasWidth-referenceEnd)
        require(pixels.all{it ushr 24==255}) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5c0b4e508a52) }
        require(featherTotalAt3072.isFinite() && featherTotalAt3072 in 0.0..16.0)
    }
    override fun validate(snapshot:BorderSnapshot) {
        super.validate(snapshot)
        val scale=minOf(snapshot.photoWidth,snapshot.photoHeight).toDouble()/referencePhotoShort
        val width=if(snapshot.portrait)snapshot.outputHeight else snapshot.outputWidth
        require(width/scale>=repeatStart+atlasWidth-repeatEnd) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a63185c8bf65) }
    }
    override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray) {
        require(snapshot.stock==stock && snapshot.assetVersion==version)
        // Rotate whole physical strip clockwise for portrait, including edge text orientation.
        val cx=if(snapshot.portrait)y else x
        val cy=if(snapshot.portrait)snapshot.outputWidth-x else y
        val scale=minOf(snapshot.photoWidth,snapshot.photoHeight).toDouble()/referencePhotoShort
        val width=if(snapshot.portrait)snapshot.outputHeight else snapshot.outputWidth
        val ux=cx/scale;val uy=cy/scale
        val fullWidth=width/scale
        val rightCap=atlasWidth-repeatEnd
        require(fullWidth>=repeatStart+rightCap) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a63185c8bf65) }
        val ax=when {
            ux<repeatStart -> ux
            ux>=fullWidth-rightCap -> atlasWidth-(fullWidth-ux)
            else -> repeatStart+(ux-repeatStart)%(repeatEnd-repeatStart)
        }
        // Snap only the narrow seam inward coordinates to opaque aperture-adjacent edge.
        val axSafe=ax.coerceIn(0.0,atlasWidth-1.0)
        val aySafe=uy.coerceIn(0.0,atlasHeight-1.0)
        val c=pixels[floor(aySafe).toInt()*atlasWidth+floor(axSafe).toInt()]
        rgb[0]=((c ushr 16)and 255)/255f;rgb[1]=((c ushr 8)and 255)/255f;rgb[2]=(c and 255)/255f
    }
}
