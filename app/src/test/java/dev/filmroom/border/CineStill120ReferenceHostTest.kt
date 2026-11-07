package dev.filmroom.border

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.imprint.ImprintPlan
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** User-approved appearance, not an authenticated continuous-roll or density measurement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CineStill120ReferenceHostTest {
    private val stock="cinestill_800t"
    private fun snapshot(route:String,format:MediumFormat,portrait:Boolean)=
        BorderCatalog.snapshot(stock,181,137,181,137,CompositionTransform(),true,route,format,portrait)

    @Test fun ownUnperforated120VersionHasAllNominalCropsButDoesNotAlias5219(){
        val d=MediumStripDefinitions.definitions.getValue(stock)
        assertEquals(stock,MediumFormatStocks.physicalStock(stock))
        assertTrue(d.version.startsWith("mf120-v8-cinestill800t-"))
        assertFalse(d.monochrome)
        assertEquals(310.209,d.coverageEndMm-d.coverageStartMm,1e-6)
        assertTrue(BorderCatalog.available(stock,null)) // Own reviewed 135; never borrow a motion edge.
        assertEquals(stock,Reviewed135Definitions.profiles.getValue(stock).physicalStock)
        assertFalse(MediumFormatStocks.available("kodak_vision3_500t"))
        assertTrue(MediumFormatStocks.available("fujifilm_neopan_acros_100_ii"))
        for(route in listOf("print","direct"))for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val s=snapshot(route,f,p)
            assertEquals(d.version,s.assetVersion)
            assertEquals(s,BorderSnapshot.fromJson(s.json()))
            BorderCatalog.verifySnapshot(s)
            assertTrue(ImprintPlan.exposureSupported(s))
            assertTrue(MediumImprintMarks.layout(s)!!.protectPhotoDate)
            assertThrows(IllegalArgumentException::class.java){BorderCatalog.verifySnapshot(s.copy(stripCenterMm=600.0))}
            assertThrows(IllegalArgumentException::class.java){BorderCatalog.verifySnapshot(s.copy(assetVersion="unreviewed-800t"))}
            assertFalse(EditorState(filmStock=stock).selectMediumFormat(f,p).copy(filmBorder=true).negativeWhiteHolesSupported)
        }
    }

    @Test fun drawnAmberAndSyntheticNegativeStayOpaqueAndFinite(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        val d=MediumStripDefinitions.definitions.getValue(stock)
        val bytes=app.assets.open(d.path).use{it.readBytes()}
        assertEquals(d.encodedBytes,bytes.size)
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        assertEquals(d.sha256,sha)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!
        assertEquals(8200,bitmap.width);assertEquals(50,bitmap.height)
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle()
        assertTrue(pixels.all{it ushr 24==255})
        assertTrue(pixels.any{((it ushr 16)and 255)>180 && ((it ushr 8)and 255) in 100..190 && (it and 255)<90})
        val r=MediumStripRaster(d,pixels);assertEquals(1640000L,r.residentBytes)
        for(route in listOf("print","direct"))for(f in MediumFormat.entries)for(p in listOf(false,true)){
            val s=snapshot(route,f,p);r.validate(s);val rgb=FloatArray(3)
            for(x in 0 until s.outputWidth step 3)for(y in 0 until s.outputHeight step 3){
                r.sample(s,x+.5,y+.5,rgb);assertTrue(rgb.all{it.isFinite() && it in 0f..1f})
            }
        }
        val s=snapshot("direct",MediumFormat.MF66,false);val rgb=FloatArray(3)
        r.sample(s,s.frameOriginX+1*s.pixelsPerMm,s.frameOriginY+30*s.pixelsPerMm,rgb)
        assertArrayEquals(floatArrayOf(.58f,.29f,.12f),rgb,0f)
    }
}
