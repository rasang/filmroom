package dev.filmroom.border

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.InputStream
import java.security.MessageDigest
import dev.filmroom.model.CompositionTransform

/** Metadata-only capture, worker-only bounded decode, one physical strip cached across aliases. */
internal object MediumStripProvider {
    private data class Cached(val stock:String,val version:String,val raster:MediumStripRaster)
    private val loadLock=Any()
    private val cacheLock=Any()
    private var cached:Cached?=null
    private var generation=0L
    fun clearCache(){synchronized(cacheLock){cached=null;generation++}}
    fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:CompositionTransform,negativeWhiteHoles:Boolean,outputRoute:String,format:MediumFormat,portrait:Boolean):BorderSnapshot {
        val definition=MediumStripDefinitions.registry.latest(MediumFormatStocks.physicalStock(stock))
        return BorderSnapshot.medium120(stock,definition.version,width,height,canonicalWidth,canonicalHeight,composition,format,portrait,definition.defaultCenterMm)
            .copy(appearance=FrameAppearanceContext.forProfile(stock,outputRoute,negativeWhiteHoles)).also{verify(it)}
    }
    private fun definition(snapshot:BorderSnapshot)=MediumStripDefinitions.registry.exact(MediumFormatStocks.physicalStock(snapshot.stock),snapshot.assetVersion)
    fun verify(snapshot:BorderSnapshot){
        require(snapshot.geometryVersion==5)
        val definition=definition(snapshot)
        definition.verifyCoverage(snapshot)
        val appearance=requireNotNull(snapshot.appearance){"120 appearance must be frozen"}
        require(appearance==FrameAppearanceContext.forProfile(snapshot.stock,appearance.outputRoute,appearance.negativeWhiteHoles))
        val expected=BorderSnapshot.medium120(snapshot.stock,definition.version,snapshot.sourceWidth,snapshot.sourceHeight,snapshot.canonicalWidth,snapshot.canonicalHeight,snapshot.composition,
            requireNotNull(snapshot.mediumFormat),snapshot.mediumPortrait,requireNotNull(snapshot.stripCenterMm))
        require(snapshot==expected.copy(appearance=appearance)){"120 geometry differs from its frozen contract"}
    }
    fun resolve(snapshot:BorderSnapshot,open:(String)->InputStream,checkActive:()->Unit):BorderRaster {
        checkActive();verify(snapshot)
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){"120 strip decoding must run off Main"}
        val definition=definition(snapshot)
        val physical=synchronized(cacheLock){cached?.takeIf{it.stock==definition.stock && it.version==definition.version}?.raster} ?: synchronized(loadLock){
            checkActive()
            val(hit,epoch)=synchronized(cacheLock){val hit=cached?.takeIf{it.stock==definition.stock && it.version==definition.version}?.raster;if(hit==null)cached=null;hit to generation}
            hit ?: decode(definition,{open(definition.path)},checkActive).also{loaded->
                checkActive();loaded.validate(snapshot)
                synchronized(cacheLock){if(generation==epoch)cached=Cached(definition.stock,definition.version,loaded)}
            }
        }
        checkActive()
        return if(physical.stock==snapshot.stock)physical else Alias(snapshot.stock,physical)
    }
    private class Alias(override val stock:String,private val physical:MediumStripRaster):BorderRaster by physical {
        override fun validate(snapshot:BorderSnapshot){require(snapshot.stock==stock);physical.validate(snapshot)}
    }
    internal fun decode(definition:MediumStripDefinitions.Definition,open:()->InputStream,checkActive:()->Unit):MediumStripRaster {
        require(definition.encodedBytes in 1..StraightAlphaBorderLoader.MAX_ENCODED_BYTES)
        require(definition.width in 1..8200 && definition.height in 1..50 && definition.width.toLong()*definition.height*4<=BorderCompositor.MAX_ATLAS_BYTES)
        checkActive()
        val encoded=ByteArray(definition.encodedBytes)
        open().use{input->var at=0;while(at<encoded.size){checkActive();val n=input.read(encoded,at,minOf(65536,encoded.size-at));require(n>0);at+=n};require(input.read()==-1)}
        checkActive()
        val hash=MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(""){"%02x".format(it.toInt()and 255)}
        require(hash==definition.sha256){"120 rail hash mismatch"}
        StraightAlphaBorderLoader.validateEncoding(encoded,StraightAlphaBorderLoader.Encoding.PNG)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true;inScaled=false}
        BitmapFactory.decodeByteArray(encoded,0,encoded.size,bounds)
        require(bounds.outWidth==definition.width && bounds.outHeight==definition.height && bounds.outMimeType=="image/png")
        checkActive()
        val options=BitmapFactory.Options().apply{inScaled=false;inPremultiplied=false;inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)}
        val bitmap=checkNotNull(BitmapFactory.decodeByteArray(encoded,0,encoded.size,options))
        try {
            checkActive();require(bitmap.width==definition.width && bitmap.height==definition.height && bitmap.config==Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb==true)
            val pixels=IntArray(definition.width*definition.height)
            bitmap.getPixels(pixels,0,definition.width,0,0,definition.width,definition.height)
            checkActive();return MediumStripRaster(definition,pixels)
        }finally{bitmap.recycle()}
    }
}
