package dev.filmroom

import dev.filmroom.border.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/** Synthetic colors/holes only, not real-stock artwork or an authenticity assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class StraightAlphaFixed135RasterHostTest {
    private fun pixels(material:Int=0xffb06020.toInt(),hidden:Int=0x00ff00ff)=IntArray(76*70){i->val x=i%76;val y=i/76;if(x in 2 until 74 && y in 11 until 59)hidden else material}
    private fun raster(pixels:IntArray)=StraightAlphaFixed135Raster("rgba_fixture","rgba-v1",76,70,pixels,4.0)
    private fun snapshot(w:Int=72,h:Int=48)=BorderSnapshot.fixed135("rgba_fixture","rgba-v1",w,h)
    private fun sample(a:BorderRaster,s:BorderSnapshot,x:Double,y:Double)=FloatArray(3).also{a.sample(s,x,y,it)}
    private fun atlasPoint(a:BorderRaster,s:BorderSnapshot,x:Double,y:Double):FloatArray {
        val lx=x/2;val ly=y/2
        return if(s.portrait)sample(a,s,s.frameOriginX+(35-ly)*s.pixelsPerMm,s.frameOriginY+lx*s.pixelsPerMm)
        else sample(a,s,s.frameOriginX+lx*s.pixelsPerMm,s.frameOriginY+ly*s.pixelsPerMm)
    }
    private fun encode(v:Double)=if(v<=.0031308)v*12.92 else 1.055*v.pow(1/2.4)-.055

    @Test fun clearedApertureAndFourCornersUseMaterialNeverHiddenRgbOrWhiteMatte(){
        for(material in listOf(0xff000000.toInt(),0xffb06020.toInt())){
            val a=raster(pixels(material));val s=snapshot()
            val expected=floatArrayOf(((material ushr 16)and 255)/255f,((material ushr 8)and 255)/255f,(material and 255)/255f)
            for((x,y) in listOf(2.5 to 11.5,73.5 to 11.5,2.5 to 58.5,73.5 to 58.5,38.0 to 35.0,2.0 to 20.0,74.0 to 30.0))
                assertArrayEquals(expected,atlasPoint(a,s,x,y),1e-6f)
            assertTrue(a.residentBytes<30_000)
        }
    }

    @Test fun holesAlwaysUseWhiteMatteAndIgnoreHostileZeroAlphaColor(){
        val first=pixels().apply{this[4*76+20]=0x00ff0000}
        val second=first.copyOf().apply{this[4*76+20]=0x0000ff00}
        val a=raster(first);val b=raster(second);val s=snapshot()
        assertArrayEquals(floatArrayOf(1f,1f,1f),atlasPoint(a,s,20.5,4.5),0f)
        assertArrayEquals(atlasPoint(a,s,20.2,4.3),atlasPoint(b,s,20.2,4.3),0f)
    }

    @Test fun semitransparentBlackEdgeIsMattedInLinearLight(){
        val p=pixels().apply{this[4*76+20]=0x80000000.toInt()}
        val actual=atlasPoint(raster(p),snapshot(),20.5,4.5)
        val expected=encode(127.0/255).toFloat()
        assertArrayEquals(floatArrayOf(expected,expected,expected),actual,1e-6f)
        assertTrue(actual[0]>.7f) // Encoded-space blending would incorrectly yield ~0.498.
    }

    @Test fun filteringPremultipliesBeforeInterpolationSoHiddenBlueCannotFringeRed(){
        val p=pixels().apply{this[4*76+20]=0xffff0000.toInt();this[4*76+21]=0x000000ff}
        val actual=atlasPoint(raster(p),snapshot(),21.0,4.5)
        assertEquals(1f,actual[0],1e-6f)
        assertEquals(encode(.5).toFloat(),actual[1],1e-6f)
        assertEquals(actual[1],actual[2],1e-6f)
    }

    @Test fun exactAreaMinificationRetainsThinHoleCoverageRatherThanAliasing(){
        val p=pixels(0xff000000.toInt())
        for(y in 0..8)for(x in 0 until 76)p[y*76+x]=if(x%4==0)0x00ff00ff else 0xff000000.toInt()
        val a=raster(p);val s=snapshot(18,12) // 4 atlas pixels per output pixel.
        val actual=atlasPoint(a,s,8.0,4.0)
        val expected=encode(.25).toFloat()
        assertArrayEquals(floatArrayOf(expected,expected,expected),actual,1e-6f)
        val portrait=snapshot(12,18)
        assertArrayEquals(actual,atlasPoint(a,portrait,8.0,4.0),1e-6f)
    }

    @Test fun clockwiseRotationAndMagnificationKeepSameAtlasMaterialAndHoleSamples(){
        val p=pixels().apply{this[4*76+20]=0x80000000.toInt();this[4*76+21]=0x00ff00ff}
        val a=raster(p)
        for((x,y) in listOf(20.5 to 4.5,21.0 to 4.5,2.5 to 11.5,73.5 to 58.5,8.2 to 64.7)){
            val normal=atlasPoint(a,snapshot(),x,y)
            assertArrayEquals(normal,atlasPoint(a,snapshot(48,72),x,y),1e-6f)
            assertArrayEquals(normal,atlasPoint(a,snapshot(144,96),x,y),1e-6f)
        }
    }

    @Test fun missingOpaqueBoundaryAndOpaqueApertureRejectInsteadOfInventingColor(){
        val missing=pixels().apply{this[10*76+20]=0x0088ffff}
        assertThrows(IllegalArgumentException::class.java){raster(missing)}
        val notCleared=pixels().apply{this[20*76+20]=0xff000000.toInt()}
        assertThrows(IllegalArgumentException::class.java){raster(notCleared)}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaFixed135Raster("rgba_fixture","bad",38,35,IntArray(38*35),4.0)}
    }

    @Test fun immutableInputAndSnapshotContractRemainBoundedAndFailClosed(){
        val p=pixels();val a=raster(p);val s=snapshot();a.validate(s)
        val before=atlasPoint(a,s,30.5,3.5);p.fill(0)
        assertArrayEquals(before,atlasPoint(a,s,30.5,3.5),0f)
        assertThrows(IllegalArgumentException::class.java){a.validate(s.copy(assetVersion="replacement"))}
        assertFalse(dev.filmroom.model.EditorState().filmBorder) // User-facing default stays OFF even in authorized candidate registry.
    }
}
