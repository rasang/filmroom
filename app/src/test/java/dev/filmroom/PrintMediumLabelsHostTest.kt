package dev.filmroom

import android.app.Application
import dev.filmroom.i18n.L10n
import dev.filmroom.model.EditorState
import dev.filmroom.model.FilmProfileCapabilities
import dev.filmroom.ui.PrintMediumLabels
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PrintMediumLabelsHostTest {
    @Test fun printSupportAndColorModelAreIndependent() {
        assertEquals(R.string.print_medium_bw_paper,PrintMediumLabels.suffixResource("ilford_multigrade_iv_rc","paper"))
        assertEquals(R.string.print_medium_bw_film,PrintMediumLabels.suffixResource("kodak_2302","cine_print"))
        assertEquals(R.string.text_3b789c2037e2,PrintMediumLabels.suffixResource("kodak_portra_endura","paper"))
        assertEquals(R.string.text_b9afff7ca1b0,PrintMediumLabels.suffixResource("kodak_2383","cine_print"))
    }
    @Test fun everyCatalogPrintLabelMatchesItsActualScientificProfile() {
        val assets=RuntimeEnvironment.getApplication().assets
        val rows=JSONObject(assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        var count=0
        val mono=mutableSetOf<String>()
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val kind=row.getString("kind")
            if(kind!="paper" && kind!="cine_print")continue
            val id=row.getString("id")
            val info=JSONObject(assets.open("engine-data/profiles/$id.json").bufferedReader().use{it.readText()}).getJSONObject("info")
            val bw=info.optString("channel_model")=="bw"
            if(bw)mono.add(id)
            val expected=when {
                kind=="paper" && bw -> R.string.print_medium_bw_paper
                kind=="paper" -> R.string.text_3b789c2037e2
                bw -> R.string.print_medium_bw_film
                else -> R.string.text_b9afff7ca1b0
            }
            assertEquals(id,expected,PrintMediumLabels.suffixResource(id,kind))
            assertEquals(id,bw,EditorState(paperStock=id).isMonochromePrint)
            count++
        }
        assertEquals(10,count);assertEquals(FilmProfileCapabilities.monochromePrints,mono)
    }
    @Test fun allFourLanguagesNameBothBlackAndWhiteMediaCorrectly() {
        val app=RuntimeEnvironment.getApplication()
        val expected=mapOf("zh" to listOf("黑白相纸","黑白电影拷贝片","彩色相纸","彩色电影拷贝片"),
            "en" to listOf("Black-and-white paper","Black-and-white motion-picture print film","Color paper","Color motion-picture print film"),
            "ja" to listOf("モノクロ印画紙","モノクロ映画用プリントフィルム","カラー印画紙","カラー映画用プリントフィルム"),
            "ko" to listOf("흑백 인화지","흑백 영화용 프린트 필름","컬러 인화지","컬러 영화용 프린트 필름"))
        try { for((language,labels) in expected) {
            L10n.choose(app,language)
            val cases=listOf("ilford_multigrade_iv_rc" to "paper","kodak_2302" to "cine_print","kodak_portra_endura" to "paper","kodak_2383" to "cine_print")
            for((index,pair) in cases.withIndex())assertEquals(language,"Fixture · ${labels[index]}",PrintMediumLabels.label(pair.first,"Fixture",pair.second))
            assertTrue(L10n.text(R.string.print_medium_bw_help).isNotBlank())
        }} finally { L10n.choose(app,"zh") }
    }
    @Test fun colorFilmMayDeliberatelyUseSilverPaperAndManualSelectionSurvivesFilmChange() {
        val p=EditorState(filmStock="kodak_portra_400",paperStock="ilford_multigrade_iv_rc")
        assertFalse(p.isMonochromeFilm);assertTrue(p.isMonochromePrint)
        assertEquals("print",p.outputRoute)
        val next=p.selectFilm("cinestill_800t","kodak_2383","print","kodak_portra_endura")
        assertEquals("ilford_multigrade_iv_rc",next.paperStock)
        assertTrue(next.isMonochromePrint);assertFalse(next.isMonochromeFilm)
        assertEquals(next,EditorState.fromJson(next.json()))
        val bw=EditorState().selectFilm("fujifilm_neopan_acros_100_ii","ilford_multigrade_iv_rc","print","kodak_portra_endura")
        assertEquals("ilford_multigrade_iv_rc",bw.paperStock)
        assertEquals("kodak_portra_endura",bw.selectFilm("kodak_portra_400","kodak_portra_endura","print","ilford_multigrade_iv_rc").paperStock)
    }
}
