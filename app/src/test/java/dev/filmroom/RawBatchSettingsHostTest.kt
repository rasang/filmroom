package dev.filmroom

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.filmroom.i18n.L10n
import dev.filmroom.model.RawBatchMode
import dev.filmroom.model.RawBatchPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w393dp-h852dp-mdpi")
class RawBatchSettingsHostTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun root(a:SettingsActivity)=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun tagged(a:SettingsActivity,tag:String)=all(root(a)).single{it.tag==tag}
    private fun open(a:SettingsActivity):AlertDialog {
        tagged(a,"raw-batch-setting").performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also{assertTrue(it.isShowing)}
    }
    private fun idle(){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()}
    private fun select(dialog:AlertDialog,index:Int){dialog.listView.performItemClick(null,index,index.toLong());idle()}
    @Before fun reset(){app.getSharedPreferences("raw-processing-preferences",0).edit().clear().commit();L10n.choose(app,"zh")}
    @After fun clean(){app.getSharedPreferences("raw-processing-preferences",0).edit().clear().commit();L10n.choose(app,"zh")}

    @Test fun stableIdsUnknownAndWrongTypedValuesSafelyDefaultWithoutTouchingOtherPreferences(){
        val prefs=RawBatchPreferences(app)
        assertEquals(RawBatchMode.AUTO,prefs.mode)
        assertEquals(listOf("auto","small","standard","large"),RawBatchMode.entries.map{it.id})
        val store=app.getSharedPreferences("raw-processing-preferences",0)
        for(value in listOf("","LARGE","unknown","128"," auto ")){
            store.edit().putString("raw-batch-mode",value).commit()
            assertEquals(RawBatchMode.AUTO,RawBatchPreferences(app).mode)
        }
        store.edit().putInt("raw-batch-mode",256).commit()
        assertEquals(RawBatchMode.AUTO,RawBatchPreferences(app).mode)
        app.getSharedPreferences("editor-preferences",0).edit().putBoolean("reset-on-import",true).commit()
        for(mode in RawBatchMode.entries){
            prefs.mode=mode
            assertEquals(mode,RawBatchPreferences(app).mode)
            assertEquals(mode.id,store.getString("raw-batch-mode",null))
        }
        assertTrue(app.getSharedPreferences("editor-preferences",0).getBoolean("reset-on-import",false))
        val disk=java.io.File(app.applicationInfo.dataDir,"shared_prefs/raw-processing-preferences.xml")
        assertTrue("Confirmed app preference must reach disk, not only a retained Activity",disk.isFile)
        val document=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(disk)
        val nodes=document.getElementsByTagName("string")
        assertTrue((0 until nodes.length).any{val node=nodes.item(it);node.attributes.getNamedItem("name")?.nodeValue=="raw-batch-mode" && node.textContent=="large"})
    }
    @Test fun choiceIsOnlySavedOnApplyAndDoubleClickDoesNotOpenAnotherDialog(){
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
            val a=c.get();val prefs=RawBatchPreferences(a)
            assertEquals(a.getString(R.string.raw_batch_auto),(tagged(a,"raw-batch-value") as TextView).text.toString())
            val first=open(a);select(first,3)
            assertEquals(RawBatchMode.AUTO,prefs.mode)
            tagged(a,"raw-batch-setting").performClick();assertSame(first,ShadowAlertDialog.getLatestAlertDialog())
            first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle()
            assertEquals(RawBatchMode.AUTO,prefs.mode)
            val back=open(a);select(back,1);back.cancel();idle();assertEquals(RawBatchMode.AUTO,prefs.mode)
            val applied=open(a);select(applied,2);applied.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
            assertEquals(RawBatchMode.STANDARD,RawBatchPreferences(a).mode)
            assertEquals(a.getString(R.string.raw_batch_standard),(tagged(a,"raw-batch-value") as TextView).text.toString())
            assertTrue(tagged(a,"raw-batch-setting").contentDescription.toString().contains(a.getString(R.string.raw_batch_standard)))
        }
    }
    @Test fun recreationDiscardsUnconfirmedChoiceAndPersistsConfirmedAppSetting(){
        RawBatchPreferences(app).mode=RawBatchMode.SMALL
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
            val dialog=open(c.get());select(dialog,3);c.recreate()
            assertFalse(dialog.isShowing);assertEquals(RawBatchMode.SMALL,RawBatchPreferences(c.get()).mode)
            assertEquals(c.get().getString(R.string.raw_batch_small),(tagged(c.get(),"raw-batch-value") as TextView).text.toString())
        }
        // New Activity and new preferences instances model a cold re-entry, not just retained view state.
        for(language in L10n.supported){
            L10n.choose(app,language)
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
                assertEquals(RawBatchMode.SMALL,RawBatchPreferences(c.get()).mode)
                assertEquals(c.get().getString(R.string.raw_batch_small),(tagged(c.get(),"raw-batch-value") as TextView).text.toString())
                val dialog=open(c.get());assertEquals(1,dialog.listView.checkedItemPosition);dialog.cancel()
                val names=RawBatchMode.entries.map{c.get().getString(it.titleRes)}
                assertEquals(4,names.toSet().size);assertTrue(names.all{it.isNotBlank()})
            }
        }
    }
    @Test fun dialogChoiceLabelsWrapWithoutEllipsisAtLargeTextInEveryLanguage(){
        try{
            RuntimeEnvironment.setFontScale(2f)
            for(language in L10n.supported){
                L10n.choose(app,language)
                Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
                    val dialog=open(c.get());val list=dialog.listView
                    for(index in RawBatchMode.entries.indices){
                        val row=list.adapter.getView(index,null,list) as TextView
                        row.measure(View.MeasureSpec.makeMeasureSpec(272,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                        row.layout(0,0,row.measuredWidth,row.measuredHeight)
                        val text=row.layout ?: error("Choice label must be laid out")
                        for(line in 0 until text.lineCount){
                            assertEquals(0,text.getEllipsisCount(line))
                            assertTrue("Choice label wraps: $language/${row.text}",text.getLineMax(line)<=text.width+1)
                        }
                        assertTrue(text.getLineBottom(text.lineCount-1)<=row.height-row.compoundPaddingTop-row.compoundPaddingBottom+1)
                    }
                    dialog.cancel()
                }
            }
        }finally{RuntimeEnvironment.setFontScale(1f)}
    }

}
