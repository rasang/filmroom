package dev.filmroom

import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class BwProfileStateHostTest {
    @org.junit.Before fun language(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")}
    @Test fun actualPortraitAndLandscapeControlDefinitionsUseBwCapabilities(){
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val a=controller.get();val group=MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}
            val methods=listOf("controls","extraControls").map{MainActivity::class.java.getDeclaredMethod(it).apply{isAccessible=true}}
            val bw=EditorState(filmStock="kodak_doublex",paperStock="kodak_2302")
            val disabled=setOf("saturation","dirInterLayer","grainRedScale","grainGreenScale","grainBlueScale","halationColor","warmth","paperGammaFast","paperGammaSlow","paperGammaRed","paperGammaGreen","paperGammaBlue","paperDeveloperExhaustion")
            val found=mutableSetOf<String>()
            for(g in listOf("film","adjust","light","grain","develop","print","scan")){
                group.set(a,g)
                for(c in methods.flatMap{it.invoke(a) as List<*>}.filterNotNull()){
                    val id=c.javaClass.getDeclaredMethod("getId").apply{isAccessible=true}.invoke(c) as String
                    @Suppress("UNCHECKED_CAST") val active=c.javaClass.getDeclaredMethod("getActive").apply{isAccessible=true}.invoke(c) as (EditorState)->Boolean
                    if(id in disabled){found+=id;assertFalse(id,active(bw))}
                    if(id=="filmDevelopmentTime" || id=="paperDevelopmentTime")assertTrue(id,active(bw))
                    if(id=="neutralFiltersFromDatabase")assertTrue(id,active(bw))
                    if(id in setOf("neutralCyan","neutralMagenta","neutralYellow"))assertFalse(id,active(bw))
                }
            }
            assertEquals(disabled,found)
        }
    }

    @Test fun exactStockRolesAndPreferencesSurviveSwitchingAndPersistence(){
        val original=EditorState(saturation=1.7f,grainRedScale=2f,paperGammaRed=1.2f,filmDevelopmentTime=9f,paperDevelopmentTime=7f).selectOutputRoute("print")
        val negative=original.selectFilm("kodak_doublex","kodak_2302","print","kodak_portra_endura")
        assertEquals("kodak_2302",negative.paperStock);assertEquals("kodak_doublex",negative.frameStock)
        assertFalse(negative.controlSupported("saturation"));assertFalse(negative.controlSupported("paperGammaRed"))
        assertTrue(negative.negativeWhiteHolesSupported)
        val direct=negative.selectOutputRoute("direct");assertEquals("kodak_doublex",direct.frameStock);assertTrue(direct.negativeWhiteHolesSupported)
        val reversal=negative.selectFilm("kodak_trix","kodak_2302","direct","kodak_2302")
        assertEquals("direct",reversal.outputRoute);assertEquals("print",reversal.preferredOutputRoute)
        assertEquals(8f,reversal.filmFormatMm);assertFalse(reversal.controlSupported("filmDevelopmentTime"));assertFalse(reversal.negativeWhiteHolesSupported)
        assertEquals(reversal,EditorState.fromJson(reversal.json()))
        val color=reversal.selectFilm("kodak_portra_400","kodak_portra_endura","print","kodak_2302")
        assertEquals("print",color.outputRoute);assertEquals(35f,color.filmFormatMm)
        assertEquals(1.7f,color.saturation);assertEquals(2f,color.grainRedScale);assertEquals(1.2f,color.paperGammaRed)
        assertTrue(color.controlSupported("saturation"));assertTrue(color.controlSupported("paperGammaRed"))
        val json=JSONObject(negative.json(includePresentation=false));assertEquals(9.0,json.getDouble("filmDevelopmentTime"),0.0);assertEquals(7.0,json.getDouble("paperDevelopmentTime"),0.0)
    }
    @Test fun customFormatAndColourCompositionAreNotReset(){
        val custom=EditorState(filmFormatMm=60f).selectFilm("kodak_trix","kodak_2302","direct","kodak_portra_endura")
        assertEquals(60f,custom.filmFormatMm)
        val color=EditorState(filmBorder=true)
        assertNull(color.presentationTransform(300,200).targetAspect)
        assertEquals(color.composition,color.presentationTransform(300,200))
        val bw=color.copy(filmStock="kodak_trix",outputRoute="direct")
        assertEquals(1046.0/766.0,bw.presentationTransform(300,200).targetAspect!!,0.0)
        val explicit=bw.copy(composition=dev.filmroom.model.CompositionTransform(.6,.4,2.0,766.0/1046.0))
        val returned=explicit.selectFilm("kodak_portra_400","kodak_portra_endura","print","kodak_2302")
        assertEquals(2.0/3.0,returned.presentationTransform(300,200).targetAspect!!,0.0)
        assertEquals(.6,returned.presentationTransform(300,200).centerX,0.0)
        assertEquals(.4,returned.presentationTransform(300,200).centerY,0.0)
        assertEquals(explicit.composition,returned.composition)
        assertEquals(returned,EditorState.fromJson(returned.json()))
    }
}
