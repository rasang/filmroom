package dev.filmroom.border

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumImprintMarksHostTest {
    private fun frozen(d:MediumStripDefinitions.Definition,f:MediumFormat,portrait:Boolean,route:String)=
        BorderSnapshot.medium120(d.stock,d.version,192,128,192,128,CompositionTransform(),f,portrait,410.0)
            .copy(appearance=FrameAppearanceContext.forProfile(d.stock,if(FrameProfileCatalog.kind(d.stock)==FrameAppearanceContext.FilmKind.REVERSAL)"direct"else route,true))
    private fun sample(r:BorderRaster,s:BorderSnapshot,local:Double,cross:Double):FloatArray {
        val rotated=s.mediumLayout!!.rotated
        val x=if(rotated)61-cross else local;val y=if(rotated)local else cross
        return FloatArray(3).also{r.sample(s,s.frameOriginX+x*s.pixelsPerMm,s.frameOriginY+y*s.pixelsPerMm,it)}
    }
    @Test fun pushAliasesSharePhysicalMarksWithoutChangingSnapshotIdentity(){
        val d=MediumStripDefinitions.definitions.getValue("kodak_portra_800")
        val pixels=IntArray(d.width*d.height){0xff080808.toInt()}
        val physical=MediumStripRaster(d,pixels)
        for(stock in listOf("kodak_portra_800_push1","kodak_portra_800_push2")){
            val s=BorderSnapshot.medium120(stock,d.version,192,128,192,128,CompositionTransform(),MediumFormat.MF645,false,410.0)
                .copy(appearance=FrameAppearanceContext.forProfile(stock,"print",true))
            val alias=MediumStripProvider.Alias(stock,physical)
            val wrapped=MediumImprintMarks.wrap(s,alias)
            assertNotSame(alias,wrapped);wrapped.validate(s)
            assertEquals(stock,wrapped.stock)
            assertArrayEquals(sample(alias,s,23.0,60.0),sample(wrapped,s,23.0,60.0),0f)
        }
    }
    @Test fun all45LayoutsBothRotationsAndMaterialsRetainSourceColorsAndBounds(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        var layouts=0;var moves=0
        for(d in MediumStripDefinitions.definitions.values){
            val bitmap=app.assets.open(d.path).use{BitmapFactory.decodeStream(it)}!!
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle()
            val original=MediumStripRaster(d,pixels)
            for(f in MediumFormat.entries){
                val l=MediumImprintMarks.layout(frozen(d,f,false,"print"))!!
                layouts++;moves+=l.moves.size
                assertTrue(l.clearStartMm<l.widthMm/2 && l.clearEndMm>l.widthMm/2)
                for(portrait in listOf(false,true))for(route in if(FrameProfileCatalog.kind(d.stock)==FrameAppearanceContext.FilmKind.REVERSAL)listOf("direct")else listOf("print","direct")){
                    val s=frozen(d,f,portrait,route);val wrapped=MediumImprintMarks.wrap(s,original)
                    wrapped.validate(s)
                    assertTrue(wrapped.residentBytes<=BorderCompositor.MAX_ATLAS_BYTES)
                    assertEquals(original.residentBytes+256,wrapped.residentBytes)
                    // Original brand rail and every aperture-side sample remain exactly untouched.
                    for(cross in listOf(0.3,1.2,2.4,30.0,58.4))for(i in 0..50){
                        val local=l.widthMm*i/50
                        assertArrayEquals(sample(original,s,local,cross),sample(wrapped,s,local,cross),0f)
                    }
                    // Every copied marker pixel preserves existing material and vertical placement.
                    for(m in l.moves)for(px in m.left until m.right)for(py in 0 until d.height/2){
                        val cross=58.5+(py+.5)/d.pixelsPerMm
                        val local=m.targetMm+(px+.5-m.left)*l.mmPerPixel
                        val sourceLocal=(l.cropStartPx+px+.5)/d.pixelsPerMm-(410-l.widthMm/2)
                        assertArrayEquals(sample(original,s,sourceLocal,cross),sample(wrapped,s,local,cross),.00001f)
                    }
                    if(d.stock in setOf("fujifilm_velvia_100","fujifilm_pro_400h")){
                        assertTrue(l.moves.isEmpty())
                        for(i in 0..50)assertArrayEquals(sample(original,s,l.widthMm*i/50,60.0),sample(wrapped,s,l.widthMm*i/50,60.0),.00001f)
                    }
                    try{wrapped.validate(s.copy(stripCenterMm=411.0));fail("Snapshot mismatch accepted")}
                    catch(_:IllegalArgumentException){}
                }
                val s=frozen(d,f,false,"print")
                for(bad in listOf(s.copy(stripCenterMm=411.0),s.copy(assetVersion="unknown"),s.copy(appearance=null))){
                    assertNull(MediumImprintMarks.layout(bad));assertSame(original,MediumImprintMarks.wrap(bad,original))
                }
            }
        }
        assertEquals(45,layouts);assertEquals(98,moves)
    }
}
