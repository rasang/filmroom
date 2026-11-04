package dev.filmroom.border

import kotlin.math.floor

/** Immutable continuous packed rails. Coordinates stay in mm: formats crop, never stretch text. */
internal class MediumStripRaster(
    private val definition:MediumStripDefinitions.Definition,
    private val pixels:IntArray,
):BorderRaster {
    override val stock get()=definition.stock
    override val version get()=definition.version
    override val railRatio=2.5/56.0
    override val endRatio=3.0/56.0
    override val residentBytes get()=pixels.size*4L
    override val featherTotalAt3072=4.0
    override val supportsFixed135=false
    init {
        require(pixels.size==definition.width*definition.height)
        require(residentBytes<=BorderCompositor.MAX_ATLAS_BYTES)
        require(pixels.all{it ushr 24==255}){"120 rails must be opaque, with no holes"}
    }
    override fun validate(snapshot:BorderSnapshot) {
        MediumStripProvider.verify(snapshot)
        require(MediumFormatStocks.physicalStock(snapshot.stock)==stock && snapshot.assetVersion==version)
    }
    override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray) {
        val g=requireNotNull(snapshot.mediumLayout)
        val mx=(x-snapshot.frameOriginX)/snapshot.pixelsPerMm;val my=(y-snapshot.frameOriginY)/snapshot.pixelsPerMm
        val along=requireNotNull(snapshot.stripCenterMm)-(g.format.longitudinalMm+6.0)/2+(if(g.rotated)my else mx)
        val cross=if(g.rotated)61.0-mx else my
        // End allowance and aperture under-feather are neutral base, not fabricated edge content.
        if(cross>=2.5 && cross<58.5){rgb[0]=base;rgb[1]=base;rgb[2]=base}
        else {
            val railHeight=definition.height/2
            val px=(along*definition.pixelsPerMm-.5).coerceIn(0.0,(definition.width-1).toDouble())
            val py=if(cross<2.5)(cross*definition.pixelsPerMm-.5).coerceIn(0.0,(railHeight-1).toDouble())
                else railHeight+((cross-58.5)*definition.pixelsPerMm-.5).coerceIn(0.0,(railHeight-1).toDouble())
            val x0=floor(px).toInt();val x1=minOf(x0+1,definition.width-1);val y0=floor(py).toInt()
            // Never interpolate across the two packed rails' seam.
            val y1=minOf(y0+1,if(y0<railHeight)railHeight-1 else definition.height-1)
            val fx=px-x0;val fy=py-y0
            for(c in 0..2){val shift=(2-c)*8
                rgb[c]=((channel(x0,y0,shift)*(1-fx)+channel(x1,y0,shift)*fx)*(1-fy)+(channel(x0,y1,shift)*(1-fx)+channel(x1,y1,shift)*fx)*fy).toFloat()
            }
        }
        if(snapshot.appearance!!.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION){
            // Explicitly synthetic presentation; never claim a measured negative density curve.
            val ink=((maxOf(rgb[0],rgb[1],rgb[2])-base)/(1-base)).coerceIn(0f,1f)
            rgb[0]=.58f+(.14f-.58f)*ink;rgb[1]=.29f+(.08f-.29f)*ink;rgb[2]=.12f+(.04f-.12f)*ink
        }
    }
    private fun channel(x:Int,y:Int,shift:Int)=((pixels[y*definition.width+x] ushr shift)and 255)/255.0
    private val base get()=if(stock=="kodak_portra_160")9f/255 else 8f/255
}
