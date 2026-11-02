package dev.filmroom

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Composition belongs to the film tool strip, never to the photo overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h792dp-mdpi")
class CompositionOverlayHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a) as View
    private fun layout(v:View,w:Int,h:Int){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    @Test fun compositionActionLivesBetweenFilmStockAndPhysicalFormatAcrossLayouts(){
        try { for((w,h) in listOf(320 to 640,360 to 792,792 to 360,480 to 320))for(font in listOf(1f,2f)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(font)
            RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
            val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val a=controller.get();val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val edit=if(w>h)root.findViewWithTag<Button>("crop-adjust") else field(a,"compositionButton") as Button;val compare=field(a,"compare")
                layout(root,w,h);layout(root,w,h)
                val strip=if(w>h)generateSequence(edit.parent as? ViewGroup){it.parent as? ViewGroup}.first{it.tag=="parameter-section:composition"} else edit.parent as ViewGroup
                assertNotSame(compare.parent,strip)
                if(w>h){
                    assertEquals("parameter-section:composition",strip.tag)
                    val sections=strip.parent as ViewGroup;val index=sections.indexOfChild(strip)
                    assertEquals("parameter-section:filmStock",sections.getChildAt(index-1).tag)
                    assertEquals("parameter-section:filmFormatMm",sections.getChildAt(index+1).tag)
                    assertTrue(sections.parent is android.widget.ScrollView)
                }else{
                    val index=strip.indexOfChild(edit)
                    assertEquals("filmStock",strip.getChildAt(index-1).tag)
                    assertEquals("filmFormatMm",strip.getChildAt(index+1).tag)
                }
                assertEquals(if(w>h)"crop-adjust" else "composition",edit.tag)
                assertFalse("No source cannot begin a draft",edit.isEnabled)
                assertTrue(edit.text.isNotBlank())
                if(w>h){
                    // A text Button exposes its visible text as its accessibility label;
                    // unlike the legacy icon action it need not duplicate contentDescription.
                    val node=edit.createAccessibilityNodeInfo()
                    try {
                        assertEquals(edit.text.toString(),node.text.toString())
                        assertFalse("No-source accessibility action stays disabled",node.isEnabled)
                    }finally{node.recycle()}
                }else assertTrue(edit.contentDescription.isNotBlank())
                if(w>h){
                    assertTrue("Landscape action keeps 48dp touch height",edit.height>=48)
                    assertTrue("Landscape action keeps 48dp touch width",edit.width>=48)
                }else assertEquals(1,edit.maxLines)
            }finally{controller.pause().stop().destroy()}
        }}finally{RuntimeEnvironment.setFontScale(1f)}
    }
}
