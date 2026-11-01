package dev.filmroom

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FilmEffectsControlsHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList())
    private fun button(activity:MainActivity,label:String)=all(activity.window.decorView).filterIsInstance<Button>().first{it.text.toString()==label}
    @Test fun categoriesShowOnlyOneParameterWithHelpOnDemand(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val activity=controller.get()
            for(label in listOf("胶片","调整","光效","颗粒","印相","扫描"))assertNotNull(button(activity,label))
            button(activity,"光效").performClick()
            assertNotNull(button(activity,"柔光类型"));assertNotNull(button(activity,"柔光强度"))
            button(activity,"柔光强度").performClick()
            assertEquals(1,all(activity.window.decorView).filterIsInstance<android.widget.SeekBar>().size)
            button(activity,"印相").performClick();button(activity,"预曝光").performClick()
            val views=all(activity.window.decorView)
            assertEquals(1,views.filterIsInstance<android.widget.SeekBar>().size)
            assertTrue(views.any{it.contentDescription?.toString()=="预曝光说明"})
            assertFalse(views.filterIsInstance<TextView>().any{it.text.contains("数值是额外曝光比例")})
            views.first{it.contentDescription?.toString()=="预曝光说明"}.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text.contains("数值是额外曝光比例")})
        }
    }
    @Test fun irrelevantControlsAreDisabledByTheActualPipelineRoute(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val a=controller.get()
            val group=MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}
            val base=MainActivity::class.java.getDeclaredMethod("controls").apply{isAccessible=true}
            val extra=MainActivity::class.java.getDeclaredMethod("extraControls").apply{isAccessible=true}
            fun control(id:String):Any = ((base.invoke(a) as List<*>)+(extra.invoke(a) as List<*>)).filterNotNull().first{
                it.javaClass.getDeclaredMethod("getId").apply{isAccessible=true}.invoke(it)==id
            }
            @Suppress("UNCHECKED_CAST")
            fun active(id:String,p:dev.filmroom.model.EditorState):Boolean {
                val c=control(id);return (c.javaClass.getDeclaredMethod("getActive").apply{isAccessible=true}.invoke(c) as (dev.filmroom.model.EditorState)->Boolean)(p)
            }
            val direct=dev.filmroom.model.EditorState(outputRoute="direct")
            group.set(a,"adjust");assertFalse(active("contrast",direct));assertFalse(active("warmth",direct))
            group.set(a,"print")
            for(id in listOf("printYellow","printMagenta","preflash","printDiffusionFilter","paperGammaRed","neutralCyan"))assertFalse(id,active(id,direct))
            assertFalse(active("neutralCyan",direct.copy(outputRoute="print")))
            assertTrue(active("neutralCyan",direct.copy(outputRoute="print",neutralFiltersFromDatabase=false)))
            group.set(a,"grain");assertFalse(active("grainLayers",direct));assertTrue(active("grainLayers",direct.copy(grainSublayers=false)))
        }
    }

    @Test fun everySupportedToolIsDirectlyReachableWithoutFineExpansion() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val a=controller.get()
            val group=MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}
            val base=MainActivity::class.java.getDeclaredMethod("controls").apply{isAccessible=true}
            val extra=MainActivity::class.java.getDeclaredMethod("extraControls").apply{isAccessible=true}
            val build=MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}
            val icons=mutableSetOf<Int>();val covered=mutableSetOf<String>();var count=0
            for (category in listOf("film","adjust","light","grain","develop","print","scan")) {
                group.set(a,category);build.invoke(a)
                val controls=((base.invoke(a) as List<*>)+(extra.invoke(a) as List<*>)).filterNotNull()
                val views=all(a.window.decorView)
                for(control in controls) {
                    val id=control.javaClass.getDeclaredMethod("getId").apply{isAccessible=true}.invoke(control)
                    assertTrue("Missing $id",views.any{it.tag==id})
                    icons.add(control.javaClass.getDeclaredMethod("getIcon").apply{isAccessible=true}.invoke(control) as Int)
                    @Suppress("UNCHECKED_CAST")
                    covered.addAll(control.javaClass.getDeclaredMethod("getParameterIds").apply{isAccessible=true}.invoke(control) as Set<String>)
                    count++
                }
                assertFalse(views.filterIsInstance<TextView>().any{it.text=="精细"})
                assertTrue(views.filterIsInstance<android.widget.SeekBar>().size<=1)
            }
            assertTrue("Supported controls disappeared",covered.size>=75)
            assertTrue("Tools still share generic icons",icons.size>=40)
            assertTrue(covered.containsAll(listOf("autoExposure","metering")))
            assertEquals("Exactly one compound control",count+1,covered.size)
        }
    }
    @Test fun shortChoicesUseInlineRailWhilePaperNamesRemainAReadableList() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val a=controller.get()
            button(a,"胶片").performClick();button(a,"自动曝光").performClick()
            assertEquals(1,all(a.window.decorView).filterIsInstance<dev.filmroom.ui.SettledChoiceSelector>().size)
            button(a,"印相").performClick();button(a,"相纸").performClick()
            assertEquals(0,all(a.window.decorView).filterIsInstance<dev.filmroom.ui.SettledChoiceSelector>().size)
        }
    }

}
