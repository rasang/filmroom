package dev.filmroom

import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.export.ExportQueue
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Actual options dialog -> default ViewModel submit -> durable queue, not an encoder/device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalSizeExportDefaultHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    @Suppress("UNCHECKED_CAST")
    @Test fun dialogDefaultsToOriginalAndExplicitSmallChoiceDoesNotRewritePriorJob()=runBlocking {
        resetAndroidViewModelFactoryForHostTest()
        val app=ApplicationProvider.getApplicationContext<Application>()
        dev.filmroom.i18n.L10n.choose(app,"zh")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        val singleton=ExportQueue::class.java.getDeclaredField("instance").apply{isAccessible=true}
        val previous=singleton.get(null);singleton.set(null,null)
        val jobs=mutableListOf<String>()
        var queue:ExportQueue?=null
        val file=File(app.cacheDir,"original-size-authored.png")
        try {
            val full=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888)
            file.outputStream().use{assertTrue(full.compress(Bitmap.CompressFormat.PNG,100,it))};full.recycle()
            Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
                val activity=controller.get();val vm=ViewModelProvider(activity)[EditorViewModel::class.java]
                val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
                withTimeout(10000){while(!state.value.startupReady||state.value.busy!=null){shadowOf(Looper.getMainLooper()).idle();delay(5)}}
                repeat(5){shadowOf(Looper.getMainLooper()).idle();delay(5)}
                val preview=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
                val params=EditorState();val source=PhotoSource(file,"authored","image/png",3000,2000,file.length())
                state.value=EditorUiState(photo=source,original=preview,rendered=preview,parameters=params,renderedParameters=params)
                queue=withContext(Dispatchers.IO){ExportQueue.get(app)}
                fun dialog():AlertDialog {
                    MainActivity::class.java.getDeclaredMethod("exportOptionsDialog").apply{isAccessible=true}.invoke(activity)
                    return ShadowAlertDialog.getLatestAlertDialog()
                }
                suspend fun submit(d:AlertDialog):dev.filmroom.export.ExportJob {
                    val before=queue!!.jobs.value.map{it.id}.toSet()
                    d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    withTimeout(10000){while(queue!!.jobs.value.none{it.id !in before}||state.value.busy!=null){shadowOf(Looper.getMainLooper()).idle();delay(5)}}
                    return queue!!.jobs.value.single{it.id !in before}.also{jobs+=it.id;assertTrue(it.inputReady)}
                }
                val firstDialog=dialog()
                val firstRadios=all(firstDialog.window!!.decorView).filterIsInstance<RadioButton>()
                assertTrue(firstRadios.single{it.text.toString().startsWith("原尺寸")}.isChecked)
                val first=submit(firstDialog)
                assertNull(first.maxEdge);assertEquals(3000,first.width);assertEquals(2000,first.height)
                val secondDialog=dialog()
                val radios=all(secondDialog.window!!.decorView).filterIsInstance<RadioButton>()
                assertTrue("Each fresh dialog defaults to original",radios.single{it.text.toString().startsWith("原尺寸")}.isChecked)
                radios.single{it.text.toString().contains("2560")}.performClick()
                val second=submit(secondDialog)
                assertEquals(2560,second.maxEdge);assertEquals(2560,second.width)
                assertNull("Existing original job remains immutable",queue!!.store.get(first.id)!!.maxEdge)
                assertEquals(3000,queue!!.store.get(first.id)!!.width)
                assertTrue(File(queue!!.store.directory(first.id),"source.image").readBytes().contentEquals(file.readBytes()))
            }
        }finally {
            queue?.let{q->withContext(Dispatchers.IO){jobs.forEach{q.cancel(it)}}}
            file.delete();singleton.set(null,previous)
        }
    }
}
