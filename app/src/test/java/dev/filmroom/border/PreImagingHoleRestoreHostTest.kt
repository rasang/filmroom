package dev.filmroom.border

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.model.CompositionTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreImagingHoleRestoreHostTest {
    /** Frozen reference from before the single-sample optimization. */
    private fun reference(bitmap:Bitmap,s:BorderSnapshot,raster:BorderRaster) {
        val ints=IntArray(s.outputWidth);val row=FloatArray(s.outputWidth*3)
        val matte=if((s.appearance?.holeMatteSrgb ?: 0xffffff)==0)0f else 1f
        repeat(s.outputHeight){y->
            bitmap.getPixels(ints,0,s.outputWidth,0,y,s.outputWidth,1)
            for(x in ints.indices){val p=ints[x];row[x*3]=LinearRgb.srgb8ToLinear[(p ushr 16)and 255];row[x*3+1]=LinearRgb.srgb8ToLinear[(p ushr 8)and 255];row[x*3+2]=LinearRgb.srgb8ToLinear[p and 255]}
            for(x in ints.indices){val h=raster.holeCoverage(s,x+.5,y+.5);if(h>0)for(c in 0..2)row[x*3+c]=(row[x*3+c]*(1-h)+matte*h).toFloat()}
            for(x in ints.indices)if(raster.holeCoverage(s,x+.5,y+.5)>0)ints[x]=(0xff shl 24)or(OutputColorSpace.SRGB.encode8(row[x*3])shl 16)or(OutputColorSpace.SRGB.encode8(row[x*3+1])shl 8)or OutputColorSpace.SRGB.encode8(row[x*3+2])
            bitmap.setPixels(ints,0,s.outputWidth,0,y,s.outputWidth,1)
        }
    }
    private fun initialize()=BorderCatalog.initialize(RuntimeEnvironment.getApplication().assets){NativeMemorySnapshot(8L*1024*1024*1024,128L*1024*1024,false)}
    private fun compare(s:BorderSnapshot,raster:BorderRaster) {
        val pixels=IntArray(s.outputWidth*s.outputHeight){i->((if(i%11==0)0 else if(i%7==0)127 else 255)shl 24)or((i*17 and 255)shl 16)or((i*37 and 255)shl 8)or(i*61 and 255)}
        val a=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888)
        a.setPixels(pixels,0,s.outputWidth,0,0,s.outputWidth,s.outputHeight)
        val b=a.copy(Bitmap.Config.ARGB_8888,true)
        try{reference(a,s,raster);PreImagingInput.restoreHoles(b,s,raster);assertTrue("Pixel mismatch: ${s.stock}/${s.assetVersion}/${s.appearance}",a.sameAs(b))}finally{a.recycle();b.recycle()}
    }
    @Test fun actualAssetsMatchReferenceAcross135And120MattesAndOrientations()=runBlocking {withContext(Dispatchers.Default){
        initialize()
        for(stock in listOf("kodak_portra_400","cinestill_800t","kodak_doublex","ilford_hp5_plus_400"))for(medium in listOf(false,true)){
            if(medium && stock=="kodak_doublex")continue
            for(white in listOf(false,true))for(portrait in listOf(false,true))for(route in listOf("print","direct")){
                val w=if(portrait)120 else 180;val h=if(portrait)180 else 120
                val s=BorderCatalog.snapshot(stock,w,h,w,h,CompositionTransform(),white,route,if(medium)MediumFormat.MF66 else null,medium && portrait)
                compare(s,BorderCatalog.resolve(s))
            }
        }
    }}
    @Test fun zeroFullAndFractionalCoverageUseOneSampleAndMatchReference()=runBlocking {withContext(Dispatchers.Default){
        initialize()
        for(white in listOf(false,true)){
            val s=BorderCatalog.snapshot("kodak_portra_400",90,60,90,60,CompositionTransform(),white,"print",null,false)
            val actual=BorderCatalog.resolve(s)
            val levels=doubleArrayOf(0.0,1.0,0.000001,0.25,0.5,0.75,0.999999)
            var calls=0
            val mask=object:BorderRaster by actual {
                override fun holeCoverage(snapshot:BorderSnapshot,x:Double,y:Double):Double {calls++;return levels[(x.toInt()+y.toInt())%levels.size]}
            }
            compare(s,mask)
            assertEquals(s.outputWidth*s.outputHeight*3,calls) // frozen reference: twice; production: once
        }
    }}
    @Test fun cancellationStillCheckedOnEveryRowIncludingEmptyMasks()=runBlocking {withContext(Dispatchers.Default){
        initialize()
        val s=BorderCatalog.snapshot("kodak_portra_400",90,60,90,60,CompositionTransform(),true,"print",null,false)
        val actual=BorderCatalog.resolve(s)
        val mask=object:BorderRaster by actual {override fun holeCoverage(snapshot:BorderSnapshot,x:Double,y:Double)=0.0}
        val bitmap=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888)
        var rows=0
        try {
            try{PreImagingInput.restoreHoles(bitmap,s,mask){rows++;if(rows==3)throw kotlinx.coroutines.CancellationException("test")};fail("Expected cancellation")}
            catch(_:kotlinx.coroutines.CancellationException){assertEquals(3,rows)}
        }finally{bitmap.recycle()}
    }}
}
