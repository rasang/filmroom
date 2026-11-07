package dev.filmroom.imprint

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.io.PhotoMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Kodachrome120ImprintHostTest {
    private val stock="kodak_kodachrome_64"
    private val base=mapOf("DateTimeOriginal" to "2026:11:06 12:34:56","PhotographicSensitivity" to "64",
        "ExposureTime" to "1/125","FNumber" to "56/10","ExposureBiasValue" to "0/1","FocalLength" to "80/1")
    private fun snap(f:MediumFormat,p:Boolean,route:String,w:Int=1280,h:Int=960)=
        BorderSnapshot.medium120(stock,MediumStripDefinitions.definitions.getValue(stock).version,w,h,
            mediumFormat=f,mediumPortrait=p).copy(appearance=FrameAppearanceContext.forProfile(stock,route,false))
    private fun physical(b:BorderSnapshot,s:ImprintPlan.Stamp):DoubleArray {
        val ppm=b.pixelsPerMm;val x0=(s.left-b.frameOriginX)/ppm;val x1=(s.right-b.frameOriginX)/ppm
        val y0=(s.top-b.frameOriginY)/ppm;val y1=(s.bottom-b.frameOriginY)/ppm
        return if(b.mediumLayout!!.rotated)doubleArrayOf(y0,61-x1,y1,61-x0)else doubleArrayOf(x0,y0,x1,y1)
    }
    private fun bounds(b:BorderSnapshot,p:ImprintPlan,photoDate:Boolean){
        val l=MediumImprintMarks.layout(b)!!;val tolerance=.51/b.pixelsPerMm
        p.stamps.forEachIndexed { i,s ->
            assertTrue(s.left>=0 && s.top>=0 && s.right<=p.width && s.bottom<=p.height)
            val r=physical(b,s)
            if(photoDate && i==0){
                assertTrue("Padded photo date left",r[0]>=3.0-tolerance)
                assertTrue("Padded photo date right",r[2]<=3.0+b.mediumFormat!!.longitudinalMm+tolerance)
                assertTrue("Padded photo date top",r[1]>=2.5-tolerance)
                assertTrue("Padded photo date bottom",r[3]<=58.5+tolerance)
            }else{
                assertTrue("Left imprint collision",r[0]>=l.clearStartMm-tolerance)
                assertTrue("Right imprint collision",r[2]<=l.clearEndMm+tolerance)
                assertTrue(r[1]>=58.5-tolerance && r[3]<=61.0+tolerance)
            }
        }
        val edgeStamps=if(photoDate)p.stamps.drop(1)else p.stamps
        if(edgeStamps.isNotEmpty()){
            val rectangles=edgeStamps.map{physical(b,it)}
            val center=(rectangles.minOf{it[0]}+rectangles.maxOf{it[2]})/2
            assertEquals("Date/parameter group must be centered on the film",l.widthMm/2,center,1.01/b.pixelsPerMm)
        }
    }
    private fun sample(r:BorderRaster,b:BorderSnapshot,local:Double,cross:Double):FloatArray {
        val rotated=b.mediumLayout!!.rotated
        val x=if(rotated)61-cross else local;val y=if(rotated)local else cross
        return FloatArray(3).also{r.sample(b,b.frameOriginX+x*b.pixelsPerMm,b.frameOriginY+y*b.pixelsPerMm,it)}
    }
    @Test fun allFormatsCenterClearanceAndMoveOnlyReviewedLowerMarks(){
        val app=ApplicationProvider.getApplicationContext<Application>();val d=MediumStripDefinitions.definitions.getValue(stock)
        val bitmap=app.assets.open(d.path).use{BitmapFactory.decodeStream(it)}!!
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle()
        val original=MediumStripRaster(d,pixels)
        for(f in MediumFormat.entries)for(p in listOf(false,true))for(route in listOf("direct")){
            val b=snap(f,p,route);val l=MediumImprintMarks.layout(b)!!
            assertTrue(l.protectPhotoDate);assertTrue(l.moves.isNotEmpty())
            assertEquals(l.widthMm/2,(l.clearStartMm+l.clearEndMm)/2,1e-9)
            val wrapped=MediumImprintMarks.wrap(b,original)
            assertNotSame(original,wrapped);wrapped.validate(b)
            assertEquals(original.residentBytes+256,wrapped.residentBytes)
            // The upper arrows/numbers and aperture are never touched.
            for(cross in listOf(.3,1.2,2.4,30.0,58.4))for(i in 0..50){
                val local=l.widthMm*i/50
                assertArrayEquals(sample(original,b,local,cross),sample(wrapped,b,local,cross),0f)
            }
            // Each existing visible lower mark retains its pixels and ordering.
            // Already cropped source fragments are preserved, not invented/completed.
            assertTrue(l.moves.zipWithNext().all{(left,right)->left.targetMm+(left.right-left.left)*l.mmPerPixel<=right.targetMm})
            for(m in l.moves){
                assertTrue(m.targetMm>=0 && m.targetMm+(m.right-m.left)*l.mmPerPixel<=l.widthMm)
                for(px in m.left until m.right)for(py in 0 until d.height/2){
                    val cross=58.5+(py+.5)/d.pixelsPerMm
                    val local=m.targetMm+(px+.5-m.left)*l.mmPerPixel
                    val sourceLocal=(l.cropStartPx+px+.5)/d.pixelsPerMm-(410-l.widthMm/2)
                    assertArrayEquals(sample(original,b,sourceLocal,cross),sample(wrapped,b,local,cross),.00001f)
                }
            }
            val baseColor=FloatArray(3){8f/255}
            for(i in 0..80)for(py in 0 until d.height/2){
                val local=l.clearStartMm+(l.clearEndMm-l.clearStartMm)*i/80
                assertArrayEquals("Relocated ink enters centered clearance",baseColor,sample(wrapped,b,local,58.5+(py+.5)/d.pixelsPerMm),.00001f)
            }
        }
    }
    @Test fun allFormatsOrientationsMaterialsStylesPlacementsAndSizesAvoidMarks(){
        var cases=0
        val m=PhotoMetadata.fromAttributes(base)
        for(f in MediumFormat.entries)for(p in listOf(false,true))for(route in listOf("direct"))
            for(style in ImprintSettings.DATE_STYLES.filter{it!="off"})for(placement in ImprintSettings.PLACEMENTS)
                for(size in listOf(.6,1.6)){
                    val b=snap(f,p,route);val s=ImprintSettings(style,size=size,glow=1.0,exposure=true,datePlacement=placement)
                    val plan=ImprintPlan.create(s,m,b.outputWidth,b.outputHeight,b)
                    assertEquals(6,plan.stamps.size);assertEquals(0,plan.omittedFieldCount)
                    bounds(b,plan,placement=="photo");cases++
                }
        assertEquals(160,cases)
        println("KODACHROME120 IMPRINT_GEOMETRY PASS cases=$cases full-padded-masks centered-group lower-marks-relocated")
    }
    @Test fun allPhotoCornersAndTinyOutputsEitherFitOrReportOmissions(){
        val m=PhotoMetadata.fromAttributes(base)
        for(f in MediumFormat.entries)for(p in listOf(false,true))for(corner in ImprintSettings.CORNERS)
            for(w in listOf(120,181,384,1280)){
                val b=snap(f,p,"direct",w,w*3/4)
                val settings=ImprintSettings("contax_t2",corner=corner,size=1.6,glow=1.0,exposure=true,datePlacement="photo")
                val plan=ImprintPlan.create(settings,m,b.outputWidth,b.outputHeight,b)
                assertEquals(6,plan.stamps.size+plan.omittedFieldCount)
                // Date is first whenever it fits. At these sizes a photo date fits;
                // very small film rails may omit metadata rather than overwrite ink.
                assertTrue(plan.stamps.isNotEmpty());bounds(b,plan,true)
            }
    }
    @Test fun extremeMetadataStaysWithinGapWithoutSilentlyDroppingFieldsAtExportSize(){
        val m=PhotoMetadata.fromAttributes(base+mapOf("PhotographicSensitivity" to "65535","SensitivityType" to "3","ISOSpeed" to "4294967295",
            "ExposureTime" to "4294967295/1","FNumber" to "4294967295/1","ExposureBiasValue" to "-99/1","FocalLength" to "4294967295/1"))
        for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val b=snap(f,p,"direct",3000,2000)
            val plan=ImprintPlan.create(ImprintSettings("canon_luna",exposure=true,datePlacement="edge"),m,b.outputWidth,b.outputHeight,b)
            assertEquals(6,plan.stamps.size);assertEquals(0,plan.omittedFieldCount);bounds(b,plan,false)
        }
    }
    @Test fun disabledMissingMetadataAndNondefaultSnapshotsFailSafely(){
        val app=ApplicationProvider.getApplicationContext<Application>();val d=MediumStripDefinitions.definitions.getValue(stock)
        val atlas=app.assets.open(d.path).use{BitmapFactory.decodeStream(it)}!!
        val pixels=IntArray(atlas.width*atlas.height);atlas.getPixels(pixels,0,atlas.width,0,0,atlas.width,atlas.height);atlas.recycle()
        val raster=MediumStripRaster(d,pixels)
        val m=PhotoMetadata.fromAttributes(base)
        for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val b=snap(f,p,"direct")
            for(placement in ImprintSettings.PLACEMENTS){
                val date=ImprintSettings("contax_t2",datePlacement=placement)
                val dateOnly=ImprintPlan.create(date,m,b.outputWidth,b.outputHeight,b)
                assertEquals(1,dateOnly.stamps.size);assertEquals(0,dateOnly.omittedFieldCount);bounds(b,dateOnly,placement=="photo")
                val exposureOnly=ImprintPlan.create(date.copy(exposure=true,dateEnabled=false),m,b.outputWidth,b.outputHeight,b)
                assertEquals(5,exposureOnly.stamps.size);assertEquals(0,exposureOnly.omittedFieldCount);bounds(b,exposureOnly,false)
                val missing=ImprintPlan.create(date.copy(exposure=true),PhotoMetadata.fromAttributes(emptyMap()),b.outputWidth,b.outputHeight,b)
                assertTrue(missing.isEmpty);assertEquals(0,missing.omittedFieldCount)
                val partial=ImprintPlan.create(date.copy(exposure=true),PhotoMetadata.fromAttributes(mapOf("PhotographicSensitivity" to "64")),b.outputWidth,b.outputHeight,b)
                assertEquals(1,partial.stamps.size);bounds(b,partial,false)
            }
            for(invalid in listOf(b.copy(stripCenterMm=411.0),b.copy(assetVersion="unknown-v10"))){
                assertNull(MediumImprintMarks.layout(invalid));assertFalse(ImprintPlan.exposureSupported(invalid))
                assertSame(raster,MediumImprintMarks.wrap(invalid,raster))
                val exposureOnly=ImprintPlan.create(ImprintSettings(exposure=true),m,invalid.outputWidth,invalid.outputHeight,invalid)
                assertTrue(exposureOnly.isEmpty)
            }
            for(w in listOf(32,64,90))for(corner in ImprintSettings.CORNERS){
                val tiny=snap(f,p,"direct",w,w*3/4)
                val date=ImprintPlan.create(ImprintSettings("contax_t2",corner=corner,size=1.6),m,tiny.outputWidth,tiny.outputHeight,tiny)
                assertEquals(1,date.stamps.size+date.omittedFieldCount)
                bounds(tiny,date,true)
            }
        }
    }
    @Test fun imprintedPreviewAndFloatExportMatchForAllFormatsOrientationsAndMaterials(){
        val app=ApplicationProvider.getApplicationContext<Application>();val d=MediumStripDefinitions.definitions.getValue(stock)
        val atlas=app.assets.open(d.path).use{BitmapFactory.decodeStream(it)}!!
        val ap=IntArray(atlas.width*atlas.height);atlas.getPixels(ap,0,atlas.width,0,0,atlas.width,atlas.height);atlas.recycle()
        val raster=MediumStripRaster(d,ap)
        val w=640;val h=480
        val photo=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
        val pix=IntArray(w*h){i->val x=i%w;val y=i/w;(0xff shl 24)or(((x*3+y*7)%256)shl 16)or(((x+y*11)%256)shl 8)or((x*13+y)%256)}
        photo.setPixels(pix,0,w,0,0,w,h)
        val dir=java.nio.file.Files.createTempDirectory("kodachrome-imprint-parity").toFile()
        var count=0;var compared=0L
        try{
            val input=java.io.File(dir,"input.rgb");val bytes=java.nio.ByteBuffer.allocate(w*h*12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for(pixel in pix)for(c in 0..2)bytes.putFloat(dev.filmroom.io.LinearRgb.srgb8ToLinear[(pixel ushr ((2-c)*8)) and 255])
            input.writeBytes(bytes.array())
            for(f in MediumFormat.entries)for(p in listOf(false,true))for(route in listOf("direct"))for(placement in ImprintSettings.PLACEMENTS){
                val b=snap(f,p,route,w,h);val settings=ImprintSettings("contax_t2",exposure=true,datePlacement=placement)
                val plan=ImprintPlan.create(settings,PhotoMetadata.fromAttributes(base),b.outputWidth,b.outputHeight,b)
                assertEquals(6,plan.stamps.size);assertEquals(0,plan.omittedFieldCount);bounds(b,plan,placement=="photo")
                val preview=BorderPreview.compose(photo,b,raster,plan)
                try{
                    val output=java.io.File(dir,"output.rgb")
                    BorderCompositor.composite(input,output,b,raster,dev.filmroom.io.OutputColorSpace.SRGB,plan)
                    val floats=java.nio.ByteBuffer.wrap(output.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    for(y in 0 until preview.height)for(x in 0 until preview.width){
                        val red=dev.filmroom.io.OutputColorSpace.SRGB.encode8(floats.get())
                        val green=dev.filmroom.io.OutputColorSpace.SRGB.encode8(floats.get())
                        val blue=dev.filmroom.io.OutputColorSpace.SRGB.encode8(floats.get())
                        assertEquals((0xff shl 24)or(red shl 16)or(green shl 8)or blue,preview.getPixel(x,y))
                    }
                    compared+=preview.width.toLong()*preview.height;count++
                }finally{preview.recycle()}
            }
        }finally{photo.recycle();dir.deleteRecursively()}
        assertEquals(20,count)
        println("KODACHROME120 IMPRINT_PARITY PASS cases=$count pixels=$compared")
    }

}
