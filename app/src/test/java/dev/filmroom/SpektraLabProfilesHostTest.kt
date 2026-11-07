package dev.filmroom

import dev.filmroom.model.EditorState
import dev.filmroom.model.FilmProfileCapabilities
import dev.filmroom.border.BorderCatalog
import dev.filmroom.diagnostics.FilmProfile
import dev.filmroom.diagnostics.PaperProfile
import dev.filmroom.ui.FilmStockArtwork
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class SpektraLabProfilesHostTest {
    private val bw = FilmProfileCapabilities.spektraLabFilms - "cinestill_800t"
    private val paper = "ilford_multigrade_iv_rc"

    @Test fun catalogDefaultsAndFittedGradeTwoMatchSource() {
        val assets=RuntimeEnvironment.getApplication().assets
        val rows=JSONObject(assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        val catalog=(0 until rows.length()).map{rows.getJSONObject(it)}.associateBy{it.getString("id")}
        val filters=JSONObject(assets.open("engine-data/filters/neutral_print_filters.json").bufferedReader().use{it.readText()})
        assertEquals("paper",catalog.getValue(paper).getString("kind"))
        assertNotNull(PaperProfile.fromId(paper))
        for(id in FilmProfileCapabilities.spektraLabFilms) {
            val row=catalog.getValue(id)
            assertEquals("negative",row.getString("kind"))
            assertEquals("print",row.getString("defaultRoute"))
            assertEquals(if(id in bw)paper else "kodak_2383",row.getString("defaultPaper"))
            assertNotNull(FilmProfile.fromId(id))
            if(id in bw) {
                val pack=filters.getJSONObject(paper).getJSONObject("TH-KG3").getJSONArray(id)
                assertEquals(0.0,pack.getDouble(0),0.0)
                assertEquals(0.0,pack.getDouble(1),0.0)
                assertEquals(68.0,pack.getDouble(2),0.0)
            }
        }
    }
    @Test fun fixedDevelopmentAndSilverPaperKeepPhysicalFiltersAndSavedParameters() {
        val original=EditorState(saturation=1.6f,dirInterLayer=1.4f,neutralFiltersFromDatabase=false,
            neutralCyan=12f,neutralMagenta=23f,neutralYellow=45f,printExposureEv=.75f,
            printYellow=3f,printMagenta=-2f,filmDevelopmentTime=9f,paperDevelopmentTime=6f).selectOutputRoute("direct")
        for(id in bw) {
            val p=original.selectFilm(id,paper,"print","kodak_portra_endura")
            assertTrue(p.isMonochromeFilm);assertTrue(p.isMonochromePrint);assertFalse(p.isReversalFilm)
            for(control in listOf("saturation","dirInterLayer","grainRedScale","grainGreenScale","grainBlueScale","halationColor","filmDevelopmentTime","paperDevelopmentTime","warmth","paperGammaRed","paperGammaFast"))assertFalse(control,p.controlSupported(control))
            for(control in listOf("printYellow","printMagenta","neutralCyan","neutralMagenta","neutralYellow","paperGamma"))assertTrue(control,p.controlSupported(control))
            assertTrue(p.neutralDatabaseSupported);assertFalse(p.effectiveNeutralFiltersFromDatabase)
            assertEquals("direct",p.outputRoute);assertEquals(35f,p.filmFormatMm)
            assertEquals(p,EditorState.fromJson(p.json()))
            assertEquals(original,p.selectFilm("kodak_portra_400","kodak_portra_endura","print",paper))
            val defaults=p.processingDefaults(paper,"print")
            assertTrue(defaults.effectiveNeutralFiltersFromDatabase);assertEquals(0f,defaults.printExposureEv)
        }
        val custom=original.copy(paperStock="kodak_2383")
        assertEquals("kodak_2383",custom.selectFilm("kodak_tri_x_400",paper,"print","kodak_portra_endura").paperStock)
        assertFalse(EditorState(filmStock="cinestill_800t").isMonochromeFilm)
        assertTrue(EditorState(filmStock="kodak_trix").isReversalFilm)
    }
    @Test fun cineStillUsesItsOwnPsfWithoutDiscardingLegacyBounceControls() {
        val previous=EditorState(halationBounces=5,halationDecay=.85f)
        val p=previous.copy(filmStock="cinestill_800t")
        for(id in listOf("halationBounces","halationDecay"))assertFalse(id,p.controlSupported(id))
        for(id in listOf("halation","halationSize","halationColor","halationNormalize"))assertTrue(id,p.controlSupported(id))
        assertEquals(p,EditorState.fromJson(p.json()))
        assertEquals(5,p.halationBounces);assertEquals(.85f,p.halationDecay)
        val returned=p.copy(filmStock=previous.filmStock)
        assertEquals(previous,returned)
        for(id in listOf("halationBounces","halationDecay"))assertTrue(id,returned.controlSupported(id))
    }
    @Test fun reviewedSelectorArtworkNeverBorrowsAnotherStockOrEnablesAnUnreviewedFrame() {
        val app=RuntimeEnvironment.getApplication()
        for(id in FilmProfileCapabilities.spektraLabFilms) {
            assertEquals("film-rolls/$id.webp",FilmStockArtwork.assetPath(id))
            assertTrue(FilmStockArtwork.drawable(app,id) is android.graphics.drawable.BitmapDrawable)
            assertTrue(BorderCatalog.available(id))
            val p=EditorState(filmBorder=true).selectFilm(id,if(id in bw)paper else "kodak_2383","print","kodak_portra_endura")
            val has120=dev.filmroom.border.MediumFormatStocks.available(id)
            assertTrue(has120);assertTrue(p.filmBorder);assertTrue(p.negativeWhiteHolesSupported)
            assertNull(p.effectiveMediumFormat) // Fresh approved 135 selection stays on 135.
            assertEquals(id,p.frameStock)
        }
    }
}
