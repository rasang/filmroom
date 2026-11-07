package dev.filmroom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.model.CompositionTransform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumFormatBorderHostTest {
    private fun snapshot(format:MediumFormat,portrait:Boolean=false,stock:String="kodak_portra_800",route:String="print",white:Boolean=true)=
        BorderCatalog.snapshot(stock,1680,1680,1680,1680,CompositionTransform(),white,route,format,portrait)
    private fun initialize(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        BorderCatalog.initialize(app.assets){dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        BorderCatalog.clearCache()
    }
    private fun resolve(s:BorderSnapshot):BorderRaster {
        val worker=Executors.newSingleThreadExecutor()
        return try {worker.submit<BorderRaster>{BorderCatalog.resolve(s)}.get()}finally{worker.shutdown()}
    }
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}

    @Test fun nominalPhysicalGeometryAllFormatsAndBothOrientations(){
        for(f in MediumFormat.values())for(portrait in listOf(false,true)){
            val s=snapshot(f,portrait);val g=f.geometry(portrait)
            assertEquals(5,s.geometryVersion);assertEquals(portrait,s.portrait)
            assertEquals(if(portrait)1/f.landscapeAspect else f.landscapeAspect,s.composition.targetAspect!!,1e-14)
            assertEquals(g.width,s.frameWidthMm,0.0);assertEquals(g.height,s.frameHeightMm,0.0)
            assertEquals(g.left,(s.apertureLeft-s.frameOriginX)/s.pixelsPerMm,1e-12)
            assertEquals(g.top,(s.apertureTop-s.frameOriginY)/s.pixelsPerMm,1e-12)
            assertEquals(g.apertureHeight,s.photoHeight/s.pixelsPerMm,1/s.pixelsPerMm)
            assertTrue(s.outputWidth>=s.photoWidth);assertTrue(s.outputHeight>=s.photoHeight)
            assertEquals((f==MediumFormat.MF645) xor portrait,g.rotated)
        }
        val horizontal=snapshot(MediumFormat.MF66);val vertical=snapshot(MediumFormat.MF66,true)
        assertEquals(1.0,horizontal.composition.targetAspect!!,0.0)
        assertEquals(1.0,vertical.composition.targetAspect!!,0.0)
        assertNotEquals(horizontal.apertureLeft,vertical.apertureLeft)
    }

    @Test fun snapshotRoundTripFreezesAppearanceOrientationAndStripCenter(){
        for(f in MediumFormat.values())for(p in listOf(false,true))for(route in listOf("print","direct")){
            val s=snapshot(f,p,route=route,white=false)
            assertEquals(s,BorderSnapshot.fromJson(s.json()));assertEquals(410.0,s.stripCenterMm!!,0.0)
            BorderCatalog.verifySnapshot(s)
            assertEquals(FrameAppearanceContext.forProfile(s.stock,route,false),s.appearance)
            val shifted=s.copy(stripCenterMm=450.0)
            assertEquals(shifted,BorderSnapshot.fromJson(shifted.json()));BorderCatalog.verifySnapshot(shifted)
        }
        val s=snapshot(MediumFormat.MF67)
        rejected{BorderCatalog.verifySnapshot(s.copy(assetVersion="unknown"))}
        rejected{BorderCatalog.verifySnapshot(s.copy(appearance=null))}
        rejected{s.copy(stripCenterMm=Double.NaN)}
        rejected{s.copy(stripCenterMm=820.0)}
        rejected{BorderSnapshot.fromJson(JSONObject(s.json()).put("mediumFormat","MF_NO").toString())}
    }

    @Test fun legacyCaptureAndJsonDoNotGainMediumFields(){
        val a=BorderCatalog.snapshot("kodak_portra_800",900,600,900,600,CompositionTransform(),true,"print")
        val b=BorderCatalog.snapshot("kodak_portra_800",900,600,900,600,CompositionTransform(),true,"print",null,false)
        assertEquals(a,b);assertEquals(a.json(),b.json());assertNull(a.mediumFormat);assertNull(a.stripCenterMm)
        val json=JSONObject(a.json());assertFalse(json.has("mediumFormat"));assertFalse(json.has("mediumPortrait"));assertFalse(json.has("stripCenterMm"))
        assertEquals(a,BorderSnapshot.fromJson(a.json()))
        rejected{BorderSnapshot.fromJson(json.put("mediumFormat","MF66").toString())}
        rejected{a.copy(mediumFormat=MediumFormat.MF66)}
        rejected{a.copy(mediumPortrait=true)}
    }

    @Test fun reviewedPhysicalStocksAndPushAliasesOnly(){
        assertEquals(15,MediumFormatStocks.profiles.values.toSet().size);assertEquals(17,MediumFormatStocks.profiles.size)
        val base=snapshot(MediumFormat.MF67)
        for(alias in listOf("kodak_portra_800_push1","kodak_portra_800_push2")){
            val s=snapshot(MediumFormat.MF67,stock=alias)
            assertEquals(base.assetVersion,s.assetVersion);assertEquals("kodak_portra_800",MediumFormatStocks.physicalStock(alias))
        }
        assertFalse(BorderCatalog.available("kodak_vision3_500t",MediumFormat.MF67))
        rejected{snapshot(MediumFormat.MF67,stock="kodak_vision3_500t")}
        for(stock in listOf("kodak_ektachrome_100","fujifilm_provia_100f","fujifilm_velvia_100","kodak_kodachrome_64")){
            val s=snapshot(MediumFormat.MF67,stock=stock,route="direct")
            assertEquals(FrameAppearanceContext.Material.POSITIVE_DISPLAY,s.appearance!!.material)
        }
    }

    @Test fun continuousMasterCoordinatesDoNotChangeWithFormat(){
        initialize()
        val base=snapshot(MediumFormat.MF617);val raster=resolve(base)
        val expected=FloatArray(3);val actual=FloatArray(3)
        // Common physical portion of the strip lies within every crop, regardless of orientation.
        for(along in listOf(400.25,405.75,410.25,419.25))for(cross in listOf(.25,1.25,2.25,58.75,59.75,60.75)){
            fun at(s:BorderSnapshot,out:FloatArray){
                val g=s.mediumFormat!!.geometry(s.mediumPortrait)
                val u=along-s.stripCenterMm!!+(g.format.longitudinalMm+6)/2
                val x=if(g.rotated)61-cross else u;val y=if(g.rotated)u else cross
                raster.sample(s,s.frameOriginX+x*s.pixelsPerMm,s.frameOriginY+y*s.pixelsPerMm,out)
            }
            at(base,expected)
            for(f in MediumFormat.values())for(p in listOf(false,true)){
                val s=snapshot(f,p);raster.validate(s);at(s,actual)
                assertArrayEquals(expected,actual,0.00001f)
            }
        }
        assertTrue(raster.residentBytes<2L*1024*1024)
    }

    @Test fun allBundledRailsAreOpaquePinnedAndHolePreferenceHasNoEffect(){
        initialize()
        for(stock in MediumFormatStocks.profiles.values.toSet()){
            val route=if(stock in listOf("kodak_ektachrome_100","fujifilm_provia_100f","fujifilm_velvia_100","kodak_kodachrome_64"))"direct" else "print"
            val s=snapshot(MediumFormat.MF617,stock=stock,route=route)
            val raster=resolve(s);raster.validate(s) // Loader checks hash, exact dimensions and every alpha=255.
            val dark=s.copy(appearance=s.appearance!!.copy(negativeWhiteHoles=false))
            val a=FloatArray(3);val b=FloatArray(3)
            for(x in 0 until s.outputWidth step 7)for(y in listOf(0,1,s.outputHeight-2,s.outputHeight-1)){
                raster.sample(s,x+.5,y+.5,a);raster.sample(dark,x+.5,y+.5,b)
                assertArrayEquals(a,b,0f);assertTrue(a.all{it.isFinite() && it in 0f..1f})
            }
        }
    }

    @Test fun routeUsesFrozenAppearanceAndLegacyProviderRejects120(){
        initialize();val positive=snapshot(MediumFormat.MF67);val negative=snapshot(MediumFormat.MF67,route="direct")
        val raster=resolve(positive);val a=FloatArray(3);val b=FloatArray(3)
        raster.sample(positive,0.5,positive.outputHeight/2.0,a)
        raster.sample(negative,0.5,negative.outputHeight/2.0,b)
        assertEquals(a[0],a[1],0f);assertTrue(b[0]>b[1] && b[1]>b[2])
        val legacy=object:BorderProvider {
            override fun snapshot(stock:String,width:Int,height:Int)=BorderSnapshot.fixed135(stock,"legacy",width,height)
            override fun resolve(snapshot:BorderSnapshot):BorderRaster=error("Not used")
        }
        rejected{legacy.snapshot("kodak_portra_800",100,100,100,100,CompositionTransform(),true,"print",MediumFormat.MF67,false)}
    }
    @Test fun everyFormatPreviewMatchesFloatExportPixelForPixel(){
        initialize()
        val width=181;val height=137
        val photo=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
        // Asymmetric, nonuniform RGB catches flipped axes, wrong crop/zoom, and channel swaps.
        val pixels=IntArray(width*height){i->val x=i%width;val y=i/width
            (0xff shl 24)or(((x*3+y*7+11)%256)shl 16)or(((x*11+y*2+37)%256)shl 8)or((x*x+y*13+71)%256)}
        photo.setPixels(pixels,0,width,0,0,width,height)
        val dir=java.nio.file.Files.createTempDirectory("medium120-parity").toFile()
        try {
            val bytes=java.nio.ByteBuffer.allocate(width*height*12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val linear=FloatArray(width*height*3)
            for(i in pixels.indices)for(c in 0..2){
                val value=dev.filmroom.io.LinearRgb.srgb8ToLinear[(pixels[i] ushr ((2-c)*8))and 255]
                linear[i*3+c]=value;bytes.putFloat(value)
            }
            val input=java.io.File(dir,"input.rgb");input.writeBytes(bytes.array())
            for(f in MediumFormat.values())for(p in listOf(false,true)){
                val composition=CompositionTransform(centerX=.63,centerY=.38,zoom=1.7)
                val s=BorderCatalog.snapshot("kodak_portra_800",width,height,width,height,composition,true,"print",f,p)
                assertEquals(.63,s.composition.centerX,0.0);assertEquals(.38,s.composition.centerY,0.0)
                assertEquals(1.7,s.composition.zoom,0.0)
                val raster=resolve(s);val preview=BorderPreview.compose(photo,s,raster)
                try {
                    val target=java.io.File("build/ui-host-renders/medium120").apply{mkdirs()}
                    java.io.File(target,"${f.name}-${if(p) "portrait" else "landscape"}-parity.png").outputStream().use{preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    val output=java.io.File(dir,"output.rgb")
                    BorderCompositor.composite(input,output,s,raster,dev.filmroom.io.OutputColorSpace.SRGB)
                    val values=java.nio.ByteBuffer.wrap(output.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    // Independent expected crop extent and source interpolation, not BorderSampling.
                    val aspect=f.geometry(p).aspect;val sourceAspect=width.toDouble()/height
                    val cropWidth=minOf(1.0,aspect/sourceAspect)/1.7
                    val cropHeight=minOf(1.0,sourceAspect/aspect)/1.7
                    for(fx in listOf(.2,.5,.8))for(fy in listOf(.25,.55,.75)){
                        val x=(s.apertureLeft+fx*s.photoWidth).toInt();val y=(s.apertureTop+fy*s.photoHeight).toInt()
                        val sx=((.63-cropWidth/2+(x+.5-s.apertureLeft)/s.photoWidth*cropWidth)*width-.5).coerceIn(0.0,width-1.0)
                        val sy=((.38-cropHeight/2+(y+.5-s.apertureTop)/s.photoHeight*cropHeight)*height-.5).coerceIn(0.0,height-1.0)
                        val x0=kotlin.math.floor(sx).toInt();val y0=kotlin.math.floor(sy).toInt()
                        val x1=minOf(x0+1,width-1);val y1=minOf(y0+1,height-1);val dx=sx-x0;val dy=sy-y0
                        for(c in 0..2){
                            val expected=(linear[(y0*width+x0)*3+c]*(1-dx)+linear[(y0*width+x1)*3+c]*dx)*(1-dy)+
                                (linear[(y1*width+x0)*3+c]*(1-dx)+linear[(y1*width+x1)*3+c]*dx)*dy
                            assertEquals("Independent crop $f portrait=$p x=$x y=$y channel=$c",expected,values.get((y*s.outputWidth+x)*3+c).toDouble(),1e-6)
                        }
                    }
                    for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                        val r=dev.filmroom.io.OutputColorSpace.SRGB.encode8(values.get())
                        val g=dev.filmroom.io.OutputColorSpace.SRGB.encode8(values.get())
                        val b=dev.filmroom.io.OutputColorSpace.SRGB.encode8(values.get())
                        assertEquals((0xff shl 24)or(r shl 16)or(g shl 8)or b,preview.getPixel(x,y))
                    }
                }finally{preview.recycle()}
            }
        }finally{photo.recycle();dir.deleteRecursively()}
    }

    @Test fun pushAliasesReuseTheDecodedPhysicalStripAndSurviveCacheTrim(){
        initialize()
        val app=ApplicationProvider.getApplicationContext<Application>()
        val worker=Executors.newSingleThreadExecutor()
        try {worker.submit<Unit>{
            var opens=0
            val open:(String)->java.io.InputStream={path->opens++;app.assets.open(path)}
            val base=snapshot(MediumFormat.MF67)
            val physical=MediumStripProvider.resolve(base,open,{})
            assertEquals(1,opens)
            var expectedOpens=1
            for(alias in listOf("kodak_portra_800_push1","kodak_portra_800_push2")){
                val s=snapshot(MediumFormat.MF67,stock=alias)
                val raster=MediumStripProvider.resolve(s,open,{})
                assertEquals(alias,raster.stock);raster.validate(s);assertEquals(expectedOpens,opens)
                assertEquals(physical.residentBytes,raster.residentBytes)
                val a=FloatArray(3);val b=FloatArray(3)
                for(x in 0 until s.outputWidth step 5)for(y in listOf(.5,s.outputHeight-.5)){
                    physical.sample(base,x+.5,y,a);raster.sample(s,x+.5,y,b);assertArrayEquals(a,b,0f)
                }
                MediumStripProvider.clearCache()
                // Eviction only drops the registry reference, never invalidates in-flight aliases.
                raster.validate(s);raster.sample(s,.5,.5,b)
                MediumStripProvider.resolve(base,open,{})
                expectedOpens++ // Each explicit trim intentionally requires one reload; aliases never do.
                assertEquals(expectedOpens,opens)
            }
        }.get()}finally{worker.shutdown()}
    }

    @Test fun cancellationBeforeCacheHitAndAfterBitmapDecodeCannotPublishAnAtlas(){
        initialize()
        val app=ApplicationProvider.getApplicationContext<Application>()
        val worker=Executors.newSingleThreadExecutor()
        try {worker.submit<Unit>{
            val s=snapshot(MediumFormat.MF67)
            var opens=0;var closes=0
            val open:(String)->java.io.InputStream={path->opens++
                object:java.io.FilterInputStream(app.assets.open(path)){
                    override fun close(){try{super.close()}finally{closes++}}
                }
            }
            val existing=MediumStripProvider.resolve(s,open,{})
            assertEquals(1,opens)
            var cancelled=false
            try {MediumStripProvider.resolve(s,open,{throw java.util.concurrent.CancellationException("Before cached resolve")})}
            catch(_:java.util.concurrent.CancellationException){cancelled=true}
            assertTrue(cancelled);assertEquals(1,opens)
            assertSame(existing,MediumStripProvider.resolve(s,open,{}))
            MediumStripProvider.clearCache()
            var checksAfterClose=0;cancelled=false
            try {MediumStripProvider.resolve(s,open,{
                // The third checkpoint after this stream closes is inside the decoded bitmap's
                // try/finally, before pixels/cache publication (hash, bounds, then bitmap checks).
                if(closes==2 && ++checksAfterClose==3)throw java.util.concurrent.CancellationException("After bitmap decode")
            })}catch(_:java.util.concurrent.CancellationException){cancelled=true}
            assertTrue(cancelled);assertEquals(3,checksAfterClose);assertEquals(2,opens);assertEquals(2,closes)
            val recovered=MediumStripProvider.resolve(s,open,{})
            assertEquals(3,opens);assertEquals(3,closes);recovered.validate(s)
            assertNotSame(existing,recovered)
            assertSame(recovered,MediumStripProvider.resolve(s,open,{}));assertEquals(3,opens)
        }.get()}finally{worker.shutdown()}
    }

}
