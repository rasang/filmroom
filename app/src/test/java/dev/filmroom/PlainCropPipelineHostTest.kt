package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.crop.*
import dev.filmroom.model.*
import dev.filmroom.io.*
import dev.filmroom.export.*
import org.json.JSONObject
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
class PlainCropPipelineHostTest {
    private fun floats(file:File)=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let{b->FloatArray(b.remaining()).also{b.get(it)}}
    private fun write(file:File,w:Int,h:Int,value:(Int,Int,Int)->Float){
        val b=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until h)for(x in 0 until w)repeat(3){b.putFloat(value(x,y,it))};file.writeBytes(b.array())
    }
    @Test fun identityIsBitExactAndRatioCropUsesActualSourceWindowWithoutUpscaling(){
        val root=Files.createTempDirectory("plain-crop").toFile()
        try {
            val source=File(root,"source.rgb");write(source,96,64){x,y,c->(x+y*96+c*.1234567).toFloat()}
            val original=source.readBytes();val identity=CropSnapshot.create(96,64)
            assertTrue(identity.isIdentity);assertEquals(96,identity.outputWidth);assertEquals(64,identity.outputHeight)
            val output=File(root,"output.rgb");CropCompositor.composite(source,output,identity);assertArrayEquals(original,output.readBytes())
            val square=CropSnapshot.create(96,64,settings=CropSettings(CropMode.SQUARE))
            assertEquals(64,square.outputWidth);assertEquals(64,square.outputHeight)
            CropCompositor.composite(source,output,square);val values=floats(output)
            for(y in 0 until 64)for(x in 0 until 64)repeat(3){c->assertEquals((x+16+y*96+c*.1234567).toFloat(),values[(y*64+x)*3+c],1e-5f)}
            assertArrayEquals(original,source.readBytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun fractionalCropSharesAnalyticBilinearMapAndPreviewTransfer(){
        val root=Files.createTempDirectory("plain-crop-parity").toFile();val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        try {
            for(y in 0 until 64)for(x in 0 until 96)bitmap.setPixel(x,y,android.graphics.Color.rgb(x*2,y*3,(x+y)%256))
            val source=File(root,"source.rgb");write(source,96,64){x,y,c->val color=bitmap.getPixel(x,y);LinearRgb.srgb8ToLinear[(color ushr (16-c*8))and 255]}
            val crop=CropSettings.fromRect(CompositionTransform.Rect(.17,.13,.61,.67),96,64)
            val snapshot=CropSnapshot.create(96,64,settings=crop);val out=File(root,"cropped.rgb")
            CropCompositor.composite(source,out,snapshot);val values=floats(out)
            val preview=CropPreview.compose(bitmap,snapshot)
            try {for(y in 0 until snapshot.outputHeight)for(x in 0 until snapshot.outputWidth){
                val c=preview.getPixel(x,y);for(channel in 0..2)assertEquals(OutputColorSpace.SRGB.encode8(values[(y*snapshot.outputWidth+x)*3+channel]),(c ushr(16-channel*8))and 255)
            }}finally{preview.recycle()}
            // Independent linear gradient oracle exercises fractional interpolation without RGB quantization.
            write(source,96,64){x,y,c->x*.01f+y*.001f+c*.1f};CropCompositor.composite(source,out,snapshot)
            val gradient=floats(out);val rect=snapshot.sourceRect
            for(y in 0 until snapshot.outputHeight)for(x in 0 until snapshot.outputWidth){
                val sx=(rect.left+(x+.5)/snapshot.outputWidth*rect.width)*96-.5
                val sy=(rect.top+(y+.5)/snapshot.outputHeight*rect.height)*64-.5
                for(c in 0..2)assertEquals((sx*.01+sy*.001+c*.1).toFloat(),gradient[(y*snapshot.outputWidth+x)*3+c],2e-7f)
            }
        }finally{bitmap.recycle();root.deleteRecursively()}
    }
    @Test fun presetsAndCanonicalOrientationHaveBoundedRoundingAndSnapshotStrictness(){
        for((w,h) in listOf(1536 to 1024,1024 to 1536,1000 to 1000))for(mode in CropMode.entries.filter{it.aspect!=null}){
            val s=CropSnapshot.create(w,h,w*3,h*3,CropSettings(mode,CompositionTransform(.6,.4,1.7)))
            assertTrue(s.outputWidth<=w*s.sourceRect.width+1e-8);assertTrue(s.outputHeight<=h*s.sourceRect.height+1e-8)
            assertTrue(kotlin.math.abs(s.outputWidth-s.outputHeight*mode.aspect!!)<maxOf(1.0,mode.aspect!!)+1e-8)
            assertEquals(s,CropSnapshot.fromJson(s.json()))
            assertThrows(IllegalArgumentException::class.java){CropSnapshot.fromJson(JSONObject(s.json()).put("outputWidth",s.outputWidth+1).toString())}
        }
        val huge=CropSnapshot.create(9000,5700,settings=CropSettings(CropMode.SQUARE))
        assertTrue(CropCompositor.workingBytes(huge)<2L*1024*1024)
    }
    @Test fun cancellationRemovesPartialAndPreservesSourceAndCommittedDestination(){
        val root=Files.createTempDirectory("plain-crop-cancel").toFile()
        try {
            val source=File(root,"source.rgb");write(source,96,64){_,_,_->.1234567f};val bytes=source.readBytes()
            val out=File(root,"output.rgb");val old=byteArrayOf(1,2,3);out.writeBytes(old);var rows=0
            assertThrows(java.util.concurrent.CancellationException::class.java){CropCompositor.composite(source,out,CropSnapshot.create(96,64,settings=CropSettings(CropMode.SQUARE))){if(++rows==4)throw java.util.concurrent.CancellationException()}}
            assertFalse(File(root,"output.rgb.part").exists());assertArrayEquals(old,out.readBytes());assertArrayEquals(bytes,source.readBytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun newCropQueueVersionFourFreezesSettingsAndOldUnframedVersionOneStaysIdentity(){
        val root=Files.createTempDirectory("plain-crop-queue").toFile()
        try {
            val settings=CropSettings(CropMode.RATIO_4_3,CompositionTransform(.55,.45,1.5));val params=EditorState(crop=settings)
            val crop=CropSnapshot.create(96,64,192,128,settings)
            val queued=ExportJob(ExportJobStore.newId(),1,params,96,64,ExportFormat.TIFF16,95,sourceWidth=192,sourceHeight=128,crop=crop)
            val store=ExportJobStore(root);store.update(queued)
            val file=File(store.directory(queued.id),"manifest.json");val saved=file.readText();assertEquals(4,JSONObject(saved).getInt("version"))
            val restored=ExportJobStore(root).get(queued.id)!!;assertEquals(crop,restored.crop);assertEquals(settings,restored.parameters.crop)
            assertEquals(crop.outputWidth,restored.outputWidth);assertEquals(crop.outputHeight,restored.outputHeight)
            assertThrows(IllegalArgumentException::class.java){queued.copy(parameters=EditorState())}
            val legacy=ExportJob(ExportJobStore.newId(),2,EditorState(),96,64,ExportFormat.PNG,95);store.update(legacy)
            assertEquals(1,JSONObject(File(store.directory(legacy.id),"manifest.json").readText()).getInt("version"))
            assertNull(ExportJobStore(root).get(legacy.id)!!.crop);assertEquals(96,ExportJobStore(root).get(legacy.id)!!.outputWidth)
            file.writeText(JSONObject(saved).put("version",3).toString());val invalid=ExportJobStore(root).get(queued.id)!!
            assertEquals(ExportJobState.FAILED,invalid.state);assertFalse(invalid.inputReady)
        }finally{root.deleteRecursively()}
    }
}
