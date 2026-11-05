package dev.filmroom.imprint

import dev.filmroom.io.OutputColorSpace
import kotlin.math.*

enum class ImprintTone { WARM, MONOCHROME, DARK }

/** Bounded stamp masks shared by preview and scanline export. Coordinates include the halo padding.
 * Row pixels are interleaved RGB in linear output primaries. Never allocates a photo-sized image. */
class ImprintRaster private constructor(
    val width: Int, val height: Int,
    val coreLeft: Int, val coreTop: Int, val coreWidth: Int, val coreHeight: Int,
    private val core: ByteArray, private val bloom: ByteArray, private val halo: ByteArray,
    private val tone:ImprintTone
) {
    val residentBytes: Long get() = core.size.toLong()+bloom.size+halo.size
    /** Immutable raster; safe to reuse across frames and concurrent export readers. */
    fun blendRow(y: Int, row: FloatArray, left: Int, colorSpace: OutputColorSpace) {
        require(row.size%3==0)
        if(y !in 0 until height) return
        val colors=palettes[tone.ordinal][colorSpace.ordinal]
        val from=max(0L,-left.toLong()).coerceAtMost(width.toLong()).toInt()
        val until=min(width.toLong(),row.size/3L-left.toLong()).coerceAtLeast(0).toInt()
        for(x in from until until) {
            val p=y*width+x; val d=(left+x)*3
            val a=(core[p].toInt() and 255)/255f
            val b=(bloom[p].toInt() and 255)/255f
            val h=(halo[p].toInt() and 255)/255f
            if(a+b+h==0f) continue
            // Layering in linear light preserves a fine luminous core without a large neon cloud.
            for(c in 0..2) {
                var v=row[d+c]
                v += (colors[6+c]-v)*h
                v += (colors[3+c]-v)*b
                v += (colors[c]-v)*a
                row[d+c]=v
            }
        }
    }
    /** Straight-alpha sRGB source for transient Canvas overlays only. Authoritative rendering uses
     * blendRow: Android's encoded-light Canvas interpolation is not identical to linear compositing. */
    fun writeArgbRow(y: Int, row: IntArray) {
        require(row.size >= width)
        require(y in 0 until height)
        val colors=palettes[tone.ordinal][OutputColorSpace.SRGB.ordinal]
        for(x in 0 until width) {
            val p=y*width+x
            val a=(core[p].toInt() and 255)/255f
            val b=(bloom[p].toInt() and 255)/255f
            val h=(halo[p].toInt() and 255)/255f
            val alpha=1f-(1f-a)*(1f-b)*(1f-h)
            if(alpha==0f) {row[x]=0;continue}
            var argb=(alpha*255).roundToInt().coerceIn(0,255) shl 24
            for(c in 0..2) {
                val value=((colors[6+c]*h*(1-b)+colors[3+c]*b)*(1-a)+colors[c]*a)/alpha
                argb=argb or (OutputColorSpace.SRGB.encode8(value) shl (16-c*8))
            }
            row[x]=argb
        }
    }
    fun rotatedClockwise(): ImprintRaster {
        fun rotate(input: ByteArray): ByteArray {
            val output=ByteArray(input.size)
            for(y in 0 until height) for(x in 0 until width) output[x*height+height-1-y]=input[y*width+x]
            return output
        }
        return ImprintRaster(height,width,height-coreTop-coreHeight,coreLeft,coreHeight,coreWidth,
            rotate(core),rotate(bloom),rotate(halo),tone)
    }
    companion object {
        const val MAX_MASK_BYTES=16L*1024*1024
        private val styles=setOf("contax_t2","yashica_t4","canon_data_back","canon_luna","pentax_mzs")
        // Linear sRGB values; convert once rather than applying a transfer function to image rows.
        private val palettes=ImprintTone.entries.map { tone -> OutputColorSpace.entries.map { space ->
            val src=when(tone){
                ImprintTone.WARM->floatArrayOf(1f,.7874f,.3424f, 1f,.243f,.01165f, 1f,.04324f,.001935f)
                ImprintTone.MONOCHROME->floatArrayOf(.7874f,.7874f,.6921f,.7874f,.7874f,.6921f,.7874f,.7874f,.6921f)
                ImprintTone.DARK->floatArrayOf(.001548f,.001161f,.000774f,.001548f,.001161f,.000774f,.001548f,.001161f,.000774f)
            }
            for(i in 0..2) {
                val p=i*3; val r=src[p];val g=src[p+1];val b=src[p+2]
                when(space) {
                    OutputColorSpace.SRGB -> Unit
                    OutputColorSpace.DISPLAY_P3 -> {src[p]=.8224619687f*r+.1775380313f*g;src[p+1]=.0331941989f*r+.9668058011f*g;src[p+2]=.0170826307f*r+.0723974407f*g+.9105199286f*b}
                    OutputColorSpace.ADOBE_RGB -> {src[p]=.71512561f*r+.28487439f*g;src[p+1]=g;src[p+2]=.04116195f*g+.95883805f*b}
                }
            };src
        }}
        fun create(text: String, style: String, heightPx: Double, glow: Double, tone:ImprintTone=ImprintTone.WARM): ImprintRaster {
            require(style in styles) {"Unknown imprint style: $style"}
            require(text.length in 1..48 && text.any{!it.isWhitespace()})
            require(heightPx.isFinite() && heightPx in 1.0..512.0)
            require(glow.isFinite() && glow in 0.0..1.0)
            val glyphs=text.map{ImprintGlyphs.glyph(it,style)}
            val scale=heightPx
            val padding=ceil(scale*.25+2).toInt()
            val coreWidth=ceil(glyphs.sumOf{it.advance}*scale).toInt().coerceAtLeast(1)
            val coreHeight=ceil(heightPx).toInt()
            val width=coreWidth+padding*2;val height=coreHeight+padding*2
            val pixels=width.toLong()*height
            require(pixels*3<=MAX_MASK_BYTES) {"Imprint exceeds bounded stamp budget"}
            val core=ByteArray(pixels.toInt());val bloom=ByteArray(pixels.toInt());val halo=ByteArray(pixels.toInt())
            var cursor=padding.toDouble()
            glyphs.forEach { glyph ->
                glyph.strokes.forEach { s ->
                    val x1=cursor+s.x1*scale;val y1=padding+s.y1*scale
                    val x2=cursor+s.x2*scale;val y2=padding+s.y2*scale
                    val radius=s.radius*scale
                    val reach=radius+scale*.19+1
                    val xStart=floor(min(x1,x2)-reach).toInt().coerceAtLeast(0)
                    val xEnd=ceil(max(x1,x2)+reach).toInt().coerceAtMost(width-1)
                    val yStart=floor(min(y1,y2)-reach).toInt().coerceAtLeast(0)
                    val yEnd=ceil(max(y1,y2)+reach).toInt().coerceAtMost(height-1)
                    val dx=x2-x1;val dy=y2-y1;val len=dx*dx+dy*dy
                    for(y in yStart..yEnd) for(x in xStart..xEnd) {
                        var cover=0.0;var ib=0.0;var ob=0.0
                        // Same reference-reviewed radii/opacity as the PDF. Union masks preserve
                        // neighbouring dot halos, unlike the old weak maximum-only bloom.
                        for(sy in 0..1) for(sx in 0..1) {
                            val px=x+.25+sx*.5;val py=y+.25+sy*.5
                            val t=if(len==0.0)0.0 else (((px-x1)*dx+(py-y1)*dy)/len).coerceIn(0.0,1.0)
                            val distance=hypot(px-x1-t*dx,py-y1-t*dy)
                            fun coverage(add:Double)=(radius+scale*add+.5-distance).coerceIn(0.0,1.0)
                            cover+=coverage(0.0)
                            ib+=1-(1-.22*glow*coverage(.06))*(1-.45*glow*coverage(.025))
                            ob+=1-(1-.045*glow*coverage(.19))*(1-.08*glow*coverage(.14))*(1-.15*glow*coverage(.10))
                        }
                        val p=y*width+x
                        fun merge(mask:ByteArray,v:Double) {
                            val old=(mask[p].toInt() and 255)/255.0
                            mask[p]=((old+(1-old)*v.coerceIn(0.0,1.0))*255).roundToInt().coerceIn(0,255).toByte()
                        }
                        merge(core,cover*.25)
                        merge(bloom,ib*.25)
                        merge(halo,ob*.25)
                    }
                }
                cursor+=glyph.advance*scale
            }
            return ImprintRaster(width,height,padding,padding,coreWidth,coreHeight,core,bloom,halo,tone)
        }

    }
}
