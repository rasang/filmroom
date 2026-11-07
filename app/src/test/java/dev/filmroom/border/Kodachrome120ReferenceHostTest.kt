package dev.filmroom.border

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.imprint.ImprintContent
import dev.filmroom.imprint.ImprintPlan
import dev.filmroom.imprint.ImprintSettings
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Source-guided local marks with declared periodic extension, not continuous-roll metrology. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Kodachrome120ReferenceHostTest {
    private val stock="kodak_kodachrome_64"
    private fun snapshot(route:String,format:MediumFormat,portrait:Boolean,width:Int=181,height:Int=137)=
        BorderCatalog.snapshot(stock,width,height,width,height,CompositionTransform(),true,route,format,portrait)
    private fun raster():MediumStripRaster {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val d=MediumStripDefinitions.definitions.getValue(stock)
        val bytes=app.assets.open(d.path).use{it.readBytes()}
        assertEquals(d.encodedBytes,bytes.size)
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        assertEquals(d.sha256,sha)
        val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!
        assertEquals(8200,b.width);assertEquals(50,b.height)
        val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height);b.recycle()
        assertTrue(pixels.all{it ushr 24==255})
        assertTrue(pixels.any{((it ushr 8)and 255)>((it ushr 16)and 255)})
        assertTrue(pixels.any{(it and 255)>150})
        return MediumStripRaster(d,pixels)
    }
    @Test fun ownVersionSupportsFiveFormatsWithoutBorrowed135Marks(){
        val d=MediumStripDefinitions.definitions.getValue(stock)
        assertTrue(d.version.startsWith("mf120-v10-kodachrome64-"));assertFalse(d.monochrome)
        assertEquals(180.0,d.coverageEndMm-d.coverageStartMm,0.0)
        assertTrue(BorderCatalog.available(stock,null)) // Existing135 artwork is retained independently.
        for(route in listOf("direct"))for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val s=snapshot(route,f,p);assertEquals(d.version,s.assetVersion)
            assertEquals(s,BorderSnapshot.fromJson(s.json()));BorderCatalog.verifySnapshot(s)
            assertThrows(IllegalArgumentException::class.java){BorderCatalog.verifySnapshot(s.copy(stripCenterMm=600.0))}
            assertThrows(IllegalArgumentException::class.java){BorderCatalog.verifySnapshot(s.copy(assetVersion="unreviewed-kodachrome"))}
            assertFalse(EditorState(filmStock=stock).selectMediumFormat(f,p).copy(filmBorder=true).negativeWhiteHolesSupported)
        }
    }
    @Test fun reversalUsesPositiveDisplayWithoutOrangeNegativeMask(){
        val r=raster();assertEquals(1640000L,r.residentBytes)
        for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val s=snapshot("direct",f,p);r.validate(s)
            assertEquals(FrameAppearanceContext.FilmKind.REVERSAL,s.appearance!!.filmKind)
            assertEquals(FrameAppearanceContext.Material.POSITIVE_DISPLAY,s.appearance!!.material)
            val rgb=FloatArray(3)
            for(x in 0 until s.outputWidth step 3)for(y in 0 until s.outputHeight step 3){
                r.sample(s,x+.5,y+.5,rgb);assertTrue(rgb.all{it.isFinite() && it in 0f..1f})
            }
        }
        val s=snapshot("direct",MediumFormat.MF66,false);val rgb=FloatArray(3)
        r.sample(s,s.frameOriginX+s.pixelsPerMm,s.frameOriginY+30*s.pixelsPerMm,rgb)
        assertArrayEquals(FloatArray(3){8f/255},rgb,0f)
        assertThrows(IllegalArgumentException::class.java){snapshot("print",MediumFormat.MF66,false)}
    }
    @Test fun datesAndAllExposureFieldsStayCenteredAfterLowerMarksMove(){
        val r=raster()
        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:11:06 12:00:00",
            "PhotographicSensitivity" to "64","ExposureTime" to "1/125","FNumber" to "56/10",
            "ExposureBiasValue" to "1/2","FocalLength" to "80/1"))
        var cases=0
        for(route in listOf("direct"))for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val s=snapshot(route,f,p,1280,960);val g=s.mediumLayout!!
            val l=MediumImprintMarks.layout(s)!!
            assertTrue(l.protectPhotoDate);assertTrue(l.moves.isNotEmpty());assertTrue(ImprintPlan.exposureSupported(s))
            assertEquals(l.widthMm/2,(l.clearStartMm+l.clearEndMm)/2,1e-9)
            assertNotSame(r,MediumImprintMarks.wrap(s,r))
            for(style in ImprintSettings.DATE_STYLES.filter{it!="off"})for(place in listOf("edge","photo")){
                val settings=ImprintSettings(style,size=1.6,glow=1.0,exposure=true,datePlacement=place)
                val content=ImprintContent.from(metadata,settings)
                val plan=ImprintPlan.create(settings,metadata,s.outputWidth,s.outputHeight,s)
                assertEquals(content.fields.size+1,plan.stamps.size);assertEquals(0,plan.omittedFieldCount)
                val effective=plan.frameRaster(s,r)
                assertNotSame(r,effective)
                for((index,stamp) in plan.stamps.withIndex()){
                    if(place=="photo" && index==0){
                        assertTrue(stamp.left>=s.apertureLeft-1 && stamp.top>=s.apertureTop-1)
                        assertTrue(stamp.right<=s.apertureLeft+s.photoWidth+1 && stamp.bottom<=s.apertureTop+s.photoHeight+1)
                    }else{
                        val localStart=if(g.rotated)(stamp.top-s.frameOriginY)/s.pixelsPerMm else (stamp.left-s.frameOriginX)/s.pixelsPerMm
                        val localEnd=if(g.rotated)(stamp.bottom-s.frameOriginY)/s.pixelsPerMm else (stamp.right-s.frameOriginX)/s.pixelsPerMm
                        assertTrue("$f $p $place $style left=$localStart",localStart>=l.clearStartMm-1/s.pixelsPerMm)
                        assertTrue("$f $p $place $style right=$localEnd",localEnd<=l.clearEndMm+1/s.pixelsPerMm)
                        // Includes every glyph's glow/padding, not only its text core.
                        for(y in stamp.top until stamp.bottom step 2)for(x in stamp.left until stamp.right step 2){
                            val rgb=FloatArray(3);effective.sample(s,x+.5,y+.5,rgb)
                            assertArrayEquals(FloatArray(3){8f/255},rgb,.0001f)
                        }
                    }
                }
                cases++
            }
        }
        assertEquals(80,cases)
    }
}
