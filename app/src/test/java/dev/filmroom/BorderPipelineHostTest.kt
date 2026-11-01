package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.export.*
import dev.filmroom.io.*
import dev.filmroom.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class BorderPipelineHostTest {
    /** Authored solid-blue fixture, NOT a stock imitation, trademark or production asset. */
    private class Fixture:BorderRaster {
        override val stock="fixture_only";override val version="authored-test-1"
        override val railRatio=.2;override val endRatio=.03
        override val featherTotalAt3072=4.0;override val residentBytes=12L
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb[0]=0f;rgb[1]=0f;rgb[2]=1f}
    }
    private fun snapshot(w:Int,h:Int)=Fixture().let{BorderSnapshot.create(it.stock,it.version,w,h,it.railRatio,it.endRatio,it.featherTotalAt3072)}
    private fun write(file:File,w:Int,h:Int,value:Float=.1234567f){
        val bytes=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
        repeat(w*h){bytes.putFloat(value);bytes.putFloat(.2345678f);bytes.putFloat(.3456789f)}
        file.writeBytes(bytes.array())
    }
    private fun read(file:File)=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let{b->FloatArray(b.remaining()).also{b.get(it)}}
    @Test fun userDefaultAndMigrationAreOffAndPresentationNeverCrossesEngineBoundary(){
        // Candidate capability may be enabled only after registry review; user default remains OFF.
        assertFalse(EditorState.fromJson("{}").filmBorder)
        val on=EditorState(filmBorder=true)
        assertTrue(EditorState.fromJson(on.json()).filmBorder)
        assertEquals(EditorState().json(includePresentation=false),on.json(includePresentation=false))
        val history=EditHistory();history.update(on);history.undo();assertFalse(history.current.filmBorder)
        history.redo();assertTrue(history.current.filmBorder);history.update(EditorState());assertFalse(history.current.filmBorder)
    }
    @Test fun queuePersistsVersionGeometryAndOriginalSettings(){
        val root=Files.createTempDirectory("border-jobs").toFile()
        try {
            val s=snapshot(96,64);val p=EditorState(filmStock=s.stock,filmBorder=true)
            val job=ExportJob(ExportJobStore.newId(),1,p,96,64,ExportFormat.TIFF16,95,border=s)
            ExportJobStore(root).update(job)
            val encoded=org.json.JSONObject(File(File(root,job.id),"manifest.json").readText())
            assertEquals(2,encoded.getInt("version"))
            val legacy=job.copy(id=ExportJobStore.newId(),parameters=EditorState(),border=null)
            ExportJobStore(root).update(legacy)
            assertEquals(1,org.json.JSONObject(File(File(root,legacy.id),"manifest.json").readText()).getInt("version"))
            val restored=ExportJobStore(root).get(job.id)!!
            assertEquals(s,restored.border);assertEquals(p,restored.parameters)
            assertEquals(s.outputWidth,restored.outputWidth);assertEquals(96,restored.width)
            assertEquals("25/25A",org.json.JSONObject(s.json()).getString("frame"))
            assertThrows(IllegalArgumentException::class.java){job.copy(border=null)}
            assertThrows(IllegalArgumentException::class.java){job.copy(parameters=p.copy(filmStock="other_stock"))}
        }finally{root.deleteRecursively()}
    }
    @Test fun portraitLandscapePanoramaAndLargePlanningNeverCropSource(){
        for((w,h) in listOf(6000 to 4000,4000 to 6000,12000 to 3000,3000 to 12000,3072 to 3072)){
            val s=snapshot(w,h);assertEquals(s,BorderSnapshot.fromJson(s.json()))
            assertEquals(w,s.outputWidth-2*s.left);assertEquals(h,s.outputHeight-2*s.top)
            assertTrue(s.outputBytes>w.toLong()*h*12)
            assertTrue(BorderCompositor.workingBytes(s,Fixture())<2L*1024*1024)
        }
        assertThrows(IllegalArgumentException::class.java){snapshot(30000,10000)}
        assertThrows(IllegalArgumentException::class.java){snapshot(10000,30000)}
    }
    @Test fun jpegTiffExportOnlyCeilingAllows50mpBorderAndRejectsAbove84mp(){
        val s=snapshot(8700,5800)
        for(format in listOf(ExportFormat.JPEG,ExportFormat.TIFF16))BorderExportPolicy.validate(s,format)
        val oversized=snapshot(10000,6000)
        assertTrue(oversized.outputWidth.toLong()*oversized.outputHeight>84_000_000L)
        for(format in listOf(ExportFormat.JPEG,ExportFormat.TIFF16))assertThrows(PhotoException::class.java){BorderExportPolicy.validate(oversized,format)}
        BorderExportPolicy.validate(oversized,ExportFormat.PNG)
        assertEquals(8700,s.photoWidth);assertEquals(5800,s.photoHeight)
    }
    @Test fun floatInteriorIsBitExactRetryIsIdempotentAndSourceUntouched(){
        val dir=Files.createTempDirectory("border-float").toFile()
        try {
            for((w,h) in listOf(96 to 64,64 to 96,160 to 32)) {
                val s=snapshot(w,h);val input=File(dir,"input.rgb");write(input,w,h)
                val before=input.readBytes();val out=File(dir,"output.rgb")
                BorderCompositor.composite(input,out,s,Fixture(),OutputColorSpace.SRGB)
                assertEquals(s.outputBytes,out.length());val first=out.readBytes();val values=read(out)
                val offset=((s.top+h/2)*s.outputWidth+s.left+w/2)*3
                assertEquals(.1234567f.toRawBits(),values[offset].toRawBits())
                assertEquals(.2345678f.toRawBits(),values[offset+1].toRawBits())
                BorderCompositor.composite(input,out,s,Fixture(),OutputColorSpace.SRGB)
                assertArrayEquals(first,out.readBytes());assertArrayEquals(before,input.readBytes())
                assertThrows(IllegalArgumentException::class.java){BorderCompositor.composite(out,File(dir,"twice.rgb"),s,Fixture(),OutputColorSpace.SRGB)}
            }
        }finally{dir.deleteRecursively()}
    }
    @Test fun seamChangesOnlyTwoInsidePixelsAt3072AndNotWholePhoto(){
        val s=snapshot(3072,3072);val row=FloatArray(s.outputWidth*3);val source=FloatArray(3072*3){.5f}
        BorderCompositor.composeRow(s,Fixture(),OutputColorSpace.SRGB,s.top+100,source,row)
        for(x in 0 until 3072){
            val actual=row[(s.left+x)*3]
            if(x in 2 until 3070)assertEquals(.5f.toRawBits(),actual.toRawBits())else assertTrue(actual<.5f)
        }
        assertEquals(2.0,s.featherRadius,0.0)
    }
    @Test fun cancellationNeverPublishesPartialAndDoesNotDestroyPriorOutput(){
        val dir=Files.createTempDirectory("border-cancel").toFile()
        try {
            val s=snapshot(96,64);val input=File(dir,"input.rgb");write(input,96,64)
            val out=File(dir,"output.rgb").apply{writeText("prior")};var checks=0
            assertThrows(InterruptedException::class.java){BorderCompositor.composite(input,out,s,Fixture(),OutputColorSpace.SRGB){if(++checks==5)throw InterruptedException()}}
            assertEquals("prior",out.readText());assertFalse(File(dir,"output.rgb.part").exists())
        }finally{dir.deleteRecursively()}
    }
    @Test fun borderColorConvertedButPhotographNeverReencoded(){
        val s=snapshot(96,64);val photo=FloatArray(96*3){.1234567f};val srgb=FloatArray(s.outputWidth*3);val p3=FloatArray(s.outputWidth*3)
        BorderCompositor.composeRow(s,Fixture(),OutputColorSpace.SRGB,s.top+10,photo,srgb)
        BorderCompositor.composeRow(s,Fixture(),OutputColorSpace.DISPLAY_P3,s.top+10,photo,p3)
        assertEquals(1f,srgb[2],0f);assertEquals(.91051993f,p3[2],1e-6f)
        assertEquals(photo[0].toRawBits(),p3[(s.left+10)*3].toRawBits())
    }
    @Test fun colorConversionPreservesNeutralAndGeometryEnvelopeRejectsUnapprovedGrowth(){
        for(space in OutputColorSpace.entries){
            val rgb=floatArrayOf(1f,1f,1f);BorderColor.convert(rgb,space)
            for(v in rgb)assertEquals(1f,v,1e-6f)
        }
        assertThrows(IllegalArgumentException::class.java){BorderSnapshot.create("fixture_only","test",100,100,.231,.03,4.0)}
        assertThrows(IllegalArgumentException::class.java){BorderSnapshot.create("fixture_only","test",100,100,.2,.041,4.0)}
        assertThrows(IllegalArgumentException::class.java){snapshot(100,100).copy(featherTotalAt3072=Double.NaN)}
        assertEquals(snapshot(100,100).outputBytes*3+8L*1024*1024,BorderExportPolicy.additionalDiskBytes(snapshot(100,100)))
    }
    @Test fun unsupportedOriginalOptionDoesNotPreventSafeSmallExport(){
        val provider=object:BorderProvider {
            override fun snapshot(stock:String,width:Int,height:Int)=BorderSnapshot.create(stock,"authored-test-1",width,height,.2,.03,4.0)
            override fun resolve(snapshot:BorderSnapshot):BorderRaster=Fixture()
        }
        assertFalse(BorderExportPolicy.option("fixture_only",30000,1000,true,provider).available)
        assertTrue(BorderExportPolicy.option("fixture_only",2560,85,true,provider).available)
        assertTrue(BorderExportPolicy.option("fixture_only",30000,1000,false,provider).available)
    }
    @Test fun cancellationDuringPreviewHandoffRecyclesCreatedBitmap()=kotlinx.coroutines.runBlocking {
        val job=kotlinx.coroutines.Job();var produced:Bitmap?=null
        try {
            kotlinx.coroutines.withContext(job){BorderPreview.owned {
                Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).also{produced=it;job.cancel()}
            }}
            fail("cancelled result must not be delivered")
        }catch(_:kotlinx.coroutines.CancellationException){}
        assertTrue(checkNotNull(produced).isRecycled)
    }
    @Test fun displayedFrameAspectDoesNotFollowPendingToggleOff(){
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val view=dev.filmroom.ui.PhotoView(app)
        val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
        try {
            view.configureDetail(dev.filmroom.roi.EditorDetailControllerTest.FakeProvider());view.layout(0,0,400,400)
            view.bitmap=bitmap
            val source=PhotoSource(File("unused"),"fixture","image/jpeg",600,600,0)
            view.bindDetail(source,EditorState(filmBorder=false),false,presentedBorder=true)
            assertEquals(800.0/600,view.viewport.imageWidth/view.viewport.imageHeight,1e-9)
        }finally{view.close();bitmap.recycle()}
    }
    @Test fun authoredAsymmetricAtlasPreservesCapsPitchAndPortraitRotation(){
        // 40 px photographic short, 8 px rails, 1 px ends; authored coordinate markers, not stock art.
        val pixels=IntArray(64*56){i->0xff000000.toInt() or ((i%64) shl 16) or ((i/64) shl 8) or 7}
        val raster=SlicedBorderRaster("fixture_only","atlas-1",.2,.025,4.0,40,64,56,8,1,20,44,pixels)
        val wide=BorderSnapshot.create("fixture_only","atlas-1",80,40,.2,.025,4.0)
        val portrait=BorderSnapshot.create("fixture_only","atlas-1",40,80,.2,.025,4.0)
        raster.validate(wide);raster.validate(portrait)
        val a=FloatArray(3);val b=FloatArray(3)
        raster.sample(wide,2.5,3.5,a);raster.sample(portrait,portrait.outputWidth-3.5,2.5,b)
        assertArrayEquals(a,b,0f);assertEquals(2/255f,a[0],0f);assertEquals(3/255f,a[1],0f)
        raster.sample(wide,22.5,2.5,a);raster.sample(wide,46.5,2.5,b);assertArrayEquals(a,b,0f)
        raster.sample(wide,wide.outputWidth-2.5,2.5,a);assertEquals(61/255f,a[0],0f)
        assertThrows(IllegalArgumentException::class.java){SlicedBorderRaster("fixture_only","atlas-1",.2,.025,4.0,40,64,56,5,1,20,44,pixels)}
        val capHeavy=SlicedBorderRaster("fixture_only","atlas-1",.2,.025,4.0,40,64,56,8,1,30,34,pixels)
        val square=BorderSnapshot.create("fixture_only","atlas-1",40,40,.2,.025,4.0)
        assertThrows(IllegalArgumentException::class.java){capHeavy.validate(square)}
    }
    @Test fun tracksUseAlreadyOrientedExifDimensionsAndSquareHasFixedHorizontalTracks(){
        for(orientation in 1..8){
            val display=ExifMapping(600,400,orientation)
            val s=snapshot(display.width,display.height)
            assertEquals(orientation>=5,s.portrait)
            assertEquals(display.width,s.outputWidth-2*s.left);assertEquals(display.height,s.outputHeight-2*s.top)
            if(s.portrait){assertEquals(s.rail,s.left);assertEquals(s.end,s.top)}else{assertEquals(s.end,s.left);assertEquals(s.rail,s.top)}
        }
        val square=snapshot(600,600);assertFalse(square.portrait);assertEquals(square.rail,square.top)
        val pixels=IntArray(64*56){i->0xff000000.toInt() or ((i%64)shl 16) or ((i/64)shl 8)}
        val atlas=SlicedBorderRaster("fixture_only","atlas-exif",.2,.025,4.0,40,64,56,8,1,20,44,pixels)
        atlas.validate(BorderSnapshot.create("fixture_only","atlas-exif",600,600,.2,.025,4.0))
        for((w,h)in listOf(29500 to 100,100 to 29500)){
            val s=BorderSnapshot.create("fixture_only","atlas-exif",w,h,.2,.025,4.0);atlas.validate(s)
            val scale=minOf(w,h)/40.0;val a=FloatArray(3);val b=FloatArray(3)
            val x=22.5*scale;val y=2.5*scale;val pitch=24*scale
            if(s.portrait){atlas.sample(s,s.outputWidth-y,x,a);atlas.sample(s,s.outputWidth-y,x+pitch,b)}
            else{atlas.sample(s,x,y,a);atlas.sample(s,x+pitch,y,b)}
            assertArrayEquals("Physical repeat pitch must not stretch for aspect $w x $h",a,b,0f)
            assertEquals(w,s.outputWidth-2*s.left);assertEquals(h,s.outputHeight-2*s.top)
        }
    }
    @Test fun previewUsesSameRowCompositeAsFloatExport(){
        val dir=Files.createTempDirectory("border-preview").toFile();val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff808080.toInt())
        try {
            val s=snapshot(96,64);val preview=BorderPreview.compose(bitmap,s,Fixture())
            try {
                val linear=((128/255.0+.055)/1.055).let{Math.pow(it,2.4)}.toFloat()
                val input=File(dir,"input.rgb");val bytes=ByteBuffer.allocate(96*64*12).order(ByteOrder.LITTLE_ENDIAN)
                repeat(96*64*3){bytes.putFloat(linear)};input.writeBytes(bytes.array())
                val out=File(dir,"output.rgb");BorderCompositor.composite(input,out,s,Fixture(),OutputColorSpace.SRGB)
                val values=read(out)
                for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                    val at=(y*s.outputWidth+x)*3
                    val expected=(0xff shl 24)or(OutputColorSpace.SRGB.encode8(values[at])shl 16)or(OutputColorSpace.SRGB.encode8(values[at+1])shl 8)or OutputColorSpace.SRGB.encode8(values[at+2])
                    assertEquals(expected,preview.getPixel(x,y))
                }
            }finally{preview.recycle()}
        }finally{bitmap.recycle();dir.deleteRecursively()}
    }
}
