package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.imprint.*
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
class PreImagingInputHostTest {
    private class Fixture:BorderRaster {
        override val stock="fixture_only";override val version="input-v1"
        override val railRatio=.2;override val endRatio=.03
        override val featherTotalAt3072=0.0;override val residentBytes=12L
        override fun holeCoverage(snapshot:BorderSnapshot,x:Double,y:Double)=if(y<1)1.0 else if(y<2)0.5 else 0.0
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){
            val h=holeCoverage(snapshot,x,y)
            val v=OutputColorSpace.SRGB.encode8((.2*(1-h)+h).toFloat())/255f
            rgb[0]=v;rgb[1]=v;rgb[2]=v
        }
    }
    private fun snapshot(w:Int=96,h:Int=64)=BorderSnapshot.create("fixture_only","input-v1",w,h,.2,.03,0.0)
    private fun data(w:Int,h:Int)=FloatArray(w*h*3){i->when(i%3){0->2.345678f;1->.1234567f;else->.2345678f}}
    private fun buffer(values:FloatArray,direct:Boolean)=
        (if(direct)ByteBuffer.allocateDirect(values.size*4)else ByteBuffer.allocate(values.size*4)).order(ByteOrder.nativeOrder()).apply{values.forEach(::putFloat);rewind()}
    private fun read(file:File)=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let{f->FloatArray(f.remaining()).also(f::get)}
    private fun write(file:File,values:FloatArray){val b=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN);values.forEach(b::putFloat);file.writeBytes(b.array())}
    @Test fun explicitZoomUsesRealHigherSamplesAndMatchesSameGridExport() {
        val s=snapshot(1800,200);val values=data(s.sourceWidth,s.sourceHeight);val raster=Fixture()
        assertTrue(maxOf(s.outputWidth,s.outputHeight)>1536)
        val input=LinearPreview(s.sourceWidth,s.sourceHeight,buffer(values,true))
        assertThrows(IllegalArgumentException::class.java){PreImagingInput.refinedPreview(null,input,s,raster)}
        val dir=Files.createTempDirectory("zoom-composite").toFile()
        try {
            val original=File(dir,"original.rgb");write(original,values)
            val composed=File(dir,"composed.rgb");PreImagingInput.file(original,composed,s,raster)
            val zoom=PreImagingInput.zoomPreview(null,input,s,raster,null,2048)
            assertEquals(s.outputWidth,zoom.width);assertEquals(s.outputHeight,zoom.height)
            val pixels=zoom.buffer().asFloatBuffer();val actual=FloatArray(pixels.remaining()).also(pixels::get)
            assertArrayEquals(read(composed),actual,0f)
        } finally {dir.deleteRecursively()}
    }
    @Test fun floatPreviewAndExportShareExactUpstreamPixelsAndKeepHdr(){
        val s=snapshot();val values=data(s.sourceWidth,s.sourceHeight);val raster=Fixture()
        val dir=Files.createTempDirectory("preimaging-input").toFile()
        try {
            val original=File(dir,"original.rgb");write(original,values);val before=original.readBytes();val composed=File(dir,"composed.rgb")
            PreImagingInput.file(original,composed,s,raster)
            val result=PreImagingInput.preview(null,LinearPreview(s.sourceWidth,s.sourceHeight,buffer(values,true)),s,raster).buffer().asFloatBuffer()
            val preview=FloatArray(result.remaining()).also(result::get)
            assertArrayEquals(read(composed),preview,0f)
            assertArrayEquals(before,original.readBytes());assertEquals(s.outputBytes,composed.length())
            val center=((s.top+s.photoHeight/2)*s.outputWidth+s.left+s.photoWidth/2)*3
            assertEquals(2.345678f.toRawBits(),preview[center].toRawBits())
            assertEquals(.1234567f.toRawBits(),preview[center+1].toRawBits())
            assertEquals(0f,preview[0],0f) // clear hole has no scene flux
            assertTrue(preview[s.outputWidth*3] < .21f) // matte removed, not sent as a lamp
            val restored=File(dir,"restored.rgb");PreImagingInput.restoreHoles(composed,restored,s,raster)
            val final=read(restored);assertEquals(1f,final[0],0f)
            assertEquals(preview[center].toRawBits(),final[center].toRawBits())
        }finally{dir.deleteRecursively()}
    }
    @Test fun bitmapInputIsLinearizedOnceAndHoleOnlyRestoreDoesNotTouchPhoto(){
        val s=snapshot();val input=Bitmap.createBitmap(s.sourceWidth,s.sourceHeight,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff4080c0.toInt())}
        val output=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff4080c0.toInt())}
        try {
            val values=PreImagingInput.preview(input,null,s,Fixture()).buffer().asFloatBuffer()
            val center=((s.top+10)*s.outputWidth+s.left+10)*3
            assertEquals(LinearRgb.srgb8ToLinear[64],values.get(center),0f)
            assertEquals(LinearRgb.srgb8ToLinear[128],values.get(center+1),0f)
            PreImagingInput.restoreHoles(output,s,Fixture())
            assertEquals(-1,output.getPixel(10,0));assertEquals(0xff4080c0.toInt(),output.getPixel(s.left+10,s.top+10))
        }finally{input.recycle();output.recycle()}
    }
    @Test fun captureDateIsAlreadyPresentInTheNativeInput(){
        val s=snapshot(300,200);val values=data(s.sourceWidth,s.sourceHeight)
        val source=LinearPreview(s.sourceWidth,s.sourceHeight,buffer(values,true))
        val settings=ImprintSettings(dateStyle="contax_t2",manualDateTime="2001-02-03 04:05:06")
        val plan=ImprintPlan.create(settings,PhotoMetadata.unavailable(),s.outputWidth,s.outputHeight,s)
        assertFalse(plan.isEmpty)
        val plain=PreImagingInput.preview(null,source,s,Fixture()).buffer().asFloatBuffer()
        val dated=PreImagingInput.preview(null,source,s,Fixture(),plan).buffer().asFloatBuffer()
        assertTrue((0 until plain.limit()).any{plain.get(it).toRawBits()!=dated.get(it).toRawBits()})
    }
    @Test fun oldFrozenJobsKeepPostStageNewEnableUsesPreStageAndIdentityChanges(){
        val old=EditorState.fromJson("{\"filmBorder\":true}")
        assertEquals(0,old.filmBorderStage);assertFalse(old.preImagingBorder)
        val current=old.selectFilmBorder(true)
        assertEquals(1,current.filmBorderStage);assertTrue(current.preImagingBorder)
        assertEquals(current,EditorState.fromJson(current.json()))
        assertNotEquals(old.json(),current.json())
        assertEquals(old.json(includePresentation=false),current.json(includePresentation=false))
        assertThrows(IllegalArgumentException::class.java){EditorState(filmBorderStage=2)}
        for(value in listOf("1.5","\"1\"","true","2","-1"))assertThrows(IllegalArgumentException::class.java){EditorState.fromJson("{\"filmBorderStage\":$value}")}
        val s=snapshot();val contract=PreImagingInput.nativeJson(s,File("/owned/scene.rgb"))
        assertEquals(s.outputWidth,contract.getInt("frameWidth"));assertEquals(s.photoWidth,contract.getInt("photoWidth"))
        assertEquals(s.sourceWidth,contract.getInt("meteringWidth"));assertFalse(PreImagingInput.nativeJson(s).has("meteringPath"))
    }
    @Test fun liveSessionMigrationIsExplicitAndDoesNotRewriteSavedRecipesOrQueueParameters(){
        val frozenJson="{\"filmBorder\":true,\"filmBorderStage\":0,\"brightness\":0.25}"
        val saved=EditorState.fromJson(frozenJson)
        val live=saved.forLiveSessionRestore()
        assertTrue(live.preImagingBorder)
        assertEquals(saved.copy(filmBorderStage=1),live)
        assertEquals(0,saved.filmBorderStage)
        assertEquals(0,EditorState.fromJson(frozenJson).filmBorderStage)
        assertSame(live,live.forLiveSessionRestore())
        val disabled=EditorState(filmBorder=false,filmBorderStage=0)
        assertSame(disabled,disabled.forLiveSessionRestore())
        assertEquals(1,live.processingDefaults(live.paperStock,live.outputRoute).filmBorderStage)
        assertEquals(0,saved.processingDefaults(saved.paperStock,saved.outputRoute).filmBorderStage)
    }
    @Test fun pendingOriginalDiskBudgetDoesNotShrinkWithAPanoramaCrop(){
        val s=BorderSnapshot.fixed135("fixture_only","mask-v1",30_000,1000,featherTotalAt3072=0.0)
        val n=s.outputWidth.toLong()*s.outputHeight
        val plan=dev.filmroom.engine.NativeExportPlan(s.outputWidth,s.outputHeight,n*12,n*12,0,n*24)
        val pending=PreImagingInput.nativeAndPendingSourceDiskBytes(plan,s,false)
        val ready=PreImagingInput.nativeAndPendingSourceDiskBytes(plan,s,true)
        assertEquals(30_000L*1000*12,pending-ready)
        assertTrue(pending>dev.filmroom.engine.ExportStoragePolicy.beforeInput(plan))
    }
    @Test fun cancellationDoesNotPublishPartialOrOverwriteSourceOrPriorDestination(){
        val s=snapshot();val dir=Files.createTempDirectory("preimaging-cancel").toFile()
        try {
            val source=File(dir,"source.rgb");write(source,data(s.sourceWidth,s.sourceHeight));val before=source.readBytes()
            val target=File(dir,"target.rgb").apply{writeText("prior")};var checks=0
            assertThrows(InterruptedException::class.java){PreImagingInput.file(source,target,s,Fixture()){if(++checks==4)throw InterruptedException()}}
            assertEquals("prior",target.readText());assertArrayEquals(before,source.readBytes());assertFalse(File(dir,"target.rgb.part").exists())
            PreImagingInput.file(source,target,s,Fixture());val completed=target.readBytes();var masks=0
            val final=File(dir,"final.rgb").apply{writeText("old-final")}
            assertThrows(InterruptedException::class.java){PreImagingInput.restoreHoles(target,final,s,Fixture()){if(++masks==4)throw InterruptedException()}}
            assertEquals("old-final",final.readText());assertArrayEquals(completed,target.readBytes());assertFalse(File(dir,"final.rgb.part").exists())
        }finally{dir.deleteRecursively()}
    }
    @Test fun realStraightAlphaMaskDoesNotMistakeThePhotoApertureForAHole(){
        val width=76;val height=70;val argb=IntArray(width*height){0xff303030.toInt()}
        for(y in 11 until 59)for(x in 2 until 74)argb[y*width+x]=0
        for(y in 2..5)for(x in 30..35)argb[y*width+x]=0
        val raster=StraightAlphaFixed135Raster("fixture_only","mask-v1",width,height,argb,0.0)
        val s=BorderSnapshot.fixed135("fixture_only","mask-v1",72,48,featherTotalAt3072=0.0)
        assertEquals(0.0,raster.holeCoverage(s,38.5,35.5),0.0)
        assertEquals(1.0,raster.holeCoverage(s,32.5,3.5),0.0)
        assertEquals(0.0,raster.holeCoverage(s,45.5,3.5),0.0)
    }
    @Test fun refinedCompositeUsesSeparate1536ContractWithoutQuantizingLinearInput() {
        val s=snapshot(900,600);val raster=Fixture();val values=data(900,600)
        val linear=LinearPreview(900,600,buffer(values,true))
        try{PreImagingInput.preview(null,linear,s,raster);fail("ordinary limit widened")}catch(_:IllegalArgumentException){}
        val refined=PreImagingInput.refinedPreview(null,linear,s,raster)
        assertEquals(s.outputWidth,refined.width);assertEquals(s.outputHeight,refined.height)
        assertTrue(maxOf(refined.width,refined.height)>768)
        assertTrue(maxOf(refined.width,refined.height)<=1536)
        val center=((s.top+s.photoHeight/2)*s.outputWidth+s.left+s.photoWidth/2)*3
        assertEquals(2.345678f,refined.buffer().asFloatBuffer().get(center),0f)
        var checked=false
        try {PreImagingInput.refinedPreview(null,linear,s,raster){checked=true;throw kotlinx.coroutines.CancellationException()};fail("ignored cancel")}
        catch(_:kotlinx.coroutines.CancellationException){}
        assertTrue(checked)
    }

}
