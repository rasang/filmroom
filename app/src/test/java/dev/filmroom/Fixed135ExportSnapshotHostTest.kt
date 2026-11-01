package dev.filmroom

import dev.filmroom.border.*
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class Fixed135ExportSnapshotHostTest {
    private fun job(w:Int,h:Int,cw:Int=w,ch:Int=h):ExportJob {
        val crop=CompositionTransform(.7,.3,2.5).clamped(cw,ch)
        val params=EditorState(filmBorder=true,composition=crop)
        val border=BorderSnapshot.fixed135(params.filmStock,"authored-fixture-135",w,h,cw,ch,crop)
        return ExportJob(ExportJobStore.newId(),1,params,w,h,ExportFormat.TIFF16,95,
            sourceWidth=cw,sourceHeight=ch,border=border)
    }
    @Test fun frozenCanonicalCropAndAssetSurviveRestartAndRetryWithoutReframing() {
        val root=Files.createTempDirectory("fixed135-jobs").toFile()
        try {
            for((w,h) in listOf(96 to 64,64 to 96,96 to 96,1200 to 13,13 to 1200)) {
                val original=job(w,h,w*2,h*2)
                val store=ExportJobStore(root);store.update(original)
                val manifest=JSONObject(File(store.directory(original.id),"manifest.json").readText())
                assertEquals(3,manifest.getInt("version"))
                val restored=ExportJobStore(root).get(original.id)!!
                assertEquals(ExportJobState.PAUSED,restored.state)
                assertEquals(original.border,restored.border);assertEquals(original.parameters,restored.parameters)
                assertEquals(w,restored.border!!.sourceWidth);assertEquals(h,restored.border.sourceHeight)
                assertEquals(w*2,restored.border.canonicalWidth);assertEquals(h*2,restored.border.canonicalHeight)
                // New editor composition and mode cannot mutate an immutable queued snapshot.
                val later=original.parameters.copy(composition=CompositionTransform(),filmBorder=false)
                assertNotEquals(later,restored.parameters)
                store.update(restored.copy(state=ExportJobState.RETRY))
                assertEquals(original.border,ExportJobStore(root).get(original.id)!!.border)
            }
        } finally {root.deleteRecursively()}
    }
    @Test fun legacyUnframedAndUncroppedJobsKeepTheirOriginalGeometry() {
        val root=Files.createTempDirectory("fixed135-migration").toFile()
        try {
            val params=EditorState(filmBorder=true)
            val border=BorderSnapshot.create(params.filmStock,"authored-legacy",96,64,.2,.03,4.0)
            val old=ExportJob(ExportJobStore.newId(),1,params,96,64,ExportFormat.PNG,95,border=border)
            val plain=old.copy(id=ExportJobStore.newId(),parameters=EditorState(),border=null)
            val store=ExportJobStore(root);store.update(old);store.update(plain)
            assertEquals(2,JSONObject(File(store.directory(old.id),"manifest.json").readText()).getInt("version"))
            assertEquals(1,JSONObject(File(store.directory(plain.id),"manifest.json").readText()).getInt("version"))
            val recovered=ExportJobStore(root)
            assertEquals(border,recovered.get(old.id)!!.border)
            assertEquals(1,recovered.get(old.id)!!.border!!.geometryVersion)
            assertNull(recovered.get(plain.id)!!.border)
        } finally {root.deleteRecursively()}
    }
    @Test fun mismatchedSourceCropOrManifestVersionFailsClosed() {
        val original=job(96,96,192,192)
        assertThrows(IllegalArgumentException::class.java){original.copy(width=95)}
        assertThrows(IllegalArgumentException::class.java){original.copy(sourceHeight=193)}
        assertThrows(IllegalArgumentException::class.java){original.copy(parameters=original.parameters.copy(composition=CompositionTransform()))}
        val root=Files.createTempDirectory("fixed135-invalid").toFile()
        try {
            val store=ExportJobStore(root);store.update(original)
            val file=File(store.directory(original.id),"manifest.json")
            val valid=file.readText()
            for(version in listOf(1,2,4)) {
                file.writeText(JSONObject(valid).put("version",version).toString())
                val rejected=ExportJobStore(root).get(original.id)!!
                assertEquals(ExportJobState.FAILED,rejected.state);assertFalse(rejected.inputReady)
                assertNull(rejected.border)
            }
        } finally {root.deleteRecursively()}
    }
    @Test fun outputOptionUsesFixed135AndCanonicalOrientationWithoutLegacyGrowth() {
        val raster=object:BorderRaster {
            override val stock="fixture_only";override val version="authored-fixture"
            override val residentBytes=12L;override val railRatio=.2;override val endRatio=.03
            override val featherTotalAt3072=4.0
            override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(0f)}
        }
        val provider=object:BorderProvider {
            override fun snapshot(stock:String,width:Int,height:Int)=BorderSnapshot.create(stock,raster.version,width,height,.2,.03,4.0)
            override fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:CompositionTransform)=
                BorderSnapshot.fixed135(stock,raster.version,width,height,canonicalWidth,canonicalHeight,composition).also(raster::validate)
            override fun resolve(snapshot:BorderSnapshot)=raster
        }
        val landscape=BorderExportPolicy.option(raster.stock,30000,1000,true,provider)
        assertTrue(landscape.available) // Cropping now supersedes the old no-crop width rejection.
        assertEquals("135 构图 + 齿边 → 1584 × 1459",landscape.label)
        val portrait=BorderExportPolicy.option(raster.stock,13,1200,true,provider,26,2400)
        assertTrue(portrait.available)
        assertFalse(BorderExportPolicy.option(raster.stock,96,64,true).available) // Authored fixture stock is never registered as a real stock.
    }
    @Test fun candidate84mpCodecAllowsFinal135CanvasAndNeverChangesSource() {
        val border=BorderSnapshot.fixed135("fixture_only","authored-fixture",8820,5880)
        assertEquals(8820,border.sourceWidth);assertEquals(5880,border.sourceHeight)
        assertTrue(border.outputWidth.toLong()*border.outputHeight in 79_000_000L..84_000_000L)
        BorderExportPolicy.validate(border,ExportFormat.JPEG)
        BorderExportPolicy.validate(border,ExportFormat.TIFF16)
        BorderExportPolicy.validate(border,ExportFormat.PNG)
        val bytes=border.outputWidth.toLong()*border.outputHeight*12
        assertEquals(bytes*3+8L*1024*1024,BorderExportPolicy.additionalDiskBytes(border))
    }

}
