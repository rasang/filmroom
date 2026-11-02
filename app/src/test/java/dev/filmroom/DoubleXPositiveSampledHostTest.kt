package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DoubleXPositiveSampledHostTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun definition(white:Boolean)=BwBorderDefinitions.selected("kodak_doublex",FrameAppearanceContext.forProfile("kodak_doublex","print",white))
    private fun load(d:LazyBorderRegistry.Definition)=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
    private fun point(s:BorderSnapshot,x:Double,y:Double)=if(s.portrait)
        s.frameOriginX+(1400-y)*s.pixelsPerMm to s.frameOriginY+x*s.pixelsPerMm
        else s.frameOriginX+x*s.pixelsPerMm to s.frameOriginY+y*s.pixelsPerMm
    private fun sample(r:BorderRaster,s:BorderSnapshot,x:Double,y:Double):FloatArray {
        val p=point(s,x,y);return FloatArray(3).also{r.sample(s,p.first,p.second,it)}
    }
    @Test fun sampledNearBlackAndGrayLettersRetainGeometryWithoutPhotoLeakage() {
        val current=definition(true)
        val legacy=BwBorderDefinitions.legacyDefinitions.single{it.spec.stock=="kodak_doublex" && it.spec.holeMatteSrgb==0xffffff}
        val a=StraightAlphaBorderLoader.decodeStraight(current.spec,{app.assets.open(current.assetPath)})
        val b=StraightAlphaBorderLoader.decodeStraight(legacy.spec,{app.assets.open(legacy.assetPath)})
        assertEquals(a.size,b.size)
        a.indices.forEach{assertEquals("geometric alpha at $it",b[it] ushr 24,a[it] ushr 24)}
        for(white in listOf(false,true)) {
            val d=definition(white);val r=load(d)
            for(portrait in listOf(false,true))for(short in listOf(120,480,960)) {
                val s=BorderSnapshot.authored("kodak_doublex",d.spec.version,if(portrait)short else short*3/2,if(portrait)short*3/2 else short,
                    composition=CompositionTransform(.61,.42,2.0,if(portrait)2.0/3 else 1.5),featherTotalAt3072=d.spec.featherTotalAt3072)
                // Body and typography are independent of any underlying image value.
                for((x,y) in listOf(760.0 to 55.0,760.0 to 1300.0,20.0 to 700.0)) {
                    val p=point(s,x,y);assertEquals(0.0,r.photoCoverage(s,p.first,p.second)!!,0.0)
                }
                val body=sample(r,s,760.0,55.0)[0]
                assertTrue("Sampled near-black body: $body",body in .005f.. .04f)
                val hole=sample(r,s,96.0,100.0)
                hole.forEach{assertEquals(if(white)1f else 0f,it,1e-6f)}
                val middle=point(s,760.0,700.0)
                assertEquals(1.0,r.photoCoverage(s,middle.first,middle.second)!!,1e-9)
            }
        }
        assertTrue("Gray letters retain readable cores",a.count{(it and 255) in 88..112}>2500)
        assertTrue("No glowy-white letter pixels",a.all{(it and 255)<=112})
        fun glyphBounds(pixels:IntArray,threshold:Int):List<Int> {
            val indices=pixels.indices.filter{(pixels[it]and 255)>threshold}
            return listOf(indices.minOf{it%1520},indices.minOf{it/1520},indices.maxOf{it%1520}+1,indices.maxOf{it/1520}+1)
        }
        // Same original47px glyph placement and strokes, only a different color target.
        assertEquals(glyphBounds(b,120),glyphBounds(a,30))
        // Original mask reconstructed byte-exact in generator; top/bottom ink stays gray.
        val top=(1359..1365).flatMap{y->(280..1200).map{x->a[y*1520+x]and 255}}.filter{it>=88}
        val bottom=(1387..1394).flatMap{y->(280..1200).map{x->a[y*1520+x]and 255}}.filter{it>=88}
        assertTrue("Visible but restrained vertical ink gradient",top.average()-bottom.average() in 12.0..24.0)
        assertTrue("Monochrome ink",a.all{(it and 255)==((it ushr 8)and 255) && (it and 255)==((it ushr 16)and 255)})
        assertEquals(listOf(172,1359,1348,1395),glyphBounds(a,30))
        // Thin outer cut rims plus soft material-side hole rim; never an aperture contour.
        for(y in listOf(0,1399))assertTrue((a[y*1520+760]and 255)<=(a[55*1520+760]and 255)+2)
        assertTrue(kotlin.math.abs((a[700*1520+2]and 255)-(a[700*1520+20]and 255))<8)
        // First upper perforation: a narrow dark band outside the unchanged opening.
        val body=a[100*1520+50]and 255
        val rim=a[100*1520+56]and 255
        assertTrue("No bright bevel on near-black rim",body-rim in -2..4)
        assertTrue("Band remains on material side",(a[100*1520+56] ushr 24)>240)
        assertEquals("Hole is not shrunk or filled",0,a[100*1520+60] ushr 24)
        assertTrue("No dark outline at image aperture",kotlin.math.abs((a[700*1520+36]and 255)-(a[700*1520+20]and 255))<8)
    }
    @Test fun croppedZoomedPortraitLandscapePreviewEqualsEveryFloatExportPixel() {
        val dir=File("build/ui-host-renders/5222-positive-sampled").apply{mkdirs()}
        for(white in listOf(false,true))for(portrait in listOf(false,true))for(bright in listOf(false,true)) {
            val d=definition(white);val r=load(d)
            val w=if(portrait)160 else 240;val h=if(portrait)240 else 160
            val composition=CompositionTransform(.62,.43,1.7,if(portrait)2.0/3 else 1.5)
            val s=BorderSnapshot.authored("kodak_doublex",d.spec.version,w,h,composition=composition,featherTotalAt3072=d.spec.featherTotalAt3072)
            val color=if(bright)0xffeeeeee.toInt()else 0xff080808.toInt()
            val photo=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
            val preview=BorderPreview.compose(photo,s,r)
            val input=File(dir,"in.rgb");val output=File(dir,"out.rgb")
            val bytes=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(w*h*3){bytes.putFloat(LinearRgb.srgb8ToLinear[color and 255])};input.writeBytes(bytes.array())
            BorderCompositor.composite(input,output,s,r,OutputColorSpace.SRGB)
            val floats=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for(y in 0 until preview.height)for(x in 0 until preview.width){
                val pixel=preview.getPixel(x,y)
                for(c in 0..2)assertEquals((pixel ushr(16-c*8))and 255,OutputColorSpace.SRGB.encode8(floats.get()))
            }
            val rail=point(s,760.0,55.0)
            val pixel=preview.getPixel(rail.first.toInt(),rail.second.toInt())
            assertTrue((pixel and 255) in 1..10)
            val core=point(s,760.0,700.0)
            assertEquals(color,preview.getPixel(core.first.toInt(),core.second.toInt()))
            File(dir,"${if(white)"white" else "black"}-${if(portrait)"portrait" else "landscape"}-${if(bright)"bright" else "dark"}.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            preview.recycle();photo.recycle();input.delete();output.delete()
        }
    }
    @Test fun perforationRimScalesAtPreviewAnd3072ExportResolution() {
        val dir=File("build/ui-host-renders/5222-positive-sampled").apply{mkdirs()}
        for(outputWidth in listOf(480,3072))for(white in listOf(false,true)) {
            val d=definition(white);val r=load(d)
            val w=(outputWidth*1440.0/1520).toInt();val h=w*2/3
            val s=BorderSnapshot.authored("kodak_doublex",d.spec.version,w,h,featherTotalAt3072=d.spec.featherTotalAt3072)
            assertTrue(kotlin.math.abs(s.outputWidth-outputWidth)<=2)
            val start=point(s,38.0,22.0);val end=point(s,160.0,177.0)
            val left=kotlin.math.floor(start.first).toInt();val top=kotlin.math.floor(start.second).toInt()
            val width=kotlin.math.ceil(end.first).toInt()-left;val height=kotlin.math.ceil(end.second).toInt()-top
            val crop=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            for(y in 0 until height)for(x in 0 until width) {
                val xx=left+x+.5;val yy=top+y+.5
                assertEquals("Perforation patch cannot sample photo",0.0,r.photoCoverage(s,xx,yy)!!,0.0)
                val rgb=FloatArray(3);r.sample(s,xx,yy,rgb);BorderColor.convert(rgb,OutputColorSpace.SRGB)
                val c=IntArray(3){OutputColorSpace.SRGB.encode8(rgb[it])}
                crop.setPixel(x,y,0xff000000.toInt() or(c[0] shl 16)or(c[1] shl 8)or c[2])
            }
            File(dir,"hole-${outputWidth}-${if(white)"white" else "black"}.png").outputStream().use{crop.compress(Bitmap.CompressFormat.PNG,100,it)}
            crop.recycle()
        }
    }
    @Test fun neutralFrameColorsAreManagedInAllExportColorSpaces() {
        val d=definition(true);val r=load(d)
        val s=BorderSnapshot.authored("kodak_doublex",d.spec.version,1440,960,featherTotalAt3072=d.spec.featherTotalAt3072)
        val encoded=sample(r,s,760.0,55.0)
        val linear=encoded.copyOf();BorderColor.convert(linear,OutputColorSpace.SRGB)
        for(space in listOf(OutputColorSpace.SRGB,OutputColorSpace.DISPLAY_P3,OutputColorSpace.ADOBE_RGB)) {
            val converted=encoded.copyOf();BorderColor.convert(converted,space)
            converted.forEach { assertTrue(it.isFinite());assertEquals(linear[0],it,2e-6f) }
        }
    }
    @Test fun actualRuntimeBeforeAfterPreservesSamePrivatePhotoCore() {
        val path=System.getenv("FILMROOM_5222_REVIEW_SCREENSHOT")
        org.junit.Assume.assumeTrue("Optional private review screenshot, never packaged",path!=null)
        val screenshot=requireNotNull(android.graphics.BitmapFactory.decodeFile(path))
        val photo=Bitmap.createBitmap(screenshot,25,536,881,588)
        val current=definition(true)
        val legacy=BwBorderDefinitions.legacyDefinitions.single{it.spec.stock=="kodak_doublex" && it.spec.holeMatteSrgb==0xffffff}
        val dir=File("build/ui-host-renders/5222-positive-sampled").apply{mkdirs()}
        val images=listOf(legacy,current).mapIndexed { index,d ->
            val s=BorderSnapshot.authored("kodak_doublex",d.spec.version,photo.width,photo.height,featherTotalAt3072=d.spec.featherTotalAt3072)
            BorderPreview.compose(photo,s,load(d)).also { bitmap ->
                File(dir,if(index==0)"actual-runtime-before.png"else "actual-runtime-after.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        }
        val s=BorderSnapshot.authored("kodak_doublex",current.spec.version,photo.width,photo.height,featherTotalAt3072=current.spec.featherTotalAt3072)
        for(y in kotlin.math.ceil(s.apertureTop+4).toInt() until kotlin.math.floor(s.apertureTop+s.photoHeight-4).toInt())
            for(x in kotlin.math.ceil(s.apertureLeft+4).toInt() until kotlin.math.floor(s.apertureLeft+s.photoWidth-4).toInt())
                assertEquals("Photo core must not change",images[0].getPixel(x,y),images[1].getPixel(x,y))
        images.forEach{it.recycle()};photo.recycle();screenshot.recycle()
    }
    @Test fun frozenLegacyAndCurrentJobsKeepTheirOwnMaterialAndDirectRemainsNegative() {
        BorderCatalog.initialize(app.assets)
        val root=Files.createTempDirectory("5222-material-queue").toFile()
        try {
            val jobs=mutableListOf<ExportJob>()
            for(white in listOf(false,true)) {
                val p=EditorState(filmStock="kodak_doublex",filmBorder=true,negativeWhiteHoles=white)
                val current=BorderCatalog.snapshot(p.frameStock,600,400,600,400,p.composition,white,"print")
                assertTrue(current.assetVersion.startsWith(BwBorderDefinitions.POSITIVE_SAMPLED_PREFIX))
                val old=BwBorderDefinitions.legacyDefinitions.single{it.spec.stock=="kodak_doublex" && it.spec.holeMatteSrgb==(if(white)0xffffff else 0)}
                for(snapshot in listOf(current,current.copy(assetVersion=old.spec.version))) {
                    BorderCatalog.verifySnapshot(snapshot)
                    jobs+=ExportJob(ExportJobStore.newId(),jobs.size.toLong(),p,600,400,ExportFormat.PNG,95,state=ExportJobState.PAUSED,border=snapshot)
                }
                val direct=BwBorderDefinitions.selected("kodak_doublex",FrameAppearanceContext.forProfile("kodak_doublex","direct",white))
                assertEquals(FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION,direct.material)
                assertEquals("f5ccf647039af61d963b1c0d425c5fced803d6dc57ec669ffdf3f10568316d7a",direct.spec.sha256)
            }
            val store=ExportJobStore(root);jobs.forEach(store::update)
            val reopened=ExportJobStore(root)
            jobs.forEach{assertEquals(it.border,reopened.get(it.id)!!.border)}
            assertEquals(4,reopened.jobs.value.map{it.border!!.assetVersion}.distinct().size)
        } finally {root.deleteRecursively()}
    }
}
