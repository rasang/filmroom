package dev.filmroom

import dev.filmroom.model.BwNeutralFilterEntries
import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class BwNeutralFiltersHostTest {
    @Test fun bwAvailabilityMatchesEveryBundledPairAndIlluminant(){
        val assets=RuntimeEnvironment.getApplication().assets
        val db=JSONObject(assets.open("engine-data/filters/neutral_print_filters.json").bufferedReader().use{it.readText()})
        val catalog=JSONObject(assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        val rows=(0 until catalog.length()).map{catalog.getJSONObject(it)}
        val films=rows.filter{it.getString("kind") in listOf("negative","reversal")}.map{it.getString("id")}
        val papers=rows.filter{it.getString("kind") in listOf("paper","cine_print")}.map{it.getString("id")}
        assertEquals(22,films.size);assertEquals(9,papers.size)
        var added=0
        for(film in films)for(paper in papers)for(light in listOf("TH-KG3","D65","D55","A")){
            val state=EditorState(filmStock=film,paperStock=paper,printIlluminant=light)
            if(state.isMonochromeFilm || state.isMonochromePrint){
                val present=db.optJSONObject(paper)?.optJSONObject(light)?.has(film)==true
                assertEquals("$film/$paper/$light",present,BwNeutralFilterEntries.contains(film,paper,light))
                assertEquals(present,state.neutralDatabaseSupported)
                assertEquals(present,state.effectiveNeutralFiltersFromDatabase)
                if(present)added++
            }
        }
        assertEquals(38,added)
        assertFalse(BwNeutralFilterEntries.contains("unknown","kodak_2302","TH-KG3"))
    }
    @Test fun savedManualChoiceAndValuesSurviveSupportedAndUnsupportedIlluminants(){
        val p=EditorState(filmStock="kodak_doublex",paperStock="fujifilm_crystal_archive_typeii",neutralMagenta=72f,neutralYellow=123f,neutralFiltersFromDatabase=false)
        assertTrue(p.neutralDatabaseSupported);assertFalse(p.effectiveNeutralFiltersFromDatabase)
        assertEquals(p,EditorState.fromJson(p.json()))
        val auto=p.copy(neutralFiltersFromDatabase=true)
        assertTrue(auto.effectiveNeutralFiltersFromDatabase)
        val unsupported=auto.copy(printIlluminant="D65")
        assertFalse(unsupported.neutralDatabaseSupported);assertFalse(unsupported.effectiveNeutralFiltersFromDatabase)
        assertTrue(unsupported.neutralFiltersFromDatabase)
        assertEquals(72f,unsupported.neutralMagenta);assertEquals(123f,unsupported.neutralYellow)
        assertEquals(auto,unsupported.copy(printIlluminant="TH-KG3"))
    }
}
