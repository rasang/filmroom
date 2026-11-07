package dev.filmroom.border

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.OutputColorSpace
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
import java.security.MessageDigest

/** Exact approved A bytes; direct appearance is separately pinned to the accepted D65 model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Reviewed135RasterHostTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val approved=linkedMapOf(
        "cinestill_800t" to "e40bcf1b510e2c2c943e6fec9a89a7b973b936522cf049a7efe9242b4cb51c8d",
        "fujifilm_neopan_acros_100_ii" to "6ab165da32b8d2b27b12e5c7f604f21e6a7bd54ecccdfe5ba595f1fefbf85c51",
        "ilford_hp5_plus_400" to "d56f257a7d7b9b9de83dd044617d3933dc31cff377246364d97ee9390f80b5a7",
        "kodak_tmax_100" to "ecd9e3fa04929eca8bd8dcba1f9662ea66ac796d904f8b12c7ce1da2104547bb",
        "kodak_tri_x_400" to "5f60b2909c48706fb8ec5a05aee25bb865c1180ca0684eb8a46204f33bf036df")
    private val phase=mapOf("cinestill_800t" to 2.4,"fujifilm_neopan_acros_100_ii" to 3.9,
        "ilford_hp5_plus_400" to .95,"kodak_tmax_100" to 1.6,"kodak_tri_x_400" to 2.0)
    private fun definition(stock:String,white:Boolean=true)=Reviewed135Definitions.selected(stock,FrameAppearanceContext.forProfile(stock,"print",white))
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun pixels(d:LazyBorderRegistry.Definition)=StraightAlphaBorderLoader.decodeStraight(d.spec,{app.assets.open(d.assetPath)})
    private fun snapshot(d:LazyBorderRegistry.Definition,w:Int,h:Int,portrait:Boolean=false)=
        BorderSnapshot.fixed135(d.spec.stock,d.spec.version,w,h,composition=CompositionTransform(targetAspect=if(portrait)2.0/3 else 1.5),featherTotalAt3072=d.spec.featherTotalAt3072)
    private fun raster(d:LazyBorderRegistry.Definition,p:IntArray)=Reviewed135BorderRaster(d.spec.stock,d.spec.version,1520,1400,p,d.spec.featherTotalAt3072,d.spec.holeMatteSrgb)
    /** Map an authored atlas texel center into the output, including clockwise portrait rotation. */
    private fun point(s:BorderSnapshot,x:Double,y:Double):Pair<Double,Double> =
        if(s.portrait)(s.frameOriginX+(1400-y)/40*s.pixelsPerMm) to (s.frameOriginY+x/40*s.pixelsPerMm)
        else (s.frameOriginX+x/40*s.pixelsPerMm) to (s.frameOriginY+y/40*s.pixelsPerMm)

    @Test fun packagedBytesMatchApprovalAndKeepRoundedApertureAlpha(){
        assertEquals(approved.keys,Reviewed135Definitions.stocks)
        for((stock,hash) in approved){
            val d=definition(stock);val bytes=app.assets.open(d.assetPath).use{it.readBytes()}
            assertEquals(hash,d.spec.sha256);assertEquals(hash,sha(bytes));assertEquals(d.spec.encodedBytes,bytes.size)
            // Extra evidence when running in the review workspace; portable CI relies on the pinned hash above.
            val original=File("/workspace/shared/filmroom-five-135-material-study/candidates/$stock-A.webp")
            if(original.isFile)assertArrayEquals(original.readBytes(),bytes)
            val p=pixels(d)
            for((xy,alpha) in listOf((40 to 220) to 255,(41 to 221) to 255,(43 to 223) to 0,
                (46 to 220) to 15,(760 to 219) to 240,(760 to 220) to 15,(760 to 221) to 0,
                (760 to 1180) to 15,(760 to 1181) to 240,(760 to 700) to 0))
                assertEquals("$stock alpha $xy",alpha,p[xy.second*1520+xy.first] ushr 24)
            assertEquals(0.0,d.spec.featherTotalAt3072,0.0)
            assertTrue(StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)}) is Reviewed135BorderRaster)
            assertThrows(IllegalArgumentException::class.java){StraightAlphaFixed135Raster(stock,d.spec.version,1520,1400,p,0.0)}
        }
    }

    @Test fun coveragePreservesCornersAndSeparatesHoleMatteInBothOrientationsAndScales(){
        for(stock in approved.keys){
            val d=definition(stock);val p=pixels(d)
            for(white in listOf(true,false)){
                val selected=definition(stock,white);val r=raster(selected,p)
                for(portrait in listOf(false,true))for(ppm in listOf(20,40,80)){
                    val s=snapshot(selected,if(portrait)24*ppm else 36*ppm,if(portrait)36*ppm else 24*ppm,portrait)
                    r.validate(s)
                    assertEquals(ppm.toDouble(),s.pixelsPerMm,0.0)
                    assertEquals(if(portrait)35*ppm else 38*ppm,s.outputWidth)
                    assertEquals(if(portrait)38*ppm else 35*ppm,s.outputHeight)
                    assertEquals(s,BorderSnapshot.fromJson(s.json()))
                    val center=point(s,760.5,700.5)
                    assertEquals(1.0,r.photoCoverage(s,center.first,center.second),1e-12)
                    val hole=point(s,phase.getValue(stock)*40+4*190,3.45*40)
                    assertEquals(0.0,r.photoCoverage(s,hole.first,hole.second),0.0)
                    val rgb=FloatArray(3);r.sample(s,hole.first,hole.second,rgb)
                    assertArrayEquals(FloatArray(3){if(white)1f else 0f},rgb,1e-6f)
                    if(ppm>=40){
                        for((x,y) in listOf(40 to 220,43 to 223,760 to 219,760 to 220,760 to 221)){
                            val q=point(s,x+.5,y+.5)
                            assertEquals(1-(p[y*1520+x] ushr 24)/255.0,r.photoCoverage(s,q.first,q.second),1e-10)
                        }
                    }else{
                        // Exact 2x2 box footprint centered at the boundary, independently computed from A alpha.
                        val q=point(s,760.0,220.0)
                        val expected=listOf(219,220).sumOf{y->listOf(759,760).sumOf{x->1-(p[y*1520+x] ushr 24)/255.0}}/4
                        assertEquals(expected,r.photoCoverage(s,q.first,q.second),1e-10)
                    }
                }
            }
        }
    }

    @Test fun smallPreviewEqualsFloatExportAtEveryPixelForFiveStocksAndBothOrientations(){
        val dir=Files.createTempDirectory("reviewed135-pixel-parity").toFile()
        try {
            val w=36;val h=24
            val p=IntArray(w*h){i->0xff000000.toInt() or ((i%w*255/(w-1)) shl 16) or ((i/w*255/(h-1)) shl 8) or 91}
            val photo=Bitmap.createBitmap(p,w,h,Bitmap.Config.ARGB_8888)
            try {
                val input=File(dir,"input");val output=File(dir,"output")
                val buffer=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
                p.forEach{pixel->for(c in 0..2)buffer.putFloat(LinearRgb.srgb8ToLinear[(pixel ushr(16-c*8))and 255])}
                input.writeBytes(buffer.array())
                for(d in Reviewed135Definitions.definitions.filter{it.spec.holeMatteSrgb==0xffffff}){
                    val stock=d.spec.stock;val r=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
                    for(portrait in listOf(false,true)){
                        val s=snapshot(d,w,h,portrait);val preview=BorderPreview.compose(photo,s,r)
                        try {
                            BorderCompositor.composite(input,output,s,r,OutputColorSpace.SRGB)
                            assertEquals(s.outputWidth.toLong()*s.outputHeight*12,output.length())
                            val values=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                            for(y in 0 until preview.height)for(x in 0 until preview.width)for(c in 0..2){
                                val value=values.get();assertTrue(value.isFinite())
                                assertEquals("$stock portrait=$portrait ($x,$y) c=$c",(preview.getPixel(x,y) ushr(16-c*8))and 255,OutputColorSpace.SRGB.encode8(value))
                            }
                        }finally{preview.recycle()}
                    }
                }
            }finally{photo.recycle()}
        }finally{dir.deleteRecursively()}
    }

    @Test fun oldVersionsStayFrozenAndReviewedVersionsKeepDistinctRouteContracts(){
        BorderCatalog.initialize(app.assets)
        for(d in (CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions+BwBorderDefinitions.definitions+BwBorderDefinitions.legacyDefinitions)
            .filter{it.material==FrameAppearanceContext.Material.POSITIVE_DISPLAY}){
            assertFalse(Reviewed135Definitions.matches(d.spec.stock,d.spec.version))
            val s=BorderSnapshot.authored(d.spec.stock,d.spec.version,72,48,featherTotalAt3072=d.spec.featherTotalAt3072)
            assertEquals(s,BorderSnapshot.fromJson(s.json()))
            BorderCatalog.verifySnapshot(s)
        }
        val old=CandidateBorderDefinitions.definitions.first()
        assertTrue(StraightAlphaBorderLoader.load(old.spec,{app.assets.open(old.assetPath)}) is StraightAlphaFixed135Raster)
        for(stock in approved.keys){
            val d=definition(stock);val s=snapshot(d,72,48)
            BorderCatalog.verifySnapshot(s)
            assertEquals(s,BorderSnapshot.fromJson(s.json()))
            assertFalse(Reviewed135Definitions.matches(stock,"unreviewed-v1"))
            val direct=Reviewed135Definitions.selected(stock,FrameAppearanceContext.forProfile(stock,"direct",true))
            assertEquals(FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION,direct.material)
            assertNotEquals(d.spec.version,direct.spec.version);assertNotEquals(d.spec.sha256,direct.spec.sha256)
            assertTrue(Reviewed135Definitions.matches(stock,direct.spec.version))
            assertThrows(IllegalArgumentException::class.java){Reviewed135BorderRaster(stock,"unreviewed-v1",1520,1400,IntArray(1520*1400),0.0,0xffffff)}
        }
    }
}
