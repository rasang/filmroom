package dev.filmroom.presets

import android.app.Application
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class IndependentMediaSelectionHostTest {
    @Test fun fourCombinationsRoundTripWithoutNewDefaults() {
        for (film in listOf("none", "kodak_portra_400")) {
            for (paper in listOf("none", "kodak_portra_endura")) {
                val state = EditorState(filmStock = film, paperStock = paper)
                val decoded = PresetQrCodec.decode(PresetQrCodec.encode("Test", state)).parameters
                assertEquals(state, decoded)
                assertEquals(film != "none", state.hasFilm)
                assertEquals(paper != "none", state.hasPaper)
            }
        }
    }
    @Test fun filmSelectionPreservesIndependentPaperNone() {
        val state = EditorState(paperStock = "none")
        assertEquals("none", state.selectFilm("none", "none", "print", "none").paperStock)
        assertEquals("none", state.selectFilm("kodak_gold_200", "kodak_portra_endura", "print", "none").paperStock)
        val original = EditorState(paperStock = "kodak_2383")
        assertEquals("kodak_2383", original.selectFilm("none", "none", "print", "kodak_2383").paperStock)
    }
    @Test fun absenceDisablesOnlyDependentControlsWithoutChangingValues() {
        val original = EditorState()
        val noFilm = original.copy(filmStock = "none")
        assertTrue(noFilm.controlSupported("grain"))
        assertFalse(noFilm.controlSupported("halation"))
        assertTrue(noFilm.controlSupported("filmExposure"))
        assertTrue(noFilm.controlSupported("diffusionFilter"))
        assertEquals(original.grain, noFilm.grain)
        val noPaper = original.copy(paperStock = "none")
        assertFalse(noPaper.controlSupported("paperGamma"))
        assertFalse(noPaper.controlSupported("contrast"))
        assertFalse(noPaper.neutralDatabaseSupported)
        assertTrue(noPaper.controlSupported("brightness"))
        assertEquals(original.paperGamma, noPaper.paperGamma, 0f)
    }
    @Test fun explicitNoneIsAllowedButUnknownStockIsNeverSilentlyOmitted() {
        val catalog = org.json.JSONArray("""[{"id":"kodak_portra_400","kind":"negative"},{"id":"kodak_portra_endura","kind":"paper"}]""")
        fun valid(film:String,paper:String)=dev.filmroom.model.StockSelectionValidation.isSupported(EditorState(filmStock=film,paperStock=paper),catalog)
        for(film in listOf("none","kodak_portra_400")) for(paper in listOf("none","kodak_portra_endura")) assertTrue(valid(film,paper))
        assertFalse(valid("future_film","none"))
        assertFalse(valid("none","future_paper"))
        assertFalse(valid("kodak_portra_endura","none"))
    }
    @Test fun restoringPaperLeavesTheOmittedRouteWithoutLosingFilm() {
        val state=EditorState(filmStock="kodak_portra_400",paperStock="none",outputRoute="direct")
        val restored=state.selectPaper("kodak_portra_endura")
        assertEquals(state.filmStock,restored.filmStock)
        assertEquals("print",restored.outputRoute)
        assertEquals("none",state.selectPaper("none").paperStock)
    }
}
