package dev.filmroom

import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.activity.ComponentActivity
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.presets.SavedPresetDialogs
import dev.filmroom.presets.SavedPresetStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Host measurements separate a delayed list read from an actual main-thread disk/JSON stall. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedPresetListPerformanceHostTest {
    data class Timing(val stage:String,val nanos:Long,val main:Boolean)
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun await(done:()->Boolean) {
        repeat(1000){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));if(done())return;Thread.sleep(5)}
        fail("Recipe list did not become ready")
    }
    private fun measure(label:String,body:()->Unit):Double {
        val start=System.nanoTime();body();val millis=(System.nanoTime()-start)/1e6
        println("RECIPE_PROFILE $label ms=$millis")
        return millis
    }
    @Test(timeout=60000) fun coldMaximumListReadsAndParsesOffMainAndCancelledLoadsCannotReopen() {
        val app=RuntimeEnvironment.getApplication()
        dev.filmroom.i18n.L10n.choose(app,"zh")
        val file=File(app.filesDir,"saved-presets.json");file.delete();File(file.path+".bak").delete()
        val snapshot=SavedPresetStore.snapshot(EditorState(brightness=.7f))
        val hash=MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()).joinToString(""){"%02x".format(it)}
        val rows=JSONArray()
        repeat(200){i->rows.put(JSONObject().put("id",UUID.nameUUIDFromBytes("recipe-$i".toByteArray()).toString()).put("name","配方 $i").put("createdAt",i.toLong()).put("parameters",snapshot).put("sha256",hash))}
        file.writeText(JSONObject().put("version",1).put("presets",rows).toString())
        assertTrue(file.length()<=2*1024*1024)
        println("RECIPE_PROFILE count=200 bytes=${file.length()}")
        // Explicitly measure the unchanged on-disk format: byte read, JSON tree, full integrity work.
        lateinit var bytes:ByteArray
        measure("fixture disk bytes"){bytes=file.readBytes()}
        measure("fixture JSON tree"){assertEquals(200,JSONObject(String(bytes,Charsets.UTF_8)).getJSONArray("presets").length())}
        measure("unchanged store list including hashes"){assertEquals(200,SavedPresetStore(app).list().size)}
        val timing=CopyOnWriteArrayList<Timing>()
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            val activity=host.get();activity.setTheme(R.style.AppTheme)
            val state=EditorUiState(photo=PhotoSource(File(activity.cacheDir,"recipe-timing.png"),"fixture","image/png",96,64,1),parameters=EditorState())
            var applies=0
            val dialogs=SavedPresetDialogs(activity,{state},{_,_,_->applies++;true},{it.show()})
            val releaseFirstRead=java.util.concurrent.CountDownLatch(1)
            var blockFirstRead=true
            dialogs.timingObserver={stage,nanos,main->
                timing.add(Timing(stage,nanos,main))
                if(stage=="store read and names" && blockFirstRead) { blockFirstRead=false;check(releaseFirstRead.await(10,java.util.concurrent.TimeUnit.SECONDS)) }
            }
            dialogs.open();val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertTrue("Saving must not wait on the list read",dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
            val list=all(dialog.window!!.decorView).filterIsInstance<ListView>().single()
            val posted=booleanArrayOf(false)
            android.os.Handler(Looper.getMainLooper()).post{posted[0]=true}
            await{dialog.window!!.decorView.width>0 && dialog.window!!.decorView.height>0}
            val decor=dialog.window!!.decorView
            val status=all(decor).filterIsInstance<android.widget.TextView>().first{it.text.toString() in listOf(dev.filmroom.i18n.L10n.text(R.string.saved_preset_choose),dev.filmroom.i18n.L10n.text(R.string.saved_preset_loading))}
            val loadingHeight=decor.height;val loadingStatusHeight=status.height
            val screen=android.graphics.Bitmap.createBitmap(decor.width,decor.height,android.graphics.Bitmap.Config.ARGB_8888)
            measure("first explicit host draw"){decor.draw(android.graphics.Canvas(screen))}
            screen.recycle();releaseFirstRead.countDown()
            measure("await complete rows host wall time"){await{posted[0] && list.adapter?.count==200}}
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
            measure("loaded list explicit host draw"){
                val bitmap=android.graphics.Bitmap.createBitmap(decor.width,decor.height,android.graphics.Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(bitmap));bitmap.recycle()
            }
            println("RECIPE_GEOMETRY loadingDialog=$loadingHeight loadedDialog=${decor.height} loadingStatus=$loadingStatusHeight loadedStatus=${status.height}")
            assertEquals("Loading must not resize the recipe dialog",loadingHeight,decor.height)
            assertEquals("Loading must not resize the instructions",loadingStatusHeight,status.height)
            assertTrue(timing.any{it.stage=="store read and names" && !it.main})
            assertTrue(timing.filter{it.stage=="list bind" || it.stage=="dialog create"}.all{it.main})
            list.performItemClick(list.adapter.getView(0,null,list),0,0)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{applies==1}
            assertTrue(timing.any{it.stage=="apply parse and stocks" && !it.main})
            repeat(2) { pass ->
                measure("warm open ${pass+1} through loaded rows") {
                    dialogs.open();val warm=ShadowAlertDialog.getLatestAlertDialog()
                    val rows=all(warm.window!!.decorView).filterIsInstance<ListView>().single()
                    await{rows.adapter?.count==200}
                    warm.cancel();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
                }
            }
            dialogs.open();val cancelled=ShadowAlertDialog.getLatestAlertDialog();cancelled.cancel()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            assertFalse(cancelled.isShowing);assertNull(dialogs.capture());assertEquals(1,applies)
            timing.forEach{println("RECIPE_PROFILE ${it.stage} ms=${it.nanos/1e6} main=${it.main}")}
            dialogs.close()
        }
        file.delete();File(file.path+".bak").delete()
    }
}
