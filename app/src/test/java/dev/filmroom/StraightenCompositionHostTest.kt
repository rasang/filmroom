package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Color
import dev.filmroom.model.*
import dev.filmroom.crop.*
import dev.filmroom.border.*
import dev.filmroom.io.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
class StraightenCompositionHostTest {
    @Test fun everyCornerStaysInsideSourceAcrossModesOrientationsPansAndZooms(){
        for((w,h)in listOf(6000 to 4000,4000 to 6000,5000 to 5000,30000 to 2))
            for(aspect in listOf(1.0,1.5,2.0/3,16.0/9,9.0/16,w.toDouble()/h))
                for(angle in listOf(-15.0,-3.7,0.0,8.1,15.0))for(zoom in listOf(1.0,2.3,16.0)){
                    val t=CompositionTransform(0.0,1.0,zoom,aspect,angle).clamped(w,h)
                    for(u in listOf(0.0,1.0))for(v in listOf(0.0,1.0)){
                        val p=t.sourcePoint(u,v,w,h)
                        assertTrue("$w x $h $t $p",p.first>=-1e-9 && p.first<=1+1e-9 && p.second>=-1e-9 && p.second<=1+1e-9)
                    }
                    val r=t.rect(w,h);assertEquals(aspect,r.width*w/(r.height*h),1e-8)
                }
    }
    @Test fun oldSerializationAndScienceRemainUnchangedAndNewAngleRoundTrips(){
        for(t in listOf(CompositionTransform(),CompositionTransform(targetAspect=1.5))){
            assertFalse(t.json().has("straightenDegrees"));assertTrue(t.json().getInt("version")<=2)
            assertEquals(t,CompositionTransform.fromJson(t.json()))
        }
        val t=CompositionTransform(.5,.5,1.3,1.5,-8.2)
        assertEquals(3,t.json().getInt("version"));assertEquals(t,CompositionTransform.fromJson(t.json()))
        val p=EditorState(composition=t,crop=CropSettings(CropMode.FREE,t))
        assertEquals(p,EditorState.fromJson(p.json()));assertEquals(EditorState().json(includePresentation=false),p.json(includePresentation=false))
        for(version in 1..2){
            val malformed=t.json().put("version",version)
            try{CompositionTransform.fromJson(malformed);fail("Legacy parser accepted nonzero angle")}catch(_:IllegalArgumentException){}
        }
        for(angle in listOf(Double.NaN,16.0,-16.0,Double.POSITIVE_INFINITY))try{CompositionTransform(straightenDegrees=angle);fail("Invalid angle")}catch(_:IllegalArgumentException){}
    }
    @Test fun automaticCoverDoesNotAccumulateAndPinchPreservesSourceFocus(){
        val t=CompositionTransform(zoom=2.0,targetAspect=1.5)
        val tilted=t.copy(straightenDegrees=10.0).clamped(600,400)
        assertEquals(t,tilted.copy(straightenDegrees=0.0).clamped(600,400))
        val p=tilted.sourcePoint(.6,.4,600,400);val z=tilted.zoomBy(1.5,.6,.4,600,400);val q=z.sourcePoint(.6,.4,600,400)
        assertEquals(p.first,q.first,1e-9);assertEquals(p.second,q.second,1e-9)
        val moved=tilted.pan(20.0,0.0,300.0,200.0,600,400)
        assertTrue(moved.centerX<tilted.centerX);assertTrue(moved.centerY>tilted.centerY)
    }
    @Test fun cropPreviewAndFloatExportUseTheSameInverseRotation(){
        val w=96;val h=64
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val bytes=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until h)for(x in 0 until w){
            val c=Color.rgb(x*255/(w-1),y*255/(h-1),90);bitmap.setPixel(x,y,c)
            bytes.putFloat(LinearRgb.srgb8ToLinear[Color.red(c)]);bytes.putFloat(LinearRgb.srgb8ToLinear[Color.green(c)]);bytes.putFloat(LinearRgb.srgb8ToLinear[Color.blue(c)])
        }
        val source=File.createTempFile("straighten",".rgb");val out=File.createTempFile("straighten-result",".rgb")
        try{
            source.writeBytes(bytes.array())
            for(angle in listOf(-15.0,-3.7,0.0,8.1,15.0)){
                val s=CropSnapshot.create(w,h,settings=CropSettings(CropMode.RATIO_3_2,CompositionTransform(straightenDegrees=angle)))
                val preview=CropPreview.compose(bitmap,s)
                CropCompositor.composite(source,out,s)
                val exported=ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                    val actual=Color.rgb(OutputColorSpace.SRGB.encode8(exported.get()),OutputColorSpace.SRGB.encode8(exported.get()),OutputColorSpace.SRGB.encode8(exported.get()))
                    assertEquals("angle=$angle ($x,$y)",actual,preview.getPixel(x,y))
                }
                if(angle!=0.0)assertFalse(s.isIdentity)
                assertEquals(s,CropSnapshot.fromJson(s.json()));preview.recycle()
            }
        }finally{source.delete();out.delete();bitmap.recycle()}
    }
    @Test fun rotatedFloatExportPreservesHdrValuesAndCancellationIsAtomic(){
        val w=80;val h=60;val source=File.createTempFile("straighten-hdr",".rgb");val output=File.createTempFile("straighten-cancel",".rgb")
        val data=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until h)for(x in 0 until w){data.putFloat(x/(w-1f)*3-1);data.putFloat(y/(h-1f)*2);data.putFloat(1.234567f)}
        source.writeBytes(data.array());val marker=byteArrayOf(1,3,5,7)
        val settings=CropSettings(CropMode.RATIO_4_3,CompositionTransform(straightenDegrees=-7.3))
        val snapshot=CropSnapshot.create(w,h,settings=settings)
        try{
            CropCompositor.composite(source,output,snapshot)
            val actualBytes=output.readBytes()
            val values=ByteBuffer.wrap(actualBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            // Pre-imaging's direct walk adapter omits sourceHeight: the frozen map must supply it.
            val map=CropCompositor.map(snapshot);val original=ByteBuffer.wrap(data.array()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val walked=ArrayList<Float>()
            FinalImageRows.walk(w,snapshot.outputWidth,snapshot.outputHeight,map,{},read={y,row->original.position(y*w*3);original.get(row)},
                compose={_,first,second,fy,row->CropCompositor.row(map,first,second,fy,row)},write={_,row->row.forEach{walked.add(it)}})
            val expected=FloatArray(walked.size);ByteBuffer.wrap(actualBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected)
            assertArrayEquals(expected,walked.toFloatArray(),0f)
            val transform=settings.effectiveTransform(w,h)
            for(y in 0 until snapshot.outputHeight)for(x in 0 until snapshot.outputWidth){
                val point=transform.sourcePoint((x+.5)/snapshot.outputWidth,(y+.5)/snapshot.outputHeight,w,h)
                val sx=(point.first*w-.5).coerceIn(0.0,w-1.0);val sy=(point.second*h-.5).coerceIn(0.0,h-1.0)
                assertEquals(sx/(w-1)*3-1,values.get().toDouble(),3e-7);assertEquals(sy/(h-1)*2,values.get().toDouble(),3e-7);assertEquals(1.234567f,values.get(),0f)
            }
            output.writeBytes(marker);var checks=0
            try{CropCompositor.composite(source,output,snapshot,checkActive={if(++checks==8)throw java.util.concurrent.CancellationException()});fail("Cancellation ignored")}catch(_:java.util.concurrent.CancellationException){}
            assertArrayEquals(marker,output.readBytes());assertArrayEquals(data.array(),source.readBytes());assertFalse(File(output.parentFile,output.name+".part").exists())
        }finally{source.delete();output.delete()}
    }
    @Test fun zeroDegreeKeepsOldMemoryEstimateAndPreviewRotationCacheIsSmall(){
        val plain=CropSnapshot.create(1536,1024)
        assertEquals(1536*36L+1536*36L+128L*1024,CropCompositor.workingBytes(plain))
        val rotated=CropSnapshot.create(1536,1024,settings=CropSettings(transform=CompositionTransform(straightenDegrees=15.0)))
        val extra=CropCompositor.workingBytes(rotated)-(1536*36L+rotated.outputWidth*36L+128L*1024)
        assertTrue(extra>0);assertTrue(extra<3L*1024*1024)
        assertTrue(FinalImageTileCache.workingBytes(30000,5000)<=FinalImageTileCache.MAX_BYTES)
    }
    @Test fun savedPresetAndQrRetainNonzeroAngleAndLegacyCodesDefaultToZero(){
        val p=EditorState(composition=CompositionTransform(straightenDegrees=9.7),crop=CropSettings(CropMode.FREE,CompositionTransform(zoom=2.0,targetAspect=1.2,straightenDegrees=-4.6)))
        val code=dev.filmroom.presets.PresetQrCodec.encode("Straighten",p)
        assertEquals(p,dev.filmroom.presets.PresetQrCodec.decode(code).parameters)
        val invalid=JSONObject(p.json()).apply{getJSONObject("composition").put("straightenDegrees",JSONObject.NULL)}
        try{dev.filmroom.presets.PresetQrParameters.read(invalid);fail("Null rotation accepted")}catch(_:dev.filmroom.presets.PresetQrCodec.Failure){}
        val old=checkNotNull(javaClass.getResourceAsStream("/presets/qr-v1-code54.txt")).bufferedReader().use{it.readText().trim()}
        val decoded=dev.filmroom.presets.PresetQrCodec.decode(old).parameters
        assertEquals(0.0,decoded.composition.straightenDegrees,0.0);assertEquals(0.0,decoded.crop.transform.straightenDegrees,0.0)
    }
    @Test fun borderFreezeRetainsAngleAndLegacySnapshotsStayExact(){
        for(angle in listOf(-15.0,0.0,7.3)){
            val s=BorderSnapshot.fixed135("fixture","v",600,400,composition=CompositionTransform(straightenDegrees=angle))
            assertEquals(s,BorderSnapshot.fromJson(s.json()));assertEquals(angle,s.composition.straightenDegrees,0.0)
        }
        val identity=CropSnapshot.create(96,64)
        assertTrue(identity.isIdentity);assertEquals(96,identity.outputWidth);assertEquals(64,identity.outputHeight)
    }
}
