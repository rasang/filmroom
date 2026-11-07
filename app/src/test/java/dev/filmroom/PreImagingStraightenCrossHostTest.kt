package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.filmroom.border.BorderRaster
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.border.MediumFormat
import dev.filmroom.border.PreImagingInput
import dev.filmroom.io.LinearPreview
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PreviewHistogramInput
import dev.filmroom.ui.PreviewHistogramSampler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.floor

/** Merged pre-imaging + straighten contract. The synthetic raster tests geometry and
 * float transport, not real atlas authenticity or the native scientific image model.
 * No native library, image decoder, application singleton, or external fixture is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PreImagingStraightenCrossHostTest {
    private data class Case(
        val label:String,val stock:String,val w:Int,val h:Int,val cw:Int,val ch:Int,
        val aspect:Double?=null,val authored:Boolean=false,val medium:MediumFormat?=null,
        val portrait:Boolean=false,
    )
    private val cases=listOf(
        Case("fixed135 landscape", "fixture_only",97,65,6001,4000),
        Case("fixed135 portrait", "fixture_only",65,97,4000,6001),
        Case("fixed135 explicit portrait", "fixture_only",97,97,5001,5001,2.0/3.0),
        Case("authored Double-X", "kodak_doublex",97,65,6001,4000,authored=true),
        Case("authored Tri-X portrait", "kodak_trix",65,97,4000,6001,authored=true),
        Case("authored 2302", "kodak_2302",97,65,6001,4000,authored=true),
        Case("120 square", "kodak_portra_400",97,65,6001,4000,medium=MediumFormat.MF66),
        Case("120 6x7 portrait", "kodak_portra_400",65,97,4000,6001,medium=MediumFormat.MF67,portrait=true),
    )
    private fun snapshot(c:Case,angle:Double):BorderSnapshot {
        // Deliberately off-centre and zoomed; canonical aspect differs from rounded preview.
        val t=CompositionTransform(.76,.23,2.15,c.aspect,angle)
        return when {
            c.medium!=null -> BorderSnapshot.medium120(c.stock,"cross-v1",c.w,c.h,c.cw,c.ch,t,
                c.medium,c.portrait,featherTotalAt3072=0.0)
            c.authored -> BorderSnapshot.authored(c.stock,"cross-v1",c.w,c.h,c.cw,c.ch,t,0.0)
            else -> BorderSnapshot.fixed135(c.stock,"cross-v1",c.w,c.h,c.cw,c.ch,t,0.0)
        }
    }
    private class Fixture(private val frozen:BorderSnapshot):BorderRaster {
        override val stock=frozen.stock
        override val version=frozen.assetVersion
        override val railRatio=.2
        override val endRatio=.03
        override val residentBytes=12L
        override val featherTotalAt3072=0.0
        override val supportsAuthoredLayout=true
        override fun validate(snapshot:BorderSnapshot) {
            if(snapshot.geometryVersion==5)require(snapshot==frozen)
            else super<BorderRaster>.validate(snapshot)
        }
        override fun holeCoverage(snapshot:BorderSnapshot,x:Double,y:Double)=
            if(x<1.0 || y<1.0)1.0 else 0.0
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray) {
            val hole=holeCoverage(snapshot,x,y)>0.0
            rgb[0]=if(hole)1f else .15f
            rgb[1]=if(hole)1f else .25f
            rgb[2]=if(hole)1f else .35f
        }
    }
    private fun gradient(w:Int,h:Int)=FloatArray(w*h*3){i->
        val p=i/3
        when(i%3){0->.4f+1.8f*(p%w)/(w-1);1->.3f+.7f*(p/w)/(h-1);else->.31415927f}
    }
    private fun linear(w:Int,h:Int,values:FloatArray)=LinearPreview(w,h,
        ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).apply{
            values.forEach(::putFloat);rewind()
        })
    private fun floats(preview:LinearPreview)=preview.buffer().asFloatBuffer().let{b->
        FloatArray(b.remaining()).also{b.get(it)}
    }
    private fun write(file:File,values:FloatArray) {
        val bytes=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(bytes::putFloat);file.writeBytes(bytes.array())
    }
    private fun read(file:File)=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer().let{b->FloatArray(b.remaining()).also{b.get(it)}}
    private fun assertBitExact(label:String,expected:FloatArray,actual:FloatArray) {
        assertEquals("$label float count",expected.size,actual.size)
        for(i in expected.indices)if(expected[i].toRawBits()!=actual[i].toRawBits())
            fail("$label float[$i]: ${expected[i]} != ${actual[i]} (different float32 bits)")
    }
    private fun assertAnalyticAperture(label:String,s:BorderSnapshot,values:FloatArray) {
        for(u in listOf(.2,.5,.8))for(v in listOf(.2,.5,.8)) {
            val x=floor(s.apertureLeft+u*s.photoWidth).toInt()
            val y=floor(s.apertureTop+v*s.photoHeight).toInt()
            val point=s.composition.sourcePoint((x+.5-s.apertureLeft)/s.photoWidth,
                (y+.5-s.apertureTop)/s.photoHeight,s.canonicalWidth,s.canonicalHeight)
            val sx=(point.first*s.sourceWidth-.5).coerceIn(0.0,s.sourceWidth-1.0)
            val sy=(point.second*s.sourceHeight-.5).coerceIn(0.0,s.sourceHeight-1.0)
            val at=(y*s.outputWidth+x)*3
            assertEquals("$label red ($x,$y)",.4+1.8*sx/(s.sourceWidth-1),values[at].toDouble(),5e-7)
            assertEquals("$label green ($x,$y)",.3+.7*sy/(s.sourceHeight-1),values[at+1].toDouble(),5e-7)
            assertEquals("$label constant blue",.31415927f,values[at+2],0f)
        }
    }

    @Test fun floatPreviewAndFileAreBitExactForFortyRotatedBorderCases() {
        val dir=Files.createTempDirectory("pre-straighten-cross").toFile()
        try {
            for(c in cases) {
                val values=gradient(c.w,c.h)
                val input=File(dir,"input.rgb");write(input,values);val original=input.readBytes()
                for(angle in listOf(-15.0,-1.0,0.0,1.0,15.0)) {
                    val s=snapshot(c,angle);val raster=Fixture(s);val label="${c.label} $angle degrees"
                    val output=File(dir,"output.rgb")
                    PreImagingInput.file(input,output,s,raster)
                    val preview=PreImagingInput.preview(null,linear(c.w,c.h,values),s,raster)
                    assertEquals(s.outputWidth,preview.width);assertEquals(s.outputHeight,preview.height)
                    val actual=floats(preview)
                    assertBitExact(label,read(output),actual)
                    assertAnalyticAperture(label,s,actual)
                    assertTrue("$label HDR retained",actual.any{it>1f})
                    assertEquals("$label clear hole removed before imaging",0f,actual[0],0f)
                    assertArrayEquals("$label original immutable",original,input.readBytes())
                    assertEquals(s.outputBytes,output.length())
                    assertEquals(s,BorderSnapshot.fromJson(s.json()))
                }
            }
        } finally {dir.deleteRecursively()}
    }

    @Test fun bitmapPreviewMatchesFileFloatForNineRotatedInputCases() {
        val dir=Files.createTempDirectory("pre-straighten-bitmap").toFile()
        try {
            for(c in listOf(cases[0],cases[1],cases[4])) {
                val pixels=IntArray(c.w*c.h){i->Color.rgb((i%c.w)*255/(c.w-1),(i/c.w)*255/(c.h-1),(i*37)%256)}
                val bitmap=Bitmap.createBitmap(pixels,c.w,c.h,Bitmap.Config.ARGB_8888)
                try {
                    val values=FloatArray(pixels.size*3){i->val color=pixels[i/3]
                        LinearRgb.srgb8ToLinear[(color ushr (16-8*(i%3))) and 255]}
                    val input=File(dir,"input.rgb");write(input,values)
                    for(angle in listOf(-8.3,0.0,11.2)) {
                        val s=snapshot(c,angle);val raster=Fixture(s);val output=File(dir,"output.rgb")
                        PreImagingInput.file(input,output,s,raster)
                        assertBitExact("${c.label} $angle bitmap",read(output),
                            floats(PreImagingInput.preview(bitmap,null,s,raster)))
                    }
                } finally {bitmap.recycle()}
            }
        } finally {dir.deleteRecursively()}
    }

    private fun lumaBin(color:Int)=((2126*Color.red(color)+7152*Color.green(color)+
        722*Color.blue(color)+5000)/10000) ushr 1
    private fun pattern(x:Int,y:Int)=when((x+3*y)%4) {
        0->Color.BLACK;1->Color.RED;2->Color.GREEN;else->Color.BLUE
    }
    private fun photo(w:Int,h:Int)=PhotoSource(File("unused-cross-fixture"),"cross","image/png",w,h,0)

    @Test fun preHistogramCountsBakedApertureOnceAndNeverCountsWhiteFrame()=runBlocking {
        for((w,h) in listOf(144 to 96,96 to 144))for(angle in listOf(-15.0,-1.0,0.0,1.0,15.0)) {
            val t=CompositionTransform(.71,.28,2.3,straightenDegrees=angle).clamped(w,h)
            val params=EditorState(filmBorder=true,filmBorderStage=1,composition=t)
            val s=BorderSnapshot.fixed135(params.frameStock,"histogram-cross",w,h,w,h,t)
            val frame=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888)
            val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {
                frame.eraseColor(Color.WHITE);source.eraseColor(Color.WHITE)
                val left=floor(s.apertureLeft).toInt();val top=floor(s.apertureTop).toInt()
                val right=ceil(s.apertureLeft+s.photoWidth).toInt();val bottom=ceil(s.apertureTop+s.photoHeight).toInt()
                val expected=IntArray(128)
                for(y in top until bottom)for(x in left until right) {
                    val color=pattern(x,y);frame.setPixel(x,y,color);expected[lumaBin(color)]++
                }
                val state=EditorUiState(photo=photo(w,h),original=source,rendered=frame,
                    compositionSource=frame,presentation=s,parameters=params,renderedParameters=params)
                val input=checkNotNull(PreviewHistogramInput.from(state))
                assertSame(frame,input.bitmap);assertNull("Angle is already baked into frame",input.rotation)
                assertEquals(left,input.left);assertEquals(top,input.top)
                assertEquals(right,input.right);assertEquals(bottom,input.bottom)
                val actual=checkNotNull(PreviewHistogramSampler.sample(input))
                assertEquals(expected.sum(),actual.samples);assertArrayEquals(expected,actual.bins)
                assertEquals("No white frame pixels",0,actual.bins[127])
                // Stage 1 must use the published frame even if a stale source is still retained.
                assertSame(frame,checkNotNull(PreviewHistogramInput.from(state.copy(compositionSource=source))).bitmap)
            } finally {frame.recycle();source.recycle()}
        }
    }

    @Test fun legacyHistogramStillUsesIndependentSourcePolygonAtBothRotationSigns()=runBlocking {
        for((w,h) in listOf(193 to 131,131 to 193))for(angle in listOf(-15.0,-1.0,1.0,15.0)) {
            val cw=if(w>h)6001 else 4000;val ch=if(w>h)4000 else 6001
            val t=CompositionTransform(.13,.78,2.7,straightenDegrees=angle).clamped(cw,ch)
            val params=EditorState(filmBorder=true,filmBorderStage=0,composition=t)
            val s=BorderSnapshot.fixed135(params.frameStock,"histogram-cross",w,h,cw,ch,t)
            val colors=IntArray(w*h){i->if((i%w+i/w)%5==0)Color.WHITE else pattern(i%w,i/w)}
            val source=Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888)
            val frame=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888)
            try {
                frame.eraseColor(Color.MAGENTA)
                val state=EditorUiState(photo=photo(cw,ch),original=source,rendered=frame,
                    compositionSource=source,presentation=s,parameters=params,renderedParameters=params)
                val input=checkNotNull(PreviewHistogramInput.from(state))
                assertSame(source,input.bitmap);assertEquals(t,input.rotation)
                val corners=listOf(0.0 to 0.0,1.0 to 0.0,1.0 to 1.0,0.0 to 1.0)
                    .map{t.sourcePoint(it.first,it.second,cw,ch)}
                val expected=IntArray(128)
                // Independent cross-product inclusion; does not reuse sampler inverse matrix.
                for(y in 0 until h)for(x in 0 until w) {
                    val px=(x+.5)/w;val py=(y+.5)/h
                    val crosses=corners.indices.map{i->val a=corners[i];val b=corners[(i+1)%4]
                        (b.first-a.first)*(py-a.second)-(b.second-a.second)*(px-a.first)}
                    if(crosses.all{it>0.0} || crosses.all{it<0.0})expected[lumaBin(colors[y*w+x])]++
                }
                val actual=checkNotNull(PreviewHistogramSampler.sample(input))
                assertEquals(expected.sum(),actual.samples);assertArrayEquals(expected,actual.bins)
            } finally {source.recycle();frame.recycle()}
        }
    }

    @Test fun pendingStageOrAngleHidesGraphUntilExactAdoptionWhileCompareUsesOriginal()=runBlocking {
        val w=144;val h=96
        val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLACK)}
        try {
            for(stage in listOf(0,1)) {
                val t=CompositionTransform(.7,.3,2.0,straightenDegrees=-7.3).clamped(w,h)
                val params=EditorState(filmBorder=true,filmBorderStage=stage,composition=t)
                val s=BorderSnapshot.fixed135(params.frameStock,"histogram-cross",w,h,w,h,t)
                val frame=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GRAY)}
                try {
                    val state=EditorUiState(photo=photo(w,h),original=source,rendered=frame,
                        compositionSource=if(stage==1)frame else source,presentation=s,
                        parameters=params,renderedParameters=params)
                    assertNotNull(PreviewHistogramInput.from(state))
                    val next=params.copy(composition=t.copy(straightenDegrees=8.1).clamped(w,h))
                    val pendingAngle=state.copy(parameters=next)
                    val pendingStage=state.copy(parameters=params.copy(filmBorderStage=1-stage))
                    assertNull(PreviewHistogramInput.from(pendingAngle))
                    assertNull(PreviewHistogramInput.from(pendingStage))
                    assertNull(PreviewHistogramInput.from(state.copy(compositionEditing=true)))
                    assertNotNull(PreviewHistogramInput.from(pendingAngle.copy(renderedParameters=next,
                        presentation=s.copy(composition=next.composition))))
                    for(pending in listOf(pendingAngle,pendingStage)) {
                        val compare=checkNotNull(PreviewHistogramInput.from(pending.copy(compare=true)))
                        assertSame(source,compare.bitmap);assertNull(compare.rotation)
                        val actual=checkNotNull(PreviewHistogramSampler.sample(compare))
                        assertEquals(w*h,actual.samples);assertEquals(w*h,actual.bins[0])
                    }
                } finally {frame.recycle()}
            }
        } finally {source.recycle()}
    }
}
