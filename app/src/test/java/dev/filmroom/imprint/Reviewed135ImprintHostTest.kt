package dev.filmroom.imprint

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.*

/** Original 135 top-gap semantics, adapted only to the real phase of new exact atlases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Reviewed135ImprintHostTest {
    private val gaps=linkedMapOf("kodak_tri_x_400" to 18.6375,"kodak_tmax_100" to 18.2375,
        "ilford_hp5_plus_400" to 17.5875,"fujifilm_neopan_acros_100_ii" to 20.5375,"cinestill_800t" to 19.0375)
    private val facts=linkedMapOf("PhotographicSensitivity" to "400","ExposureTime" to "1/250",
        "FNumber" to "56/10","ExposureBiasValue" to "1/2","FocalLength" to "50/1")
    private val metadata=PhotoMetadata.fromAttributes(facts+mapOf("DateTimeOriginal" to "2026:11:06 12:34:56"))
    private val settings=ImprintSettings("contax_t2",exposure=true)
    private val app get()=RuntimeEnvironment.getApplication()
    private fun definition(stock:String,route:String="print",white:Boolean=true)=
        Reviewed135Definitions.selected(stock,FrameAppearanceContext.forProfile(stock,route,white))
    private fun snap(stock:String,portrait:Boolean,ppm:Int=20,route:String="print",white:Boolean=true):BorderSnapshot {
        val d=definition(stock,route,white)
        return BorderSnapshot.fixed135(stock,d.spec.version,if(portrait)24*ppm else 36*ppm,if(portrait)36*ppm else 24*ppm,
            composition=CompositionTransform(targetAspect=if(portrait)2.0/3 else 1.5),featherTotalAt3072=0.0)
            .copy(appearance=FrameAppearanceContext.forProfile(stock,route,white))
    }
    private fun physical(b:BorderSnapshot,s:ImprintPlan.Stamp,core:Boolean=false):DoubleArray {
        val x=s.left+(if(core)s.raster.coreLeft else 0);val y=s.top+(if(core)s.raster.coreTop else 0)
        val w=if(core)s.raster.coreWidth else s.raster.width;val h=if(core)s.raster.coreHeight else s.raster.height
        val p=b.pixelsPerMm;val x0=(x-b.frameOriginX)/p;val x1=(x+w-b.frameOriginX)/p
        val y0=(y-b.frameOriginY)/p;val y1=(y+h-b.frameOriginY)/p
        return if(b.portrait)doubleArrayOf(y0,35-x1,y1,35-x0)else doubleArrayOf(x0,y0,x1,y1)
    }
    private fun sameStamp(a:ImprintPlan.Stamp,b:ImprintPlan.Stamp){
        assertEquals(a.left,b.left);assertEquals(a.top,b.top)
        assertEquals(a.raster.width,b.raster.width);assertEquals(a.raster.height,b.raster.height)
        for(y in 0 until a.raster.height){val x=IntArray(a.raster.width);val z=IntArray(b.raster.width)
            a.raster.writeArgbRow(y,x);b.raster.writeArgbRow(y,z);assertArrayEquals(x,z)}
    }

    @Test fun fiveRealTopGapPhasesKeepCommonHeightOddEvenRulesAndFullPaddedMasksOffPerforations(){
        for((stock,gap) in gaps){
            val d=definition(stock);val pixels=StraightAlphaBorderLoader.decodeStraight(d.spec,{app.assets.open(d.assetPath)})
            for(route in listOf("print","direct"))for(portrait in listOf(false,true))for(ppm in listOf(10,20,40))for(count in 1..5){
                val b=snap(stock,portrait,ppm,route)
                val m=PhotoMetadata.fromAttributes(facts.entries.take(count).associate{it.toPair()})
                val p=ImprintPlan.create(settings.copy(dateEnabled=false),m,b.outputWidth,b.outputHeight,b)
                assertEquals("$stock/$route/$portrait/$ppm/$count",count,p.stamps.size);assertEquals(0,p.omittedFieldCount)
                assertEquals(1,p.stamps.map{if(portrait)it.raster.coreWidth else it.raster.coreHeight}.toSet().size)
                for((index,stamp) in p.stamps.withIndex()){
                    val half=count/2;val offset=if(count%2==1)index-half else if(index<half)index-half else index-half+1
                    val expected=gap+offset*4.75;val core=physical(b,stamp,true);val box=physical(b,stamp)
                    assertEquals(expected,(core[0]+core[2])/2,.51/ppm)
                    assertEquals("Original 135 top baseline",3.72,core[3],.51/ppm)
                    assertTrue("Full mask left of hole: $stock $expected ${box.toList()}",box[0]>=expected-1.3125)
                    assertTrue("Full mask right of hole: $stock $expected ${box.toList()}",box[2]<=expected+1.3125)
                    assertTrue("No top brand collision",box[1]>=2.0);assertTrue("No photo intrusion",box[3]<=5.0)
                    // Check the full rectangular padding against approved alpha, not just glyph core.
                    for(y in floor(box[1]*40).toInt()..ceil(box[3]*40).toInt())
                        for(x in floor(box[0]*40).toInt()..ceil(box[2]*40).toInt())
                            assertEquals("$stock alpha at $x,$y",255,pixels[y*1520+x] ushr 24)
                }
            }
        }
    }
    @Test fun dateStaysOnPhotoForBothStoredPlacementValuesAndNeverChangesCaptureData(){
        for(stock in gaps.keys)for(portrait in listOf(false,true))for(route in listOf("print","direct")){
            val b=snap(stock,portrait,20,route)
            val reference=ImprintPlan.create(settings.copy(dateEnabled=false),metadata,b.outputWidth,b.outputHeight,b)
            for((i,style) in ImprintSettings.DATE_STYLES.filter{it!="off"}.withIndex()){
                val s=settings.copy(dateStyle=style,corner=listOf("top_left","top_right","bottom_left","bottom_right")[i],size=if(i%2==0).6 else 1.6,glow=if(i%2==0)0.0 else 1.0)
                val a=ImprintPlan.create(s.copy(datePlacement="photo"),metadata,b.outputWidth,b.outputHeight,b)
                val e=ImprintPlan.create(s.copy(datePlacement="edge"),metadata,b.outputWidth,b.outputHeight,b)
                assertEquals(6,a.stamps.size);assertEquals(6,e.stamps.size);assertEquals(0,e.omittedFieldCount)
                for(k in a.stamps.indices)sameStamp(a.stamps[k],e.stamps[k])
                for(k in reference.stamps.indices)sameStamp(reference.stamps[k],e.stamps[k+1])
                val date=physical(b,e.stamps.first(),true)
                assertTrue(date[0]>=1 && date[2]<=37 && date[1]>=5.5 && date[3]<=29.5)
            }
        }
    }
    @Test fun imprintsNeverMoveOriginalPixelsAndHistoricalVersionsDoNotAcquireNewGapGeometry(){
        for(stock in gaps.keys)for(route in listOf("print","direct")){
            val d=definition(stock,route);val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
            for(portrait in listOf(false,true)){
                val b=snap(stock,portrait,20,route)
                for(s in listOf(ImprintSettings(),settings,settings.copy(datePlacement="edge"),settings.copy(exposure=false))){
                    val p=ImprintPlan.create(s,metadata,b.outputWidth,b.outputHeight,b)
                    assertSame("No 135 mark relocation is permitted",raster,p.frameRaster(b,raster))
                }
                assertNull(Reviewed135ImprintGeometry.layout(b.copy(assetVersion="historical-version")))
            }
        }
        for(d in CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions+BwBorderDefinitions.definitions+BwBorderDefinitions.legacyDefinitions){
            val b=BorderSnapshot.authored(d.spec.stock,d.spec.version,720,480,featherTotalAt3072=d.spec.featherTotalAt3072)
            assertNull(Reviewed135ImprintGeometry.layout(b))
        }
        for(stock in MediumFormatStocks.profiles.keys)for(format in MediumFormat.entries){
            val b=BorderCatalog.snapshot(stock,720,480,720,480,CompositionTransform(),true,
                if(FrameProfileCatalog.kind(stock)==FrameAppearanceContext.FilmKind.REVERSAL)"direct" else "print",format,false)
            assertNull(Reviewed135ImprintGeometry.layout(b))
        }
    }
    @Test fun tinyMissingAndExtremeMetadataRetainSharedFitOmissionAndMemoryRules(){
        val extreme=PhotoMetadata.fromAttributes(mapOf("PhotographicSensitivity" to "65535","SensitivityType" to "3","ISOSpeed" to "4294967295",
            "ExposureTime" to "4294967295/1","FNumber" to "4294967295/1","FocalLength" to "4294967295/1","ExposureBiasValue" to "-99/1"))
        val frozen=extreme.toJson()
        for(stock in gaps.keys)for(portrait in listOf(false,true))for(ppm in listOf(1,2,10,40)){
            val b=snap(stock,portrait,ppm)
            assertTrue(ImprintPlan.create(settings,PhotoMetadata.unavailable(),b.outputWidth,b.outputHeight,b).isEmpty)
            for(m in listOf(metadata,extreme)){
                val p=ImprintPlan.create(settings.copy(dateEnabled=false),m,b.outputWidth,b.outputHeight,b)
                assertEquals(5,p.stamps.size+p.omittedFieldCount)
                assertTrue(p.stamps.isEmpty() || p.stamps.size==5)
                if(p.stamps.isNotEmpty())assertEquals(1,p.stamps.map{if(portrait)it.raster.coreWidth else it.raster.coreHeight}.toSet().size)
                assertTrue(p.residentBytes<=ImprintPlan.MAX_WORKING_BYTES)
                p.stamps.forEach{assertTrue(it.left>=0 && it.top>=0 && it.right<=p.width && it.bottom<=p.height)}
            }
        }
        assertEquals(frozen,extreme.toJson())
    }
    @Test fun imprintedPreviewEqualsFloatExportAtEveryPixelForFiveStocksBothRoutesAndOrientations(){
        val dir=Files.createTempDirectory("reviewed135-top-parity").toFile()
        val review=System.getProperty("filmroom.reviewed135.top.output")?.let{File(it).apply{mkdirs()}}
        try {
            for(stock in gaps.keys)for(route in listOf("print","direct"))for(white in listOf(true,false)){
                val d=definition(stock,route,white);val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
                for(portrait in listOf(false,true)){
                    val b=snap(stock,portrait,20,route,white);val w=if(portrait)480 else 720;val h=if(portrait)720 else 480
                    val pixels=IntArray(w*h){i->0xff000000.toInt() or ((50+i%w*100/w) shl 16) or ((65+i/w*90/h) shl 8) or 110}
                    val photo=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
                    try{
                        val input=File(dir,"input");val output=File(dir,"output")
                        val buffer=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
                        pixels.forEach{pixel->for(c in 0..2)buffer.putFloat(LinearRgb.srgb8ToLinear[(pixel ushr(16-c*8))and 255])};input.writeBytes(buffer.array())
                        val plan=ImprintPlan.create(settings.copy(datePlacement="edge"),metadata,b.outputWidth,b.outputHeight,b)
                        assertEquals(6,plan.stamps.size)
                        val preview=BorderPreview.compose(photo,b,raster,plan)
                        try{
                            BorderCompositor.composite(input,output,b,raster,OutputColorSpace.SRGB,plan)
                            assertEquals(b.outputWidth.toLong()*b.outputHeight*12,output.length())
                            val values=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                            for(y in 0 until preview.height)for(x in 0 until preview.width)for(c in 0..2){
                                val value=values.get();assertTrue(value.isFinite())
                                assertEquals("$stock/$route/$portrait $x,$y/$c",(preview.getPixel(x,y) ushr(16-c*8))and 255,OutputColorSpace.SRGB.encode8(value))
                            }
                            if(review!=null && white){
                                val name="$stock-$route-${if(portrait)"portrait" else "landscape"}"
                                File(review,"$name.png").outputStream().use{check(preview.compress(Bitmap.CompressFormat.PNG,100,it))}
                                val crop=if(portrait)Bitmap.createBitmap(preview,preview.width-110,0,110,preview.height) else Bitmap.createBitmap(preview,0,0,preview.width,110)
                                try{File(review,"$name-top.png").outputStream().use{check(crop.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{crop.recycle()}
                            }
                        }finally{preview.recycle()}
                    }finally{photo.recycle()}
                }
            }
        }finally{dir.deleteRecursively()}
    }
}
