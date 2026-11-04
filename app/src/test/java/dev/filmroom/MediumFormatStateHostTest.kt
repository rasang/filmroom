package dev.filmroom

import android.app.Application
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class MediumFormatStateHostTest {
    @Test fun legacyDefaultsAndScientificParametersStayUnchanged() {
        val legacy=EditorState(filmBorder=true,composition=CompositionTransform(.6,.4,2.0))
        assertNull(legacy.mediumFormat)
        assertFalse(legacy.mediumPortrait)
        assertEquals(legacy,EditorState.fromJson(legacy.json()))
        for(format in MediumFormat.entries)for(portrait in listOf(false,true)) {
            val next=legacy.selectMediumFormat(format,portrait)
            assertEquals(legacy.json(includePresentation=false),next.json(includePresentation=false))
            assertEquals(35f,next.filmFormatMm,0f)
            assertEquals(next,EditorState.fromJson(next.json()))
            assertFalse(next.negativeWhiteHolesSupported)
            val transform=next.presentationTransform(6000,4000)
            assertEquals(BorderCatalog.frameAspect(next.frameStock,portrait,format),transform.targetAspect!!,0.0)
            val reset=next.processingDefaults("kodak_portra_endura","print")
            assertEquals(format,reset.mediumFormat);assertEquals(portrait,reset.mediumPortrait)
            assertEquals(next.composition,reset.composition)
        }
    }
    @Test fun stockSelectionKeepsOnlyReal120CapabilitiesAndPushAlias() {
        val p=EditorState(filmBorder=true).selectMediumFormat(MediumFormat.MF617,true)
        for(stock in listOf("kodak_portra_800_push1","kodak_portra_800_push2","fujifilm_pro_400h")) {
            val chosen=p.selectFilm(stock,"kodak_portra_endura","print",p.paperStock)
            assertEquals(MediumFormat.MF617,chosen.effectiveMediumFormat)
            assertTrue(chosen.effectiveMediumPortrait)
        }
        for(stock in listOf("fujifilm_c200","fujifilm_xtra_400","kodak_ultramax_400","kodak_kodachrome_64","kodak_doublex","kodak_trix")) {
            val chosen=p.selectFilm(stock,"kodak_portra_endura",if(stock in listOf("kodak_kodachrome_64","kodak_trix"))"direct" else "print",p.paperStock)
            assertNull(chosen.mediumFormat);assertFalse(chosen.mediumPortrait)
            assertNull(chosen.effectiveMediumFormat)
        }
        val square=p.selectMediumFormat(MediumFormat.MF66,true)
        assertEquals(1.0,square.presentationTransform(6000,4000).targetAspect!!,0.0)
        assertTrue(EditorState.fromJson(square.json()).mediumPortrait)
        assertFalse(square.selectMediumFormat(null,false).mediumPortrait)
    }
    @Test fun queueVersionSixFreezesFormatOrientationAndKeepsLegacyJobs() {
        val root=Files.createTempDirectory("medium-state-queue").toFile()
        try {
            val store=ExportJobStore(root)
            val records=mutableListOf<ExportJob>()
            for(format in MediumFormat.entries)for(portrait in listOf(false,true))for(route in listOf("print","direct")) {
                val p=EditorState(filmBorder=true,outputRoute=route).selectMediumFormat(format,portrait)
                val s=BorderCatalog.snapshot(p.frameStock,192,128,6000,4000,p.composition,p.negativeWhiteHoles,p.outputRoute,p.effectiveMediumFormat,p.effectiveMediumPortrait)
                val job=ExportJob(ExportJobStore.newId(),records.size.toLong()+1,p,192,128,ExportFormat.PNG,95,
                    state=ExportJobState.PAUSED,sourceWidth=6000,sourceHeight=4000,border=s)
                records+=job;store.update(job)
                assertEquals(6,JSONObject(store.directory(job.id).resolve("manifest.json").readText()).getInt("version"))
            }
            val legacy=EditorState(filmBorder=true)
            val s=BorderCatalog.snapshot(legacy.frameStock,192,128,192,128,legacy.composition,true,"print")
            val old=ExportJob(ExportJobStore.newId(),100,legacy,192,128,ExportFormat.PNG,95,state=ExportJobState.PAUSED,border=s)
            records+=old;store.update(old)
            assertEquals(5,JSONObject(store.directory(old.id).resolve("manifest.json").readText()).getInt("version"))
            val reopened=ExportJobStore(root)
            assertEquals(records.size,reopened.jobs.value.size)
            records.forEach { expected ->
                val actual=checkNotNull(reopened.get(expected.id))
                assertEquals(expected.parameters,actual.parameters);assertEquals(expected.border,actual.border)
                BorderCatalog.verifySnapshot(checkNotNull(actual.border))
            }
            val medium=records.first()
            try { medium.copy(parameters=medium.parameters.copy(mediumFormat=null));fail("Must reject frame/parameter mismatch") }catch(_:IllegalArgumentException){}
            val manifest=store.directory(medium.id).resolve("manifest.json")
            manifest.writeText(JSONObject(manifest.readText()).put("version",5).toString())
            val invalid=checkNotNull(ExportJobStore(root).get(medium.id))
            assertEquals(ExportJobState.FAILED,invalid.state);assertFalse(invalid.inputReady)
        }finally{root.deleteRecursively()}
    }
}
