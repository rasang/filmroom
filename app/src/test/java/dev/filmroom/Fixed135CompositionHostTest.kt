package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.border.*
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

/** Authored mathematical fixtures only; these tests do not establish stock authenticity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class Fixed135CompositionHostTest {
    private class Fixture(override val featherTotalAt3072:Double=4.0):BorderRaster {
        override val stock="fixed135_fixture";override val version="authored-test-v2"
        override val railRatio=.2;override val endRatio=.03;override val residentBytes=12L
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb[0]=.1f;rgb[1]=.2f;rgb[2]=.3f}
    }
    private fun snapshot(w:Int,h:Int,c:CompositionTransform=CompositionTransform(),canonicalW:Int=w,canonicalH:Int=h,feather:Double=4.0)=
        BorderSnapshot.fixed135("fixed135_fixture","authored-test-v2",w,h,canonicalW,canonicalH,c,feather)
    private fun write(file:File,w:Int,h:Int,pixel:(Int,Int,Int)->Float){
        file.outputStream().buffered().use { output->
            val row=ByteBuffer.allocate(w*12).order(ByteOrder.LITTLE_ENDIAN)
            for(y in 0 until h){row.clear();for(x in 0 until w)for(c in 0..2)row.putFloat(pixel(x,y,c));output.write(row.array())}
        }
    }
    private fun read(file:File)=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let{b->FloatArray(b.remaining()).also{b.get(it)}}

    @Test fun fixedApertureAndUniformPhysicalFrameForLandscapePortraitSquareAndExtremeAspect(){
        for((w,h) in listOf(6000 to 4000,4000 to 6000,4000 to 4000,30000 to 100,100 to 30000,1 to 1)){
            val s=snapshot(w,h);val p=h>w
            assertEquals(p,s.portrait)
            assertEquals(if(p)2.0/3 else 1.5,s.photoWidth.toDouble()/s.photoHeight,1e-12)
            assertEquals(36.0,s.photoWidth/s.pixelsPerMm*(if(p)1.5 else 1.0),1e-10)
            assertEquals(s.pixelsPerMm,(s.apertureLeft-s.frameOriginX)/(if(p)5.5 else 1.0),1e-10)
            assertEquals(s.pixelsPerMm,(s.apertureTop-s.frameOriginY)/(if(p)1.0 else 5.5),1e-10)
            assertTrue(s.outputWidth-s.frameWidthMm*s.pixelsPerMm in 0.0..1.0)
            assertTrue(s.outputHeight-s.frameHeightMm*s.pixelsPerMm in 0.0..1.0)
            assertTrue(s.apertureLeft>=0 && s.apertureTop>=0)
            assertTrue(s.apertureLeft+s.photoWidth<=s.outputWidth && s.apertureTop+s.photoHeight<=s.outputHeight)
            assertEquals(s,BorderSnapshot.fromJson(s.json()))
        }
        val landscape=snapshot(6000,4000);val portrait=snapshot(4000,6000)
        assertEquals(landscape.outputWidth,portrait.outputHeight);assertEquals(landscape.outputHeight,portrait.outputWidth)
        assertEquals(landscape.pixelsPerMm,portrait.pixelsPerMm,0.0)
        assertFalse(snapshot(4000,4000).portrait)
        assertTrue(BorderCompositor.workingBytes(snapshot(8800,5900),Fixture())<2L*1024*1024)
    }

    @Test fun allEightExifOrientationsChooseRailsFromDisplayDimensionsWithoutSecondTransform(){
        for(orientation in 1..8){
            val exif=ExifMapping(120,80,orientation)
            val s=snapshot(exif.width,exif.height)
            assertEquals(orientation>=5,s.portrait)
            assertEquals(exif.width,s.canonicalWidth);assertEquals(exif.height,s.canonicalHeight)
            for((x,y) in listOf(0 to 0,(exif.width-1) to (exif.height-1))){
                assertTrue(exif.rawX(x,y) in 0 until 120);assertTrue(exif.rawY(x,y) in 0 until 80)
            }
        }
    }

    @Test fun coverPanAndZoomAreBoundedAndFocalPointIsStableBeforeClamping(){
        for((w,h) in listOf(6000 to 4000,4000 to 6000,4000 to 4000,30000 to 100,100 to 30000)){
            var c=CompositionTransform()
            for(i in 0..20){
                c=c.zoomBy(if(i%2==0)3.0 else .2,.17,.81,w,h).pan(1e12,-1e12,401.0,307.0,w,h)
                val r=c.rect(w,h)
                assertTrue(r.left>=0 && r.top>=0 && r.right<=1.0+1e-12 && r.bottom<=1.0+1e-12)
                assertEquals(if(w>=h)1.5 else 2.0/3,r.width*w/(r.height*h),1e-10)
                assertTrue(c.zoom in 1.0..16.0)
            }
        }
        val initial=CompositionTransform(.5,.5,2.0);val before=initial.rect(6000,4000)
        val after=initial.zoomBy(1.5,.2,.7,6000,4000).rect(6000,4000)
        assertEquals(before.left+.2*before.width,after.left+.2*after.width,1e-12)
        assertEquals(before.top+.7*before.height,after.top+.7*after.height,1e-12)
        assertEquals(initial,initial.pan(Double.NaN,0.0,200.0,200.0,6000,4000))
        assertEquals(initial,initial.zoomBy(Double.POSITIVE_INFINITY,.5,.5,6000,4000))
    }

    @Test fun canonicalAspectAndCropStayIdenticalAcrossRoundedPreviewAndExportGrids(){
        val c=CompositionTransform(.63,.48,2.3)
        val preview=snapshot(1000,667,c,6001,4000);val export=snapshot(6001,4000,c)
        assertEquals(export.sourceRect,preview.sourceRect)
        assertEquals(1.5,preview.photoWidth.toDouble()/preview.photoHeight,0.0)
        val state=EditorState(filmBorder=true,composition=c)
        assertEquals(state,EditorState.fromJson(state.json()))
        assertEquals(EditorState().json(includePresentation=false),state.json(includePresentation=false))
        val history=EditHistory();history.update(state);history.undo();assertEquals(CompositionTransform(),history.current.composition)
        history.redo();assertEquals(c,history.current.composition)
        assertEquals(CompositionTransform(),EditorState.fromJson("{}").composition)
    }

    @Test fun floatCropMatchesAnalyticGradientAndRetryPreservesOriginal(){
        val dir=Files.createTempDirectory("fixed135-gradient").toFile()
        try {
            for((w,h) in listOf(120 to 80,80 to 120,100 to 100,160 to 24)){
                val s=snapshot(w,h,CompositionTransform(.63,.39,2.4),feather=0.0)
                val input=File(dir,"input.rgb");val output=File(dir,"out.rgb")
                write(input,w,h){x,y,c-> when(c){0->x/(w-1f);1->y/(h-1f);else->.1234567f}}
                val original=input.readBytes();BorderCompositor.composite(input,output,s,Fixture(0.0),OutputColorSpace.SRGB)
                val values=read(output);val r=s.sourceRect
                for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                    val u=(x+.5-s.apertureLeft)/s.photoWidth;val v=(y+.5-s.apertureTop)/s.photoHeight
                    if(u>.05 && u<.95 && v>.05 && v<.95){
                        val sx=((r.left+u*r.width)*w-.5).coerceIn(0.0,w-1.0)
                        val sy=((r.top+v*r.height)*h-.5).coerceIn(0.0,h-1.0)
                        val at=(y*s.outputWidth+x)*3
                        assertEquals(sx/(w-1),values[at].toDouble(),1e-6)
                        assertEquals(sy/(h-1),values[at+1].toDouble(),1e-6)
                        assertEquals(.1234567f,values[at+2],1e-7f)
                    }
                }
                val first=output.readBytes();BorderCompositor.composite(input,output,s,Fixture(0.0),OutputColorSpace.SRGB)
                assertArrayEquals(original,input.readBytes());assertArrayEquals(first,output.readBytes());assertEquals(s.outputBytes,output.length())
            }
        }finally{dir.deleteRecursively()}
    }

    @Test fun bitmapPreviewAndFloatExportUseTheSameCropSamplerAndFeather(){
        val dir=Files.createTempDirectory("fixed135-preview").toFile()
        try {
            for((w,h) in listOf(96 to 64,64 to 96,97 to 97)){
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                val pixels=IntArray(w*h){i->val x=i%w;val y=i/w;(0xff shl 24)or((x*255/(w-1))shl 16)or((y*255/(h-1))shl 8)or 71}
                bitmap.setPixels(pixels,0,w,0,0,w,h)
                val s=snapshot(w,h,CompositionTransform(.57,.41,1.7))
                val input=File(dir,"input.rgb");val output=File(dir,"out.rgb")
                write(input,w,h){x,y,c->LinearRgb.srgb8ToLinear[(pixels[y*w+x] ushr (16-c*8))and 255]}
                BorderCompositor.composite(input,output,s,Fixture(),OutputColorSpace.SRGB)
                val preview=BorderPreview.compose(bitmap,s,Fixture());val values=read(output)
                for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                    val actual=preview.getPixel(x,y);val at=(y*s.outputWidth+x)*3
                    for(c in 0..2)assertEquals(OutputColorSpace.SRGB.encode8(values[at+c]),(actual ushr (16-c*8))and 255)
                }
                preview.recycle();bitmap.recycle()
            }
        }finally{dir.deleteRecursively()}
    }

    @Test fun nativeAlignedAperturePreservesZeroAndNonzeroPixelsBitExactly(){
        for(s in listOf(snapshot(3600,2400,feather=0.0),BorderSnapshot.create("fixed135_fixture","authored-test-v2",100,80,.2,.03,0.0))){
            val map=BorderSampling(s);val source=FloatArray(s.sourceWidth*3){i->if((i/3)%2==0)0f else .75f}
            val output=FloatArray(s.outputWidth*3)
            val y=s.apertureTop.toInt()+20
            BorderCompositor.composeSampledRow(s,Fixture(0.0),OutputColorSpace.SRGB,y,map,source,source,0.0,output)
            for(x in 0 until s.photoWidth)for(c in 0..2)
                assertEquals("pixel $x channel $c geometry ${s.geometryVersion}",source[x*3+c].toRawBits(),output[(s.apertureLeft.toInt()+x)*3+c].toRawBits())
        }
    }

    @Test fun prototypeFourPixelFeatherChangesOnlyTwoInsideColumnsAtNativeScale(){
        // 4 px is a versioned test fixture, not a claim about physical film or final shipping assets.
        val s=snapshot(4608,3072);val map=BorderSampling(s)
        val source=FloatArray(s.sourceWidth*3){.456789f};val output=FloatArray(s.outputWidth*3)
        val y=s.apertureTop.toInt()+100
        BorderCompositor.composeSampledRow(s,Fixture(),OutputColorSpace.SRGB,y,map,source,source,0.0,output)
        assertEquals(2.0,s.featherRadius,0.0)
        for(x in 0 until s.photoWidth){
            val value=output[(s.apertureLeft.toInt()+x)*3]
            if(x in 2 until s.photoWidth-2)assertEquals(.456789f.toRawBits(),value.toRawBits())
            else assertTrue(value<.456789f)
        }
    }

    @Test fun authoredMarkerAtlasRotatesClockwiseWithoutGlyphStretchAndOwnsItsPixels(){
        val pixels=IntArray(38*35){i->val x=i%38;val y=i/38;(0xff shl 24)or((x*6)shl 16)or((y*7)shl 8)or 39}
        val atlas=Fixed135BorderRaster("fixed135_fixture","authored-test-v2",38,35,pixels,4.0)
        val landscape=snapshot(360,240);val portrait=snapshot(240,360)
        atlas.validate(landscape);atlas.validate(portrait)
        val a=FloatArray(3);val b=FloatArray(3)
        for((x,y) in listOf(.5 to .5,12.5 to 3.5,37.5 to 34.5,9.2 to 4.7)){
            atlas.sample(landscape,landscape.frameOriginX+x*landscape.pixelsPerMm,landscape.frameOriginY+y*landscape.pixelsPerMm,a)
            atlas.sample(portrait,portrait.frameOriginX+(35-y)*portrait.pixelsPerMm,portrait.frameOriginY+x*portrait.pixelsPerMm,b)
            assertArrayEquals(a,b,1e-6f)
        }
        atlas.sample(landscape,5.0,5.0,a);pixels.fill(0xffffffff.toInt());atlas.sample(landscape,5.0,5.0,b)
        assertArrayEquals(a,b,0f)
        assertThrows(IllegalArgumentException::class.java){Fixed135BorderRaster("fixed135_fixture","test",39,35,IntArray(39*35){-1},4.0)}
    }

    @Test fun cancellationKeepsPriorDestinationAndRemovesOnlyPartialFile(){
        val dir=Files.createTempDirectory("fixed135-cancel").toFile()
        try {
            val input=File(dir,"input.rgb");write(input,120,80){_,_,_->.5f};val original=input.readBytes()
            val output=File(dir,"out.rgb").apply{writeText("prior")};var checks=0
            assertThrows(InterruptedException::class.java){BorderCompositor.composite(input,output,snapshot(120,80),Fixture(),OutputColorSpace.SRGB){if(++checks==7)throw InterruptedException("test")}}
            assertEquals("prior",output.readText());assertFalse(File(dir,"out.rgb.part").exists());assertArrayEquals(original,input.readBytes())
        }finally{dir.deleteRecursively()}
    }
}
