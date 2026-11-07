package dev.filmroom.border

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.imprint.ImprintPlan
import dev.filmroom.imprint.ImprintSettings
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.io.PhotoMetadata
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
class Medium120EvidenceHostTest {
    private val added=setOf("ilford_hp5_plus_400","kodak_tmax_100","kodak_tri_x_400")
    private fun snapshot(stock:String,route:String="print",format:MediumFormat=MediumFormat.MF617,portrait:Boolean=false)=
        BorderCatalog.snapshot(stock,181,137,181,137,CompositionTransform(),true,route,format,portrait)
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}

    @Test fun reviewedStillProductsHaveIndependentVersionsAndFiniteCoverage(){
        for(stock in added){
            val d=MediumStripDefinitions.definitions.getValue(stock)
            assertEquals(stock,MediumFormatStocks.physicalStock(stock))
            assertTrue(d.monochrome);assertTrue(d.coverageStartMm>300);assertTrue(d.coverageEndMm<510)
            assertTrue(BorderCatalog.available(stock,null)) // Separately approved own 135 atlas, not borrowed from 120.
            assertEquals(stock,Reviewed135Definitions.profiles.getValue(stock).physicalStock)
            for(f in MediumFormat.entries)for(p in listOf(false,true))for(route in listOf("print","direct")){
                val s=snapshot(stock,route,f,p)
                assertEquals(d.version,s.assetVersion);assertEquals(s,BorderSnapshot.fromJson(s.json()))
                BorderCatalog.verifySnapshot(s)
                assertEquals(61.0,s.mediumLayout!!.let{if(it.rotated)it.width else it.height},0.0)
                assertEquals(FrameAppearanceContext.FilmKind.NEGATIVE,s.appearance!!.filmKind)
            }
            val s=snapshot(stock)
            rejected{BorderCatalog.verifySnapshot(s.copy(stripCenterMm=500.0))}
            rejected{BorderCatalog.verifySnapshot(s.copy(assetVersion="mf120-invented"))}
            assertNotNull(MediumImprintMarks.layout(s));assertTrue(ImprintPlan.exposureSupported(s))
            // Existing v7 frozen queues keep their original no-edge-imprint contract.
            val legacy=s.copy(assetVersion=s.assetVersion.replace("v11-", "v7-").replace("-imprint-", "-"))
            BorderCatalog.verifySnapshot(legacy)
            assertNull(MediumImprintMarks.layout(legacy));assertFalse(ImprintPlan.exposureSupported(legacy))
        }
        val tmax=MediumStripDefinitions.definitions.getValue("kodak_tmax_100")
        assertTrue(tmax.coverageEndMm-tmax.coverageStartMm>=174)
        assertTrue(tmax.coverageEndMm-tmax.coverageStartMm<175)
        assertFalse(MediumStripDefinitions.definitions.getValue("kodak_portra_400").monochrome)
    }

    @Test fun existing120EvidenceAloneDoesNotEnableUnreviewedBorders(){
        val blocked=listOf("kodak_ultramax_400","kodak_vision3_50d","kodak_vision3_250d","kodak_verita_200d",
            "kodak_vision3_200t","kodak_vision3_500t","fujifilm_c200","fujifilm_xtra_400",
            "kodak_trix","kodak_doublex")
        for(stock in blocked)for(f in MediumFormat.entries){
            assertFalse(stock,BorderCatalog.available(stock,f))
            rejected{snapshot(stock,format=f)}
        }
        assertNotEquals(FrameProfileCatalog.kind("kodak_trix"),FrameProfileCatalog.kind("kodak_tri_x_400"))
    }

    @Test fun bwBothRoutesRemainNeutralWithoutChangingLegacyOrangeMask(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        for(stock in added){
            val d=MediumStripDefinitions.definitions.getValue(stock)
            val bitmap=app.assets.open(d.path).use{BitmapFactory.decodeStream(it)}!!
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle()
            val raster=MediumStripRaster(d,pixels)
            assertEquals(1640000L,raster.residentBytes)
            for(route in listOf("print","direct"))for(f in MediumFormat.entries)for(p in listOf(false,true)){
                val s=snapshot(stock,route,f,p);raster.validate(s)
                val rgb=FloatArray(3)
                for(x in 0 until s.outputWidth step 3)for(y in 0 until s.outputHeight step 3){
                    raster.sample(s,x+.5,y+.5,rgb)
                    assertEquals(rgb[0],rgb[1],0f);assertEquals(rgb[1],rgb[2],0f)
                    assertTrue(rgb.all{it.isFinite() && it in 0f..1f})
                }
            }
            // A neutral base in negative route is .70, while the print base remains 8/255.
            for(route in listOf("print","direct")){
                val s=snapshot(stock,route);val rgb=FloatArray(3)
                raster.sample(s,s.frameOriginX+5*s.pixelsPerMm,s.frameOriginY+30*s.pixelsPerMm,rgb)
                assertEquals(if(route=="direct").70f else 8f/255,rgb[0],0f)
            }
        }
        val d=MediumStripDefinitions.definitions.getValue("kodak_portra_400")
        val r=MediumStripRaster(d,IntArray(d.width*d.height){0xff080808.toInt()})
        val s=snapshot(d.stock,"direct");val rgb=FloatArray(3)
        r.sample(s,s.frameOriginX+5*s.pixelsPerMm,s.frameOriginY+30*s.pixelsPerMm,rgb)
        assertArrayEquals(floatArrayOf(.58f,.29f,.12f),rgb,0f)
    }

    @Test fun frozenV7EdgeMetadataStillFallsBackToPhotoDateWithBwTone(){
        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:11:06 12:00:00",
            "PhotographicSensitivity" to "400","ExposureTime" to "1/125","FNumber" to "56/10"))
        for(stock in added)for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val current=BorderCatalog.snapshot(stock,1280,960,1280,960,CompositionTransform(),true,"print",f,p)
            val s=current.copy(assetVersion=current.assetVersion.replace("v11-", "v7-").replace("-imprint-", "-"))
            BorderCatalog.verifySnapshot(s)
            val plan=ImprintPlan.create(ImprintSettings("contax_t2",exposure=true,datePlacement="edge"),
                metadata,s.outputWidth,s.outputHeight,s)
            assertEquals(1,plan.stamps.size) // Date on photo; no unreviewed exposure gap.
            val stamp=plan.stamps.single()
            assertTrue(stamp.left>=0 && stamp.top>=0 && stamp.right<=s.outputWidth && stamp.bottom<=s.outputHeight)
            var colored=0
            for(y in stamp.top until stamp.bottom){
                val row=FloatArray(s.outputWidth*3)
                plan.blendRow(y,row,OutputColorSpace.SRGB)
                for(x in stamp.left until stamp.right)if(row[x*3]>0){
                    assertEquals(row[x*3],row[x*3+1],0f) // B&W date palette, not warm orange.
                    colored++
                }
            }
            assertTrue(colored>0)
        }
    }
}
