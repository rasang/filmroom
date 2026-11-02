package dev.filmroom.border

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.InputStream
import java.security.MessageDigest

/** Bounded loader for the isolated candidate trusted, compiled registry. No path/URI/network interpretation.
 * Callers supply immutable reviewed metadata and a trusted stream factory; user-editable metadata
 * is not an approval mechanism. Production checkout remains untouched. Hash and bounded exact encoded
 * length are verified before any image decode; bounds are verified before bitmap allocation.
 */
object StraightAlphaBorderLoader {
    const val MAX_ENCODED_BYTES=4*1024*1024
    enum class Encoding(val mime:String){ PNG("image/png"), LOSSLESS_WEBP("image/webp") }
    data class Spec(val stock:String,val version:String,val sha256:String,val encodedBytes:Int,
                    val width:Int=1520,val height:Int=1400,val featherTotalAt3072:Double=4.0,val encoding:Encoding=Encoding.PNG,val holeMatteSrgb:Int=0xffffff) {
        init {
            require(stock.matches(Regex("[a-z0-9_]{1,80}")) && version.matches(Regex("[a-zA-Z0-9_.-]{1,96}")))
            require(holeMatteSrgb in 0..0xffffff)
            require(sha256.matches(Regex("[a-f0-9]{64}")))
            require(encodedBytes in 1..MAX_ENCODED_BYTES)
            val bw=BwFrameGeometry.forStock(stock)
            require(if(bw==null)width==1520 && height==1400 && width.toLong()*35==height.toLong()*38 else width.toDouble()==bw.width && height.toDouble()==bw.height)
            require(featherTotalAt3072.isFinite() && featherTotalAt3072 in 0.0..16.0)
        }
    }
    fun load(spec:Spec,open:()->InputStream,checkActive:()->Unit={}):BorderRaster {
        val pixels=decodeStraight(spec,open,checkActive)
        checkActive()
        val raster=if(BwFrameGeometry.forStock(spec.stock)!=null)AuthoredBwBorderRaster(spec.stock,spec.version,spec.width,spec.height,pixels,spec.featherTotalAt3072,spec.holeMatteSrgb) else StraightAlphaFixed135Raster(spec.stock,spec.version,spec.width,spec.height,pixels,spec.featherTotalAt3072,spec.holeMatteSrgb)
        checkActive()
        return raster
    }
    /** Observer is internal and used only to verify decoder ownership/cancellation in host tests. */
    internal fun decodeStraight(spec:Spec,open:()->InputStream,checkActive:()->Unit={},onDecoded:(Bitmap)->Unit={}):IntArray {
        checkActive()
        val encoded=ByteArray(spec.encodedBytes)
        open().use{input->
            var position=0
            while(position<encoded.size){
                checkActive()
                val read=input.read(encoded,position,minOf(64*1024,encoded.size-position))
                require(read>0){"Encoded atlas shorter than reviewed length"};position+=read
            }
            checkActive();require(input.read()==-1){"Encoded atlas exceeds reviewed length"}
        }
        checkActive()
        val actual=MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(""){"%02x".format(it.toInt()and 255)}
        require(actual==spec.sha256){"Atlas hash does not match reviewed bytes"}
        validateEncoding(encoded,spec.encoding)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true;inScaled=false}
        BitmapFactory.decodeByteArray(encoded,0,encoded.size,bounds)
        require(bounds.outWidth==spec.width && bounds.outHeight==spec.height && bounds.outMimeType==spec.encoding.mime){"Atlas decoded dimensions/type do not match reviewed geometry"}
        checkActive()
        val options=BitmapFactory.Options().apply{
            inScaled=false;inPremultiplied=false;inPreferredConfig=Bitmap.Config.ARGB_8888
            inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap=checkNotNull(BitmapFactory.decodeByteArray(encoded,0,encoded.size,options)){"Atlas decode failed"}
        try {
            onDecoded(bitmap);checkActive()
            require(bitmap.width==spec.width && bitmap.height==spec.height && bitmap.config==Bitmap.Config.ARGB_8888)
            require(!bitmap.isPremultiplied){"Decoder did not preserve straight alpha"}
            require(bitmap.colorSpace?.isSrgb==true){"Atlas decoder did not return sRGB"}
            val pixels=IntArray(spec.width*spec.height)
            bitmap.getPixels(pixels,0,spec.width,0,0,spec.width,spec.height)
            checkActive()
            return pixels
        }finally{bitmap.recycle()}
    }
    /** Check lossless bitstream/container, not merely a .webp suffix or MIME guess. */
    internal fun validateEncoding(bytes:ByteArray,encoding:Encoding){
        if(encoding==Encoding.PNG){
            require(bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))){"Expected reviewed PNG signature"}
            return
        }
        fun tag(at:Int)=String(bytes,at,4,Charsets.US_ASCII)
        fun u32(at:Int)=(0..3).fold(0L){v,n->v or ((bytes[at+n].toLong()and 255)shl(8*n))}
        require(bytes.size>=20 && tag(0)=="RIFF" && tag(8)=="WEBP" && u32(4)+8==bytes.size.toLong()){"Invalid reviewed WebP RIFF length/signature"}
        var offset=12;var lossless=0
        while(offset<bytes.size){
            require(bytes.size-offset>=8){"Truncated WebP chunk"}
            val tag=tag(offset);val length=u32(offset+4);val next=offset.toLong()+8+length+(length and 1)
            require(next<=bytes.size && next>offset){"Invalid WebP chunk length"}
            require(tag in setOf("VP8X","VP8L","ICCP","EXIF","XMP ")){"Animated/lossy/unknown WebP chunks are not accepted"}
            if(tag=="VP8X")require(length==10L && (bytes[offset+8].toInt()and 0xc3)==0){"Animated/reserved WebP flags are not accepted"}
            if(tag=="VP8L"){require(length>=5 && bytes[offset+8]==0x2f.toByte() && ((bytes[offset+12].toInt() ushr 5)and 7)==0);lossless++}
            offset=next.toInt()
        }
        require(lossless==1 && offset==bytes.size){"Exactly one lossless WebP image is required"}
    }

}
