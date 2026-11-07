package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.filmroom.i18n.L10n
import dev.filmroom.model.EditorPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real SettingsActivity native view drawing on host; not a phone screenshot or touch-performance test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSectionsLayoutHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(root:View,w:Int,h:Int){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)}
    private fun capture(root:View,w:Int,h:Int,name:String){
        val out=File(System.getenv("FILMROOM_SETTINGS_SECTIONS_QA_DIR") ?: "build/ui-host-renders/settings-sections").apply{mkdirs()}
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap));File(out,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun sectionsRemainReadableAlignedAndScrollableInEveryLanguageAndSize(){
        val app=RuntimeEnvironment.getApplication()
        try{for(language in L10n.supported)for(font in listOf(1f,2f))for((w,h) in listOf(320 to 740,393 to 852,800 to 393)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h)"land" else "port"}-mdpi")
            RuntimeEnvironment.setFontScale(font);L10n.choose(app,language)
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
                val a=c.get();val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                layout(root,w,h);layout(root,w,h)
                fun tagged(tag:String)=all(root).single{it.tag==tag}
                val scroll=tagged("settings-scroll") as ScrollView
                val content=tagged("settings-content") as ViewGroup
                val cards=all(root).filter{(it.tag as? String)?.startsWith("settings-section:")==true}
                assertEquals(listOf("settings-section:language","settings-section:photos","settings-section:history","settings-section:backup","settings-section:diagnostics"),cards.map{it.tag})
                assertTrue(all(tagged("language-setting")).filterIsInstance<TextView>().any{it.text.toString()==L10n.multilingualTitle})
                assertTrue(all(tagged("language-setting")).filterIsInstance<TextView>().any{it.text.toString()==L10n.multilingualDescription})
                for((previous,nextKey) in listOf(cards[0] to "photos",cards[1] to "history",cards[2] to "backup",cards[3] to "diagnostics")){
                    val heading=tagged("settings-heading:$nextKey")
                    assertTrue("Groups retain visible breathing space",heading.top-previous.bottom>=24)
                }
                val history=tagged("settings-section:history") as ViewGroup
                for(key in listOf("single-delete-mode","clear-history-mode")){
                    val setting=tagged("history-setting:$key")
                    val value=tagged("history-value:$key") as TextView
                    assertSame("Both independently saved choices belong to history",history,setting.parent)
                    assertTrue("History targets remain at least 48dp",setting.height>=48)
                    assertTrue("Each history preference has a visible value",value.text.isNotBlank())
                    assertTrue("History controls stay inside their card",setting.left>=0 && setting.right<=history.width)
                }
                val dividers=all(root).filter{it.tag=="settings-divider"}
                assertTrue("Fine rules separate preference rows and log detail",dividers.size>=2)
                dividers.forEach{assertEquals(1,it.height);assertTrue(it.width>0)}
                val preferenceRows=all(root).filter{it.tag=="settings-preference-row"}.map{it as ViewGroup}
                assertEquals(2,preferenceRows.size)
                preferenceRows.forEach{row->
                    val labels=row.getChildAt(0);val toggle=row.getChildAt(1) as Switch
                    assertTrue("Descriptions cannot overlap switches",labels.right<=toggle.left)
                    assertTrue(toggle.right<=row.width);assertTrue(toggle.width>=48);assertTrue(toggle.height>=48)
                }
                val prefix="$language-${w}x${h}-font$font"
                capture(root,w,h,"$prefix-top")
                // Empty EditText.text does not expose hint glyph bounds: inspect its actual hint layout.
                val input=all(root).filterIsInstance<android.widget.EditText>().single()
                assertEquals("1–1440",input.hint.toString())
                val hintLayout=TextView::class.java.getDeclaredField("mHintLayout").apply{isAccessible=true}.get(input) as android.text.Layout
                assertEquals("Range hint stays on one line",1,hintLayout.lineCount)
                assertEquals(0,hintLayout.getEllipsisCount(0))
                assertTrue("Hint glyphs cannot clip at $language/$font/$w",hintLayout.getLineMax(0)<=input.width-input.compoundPaddingLeft-input.compoundPaddingRight+1)
                assertTrue("Hint height cannot clip",hintLayout.getLineBottom(0)<=input.height-input.compoundPaddingTop-input.compoundPaddingBottom+1)
                fun readable(){all(root).filterIsInstance<TextView>().filter{it.isShown&&it.text.isNotEmpty()}.forEach{v->
                    val textLayout=v.layout ?: error("Text has not been laid out: ${v.text}")
                    for(line in 0 until textLayout.lineCount){
                        assertEquals("No ellipsis: $language/$font/$w ${v.text}",0,textLayout.getEllipsisCount(line))
                        if(textLayout.getLineWidth(line)>textLayout.width+1){
                            val out=File(System.getenv("FILMROOM_SETTINGS_SECTIONS_QA_DIR") ?: "build/ui-host-renders/settings-sections").apply{mkdirs()}
                            File(out,"wrap-space-evidence.txt").appendText("$prefix line=$line raw=${textLayout.getLineWidth(line)} visible=${textLayout.getLineMax(line)} width=${textLayout.width} text=[${v.text.subSequence(textLayout.getLineStart(line),textLayout.getLineEnd(line))}]\n")
                        }
                        // getLineWidth includes a trailing wrap-space; getLineMax measures visible content.
                        assertTrue("No horizontal glyph clipping: $language/$font/$w line=$line max=${textLayout.getLineMax(line)} raw=${textLayout.getLineWidth(line)} width=${textLayout.width} text=${v.text}",textLayout.getLineMax(line)<=textLayout.width+1)
                    }
                    assertTrue("No vertical clipping: $language/$font/$w ${v.text}",textLayout.getLineBottom(textLayout.lineCount-1)<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+1)
                }}
                readable();assertEquals(View.GONE,tagged("settings-log-notes").visibility)
                assertEquals(View.VISIBLE,tagged("settings-log-summary").visibility)
                assertTrue("Content remains scrollable rather than shrinking",content.height>scroll.height)
                scroll.scrollTo(0,(content.height-scroll.height).coerceAtLeast(0))
                val share=SettingsActivity::class.java.getDeclaredField("share").apply{isAccessible=true}.get(a) as Button
                val actionXY=IntArray(2);share.getLocationInWindow(actionXY);val scrollXY=IntArray(2);scroll.getLocationInWindow(scrollXY)
                assertTrue("TXT action fully reachable",actionXY[1]>=scrollXY[1]&&actionXY[1]+share.height<=scrollXY[1]+scroll.height)
                capture(root,w,h,"$prefix-bottom")
                tagged("settings-log-notes-toggle").performClick();layout(root,w,h);layout(root,w,h)
                assertEquals(View.VISIBLE,tagged("settings-log-notes").visibility);readable()
                scroll.scrollTo(0,(content.height-scroll.height).coerceAtLeast(0))
                capture(root,w,h,"$prefix-details")
            }
        }}finally{RuntimeEnvironment.setFontScale(1f);RuntimeEnvironment.setQualifiers("w393dp-h852dp-mdpi");L10n.choose(app,"zh")}
    }
    @Test fun preferenceClicksAndExpandedNotesSurviveRecreationWithoutChangingOtherSettings(){
        val app=RuntimeEnvironment.getApplication();L10n.choose(app,"zh")
        val prefs=EditorPreferences(app);val reset=prefs.resetOnImport;val restore=prefs.restoreLastPhoto
        try{Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
            fun root()=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val rows=all(root()).filter{it.tag=="settings-preference-row"}.map{it as ViewGroup}
            rows[0].getChildAt(0).performClick();assertEquals(!reset,prefs.resetOnImport);assertEquals(restore,prefs.restoreLastPhoto)
            (rows[1].getChildAt(1) as Switch).performClick();assertEquals(!restore,prefs.restoreLastPhoto)
            all(root()).single{it.tag=="settings-log-notes-toggle"}.performClick()
            c.recreate()
            assertEquals(View.VISIBLE,all(root()).single{it.tag=="settings-log-notes"}.visibility)
            assertEquals(listOf(!reset,!restore),all(root()).filterIsInstance<Switch>().map{it.isChecked})
        }}finally{prefs.resetOnImport=reset;prefs.restoreLastPhoto=restore}
    }
}
