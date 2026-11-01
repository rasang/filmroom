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
                val edit=field(a,"compositionButton") as Button;val compare=field(a,"compare")
                layout(root,w,h);layout(root,w,h)
                val strip=edit.parent as ViewGroup
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
                assertEquals("composition",edit.tag)
                assertFalse("No source cannot begin a draft",edit.isEnabled)
                assertTrue(edit.text.isNotBlank());assertTrue(edit.contentDescription.isNotBlank())
                assertEquals(1,edit.maxLines)
            }finally{controller.pause().stop().destroy()}
        }}finally{RuntimeEnvironment.setFontScale(1f)}
    }
}
