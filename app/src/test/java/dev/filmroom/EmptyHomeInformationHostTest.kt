package dev.filmroom

import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.i18n.L10n
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Activity and repository PNG import; preview science is stubbed, not a device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmptyHomeInformationHostTest {
    @Before fun prepare(){
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        L10n.choose(app,"zh")
    }
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun info(a:MainActivity)=MainActivity::class.java.getDeclaredField("infoButton").apply{isAccessible=true}.get(a) as Button
    private fun await(condition:()->Boolean){
        val deadline=System.nanoTime()+10_000_000_000L
        do{Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));if(condition())return;Thread.sleep(5)}while(System.nanoTime()<deadline)
        fail("Timed out waiting for editor state")
    }
    private fun operationDone(vm:EditorViewModel)= (EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}.get(vm) as? Job)?.isCompleted==true
    @Test(timeout=30000) fun emptyHomeHasNoAccountFootnoteOrInactiveInformationAcrossLanguages(){
        try{for(tag in L10n.supported){
            L10n.choose(RuntimeEnvironment.getApplication(),tag)
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val vm=ViewModelProvider(c.get())[EditorViewModel::class.java]
                await{vm.state.value.startupReady && operationDone(vm)}
                assertNull(vm.state.value.photo)
                assertEquals(View.GONE,info(c.get()).visibility)
                assertEquals(View.GONE,(MainActivity::class.java.getDeclaredField("compare").apply{isAccessible=true}.get(c.get()) as View).visibility)
                assertFalse(views(c.get().window.decorView).filterIsInstance<TextView>().any{it.text.toString()==L10n.text(R.string.text_ee2241639ab0)})
            }
        }}finally{L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
    }
    @Test(timeout=30000) fun rawAndHeicCompatibilityNotesKeepTheirInformationEntry(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val vm=ViewModelProvider(a)[EditorViewModel::class.java]
            await{vm.state.value.startupReady && operationDone(vm)}
            @Suppress("UNCHECKED_CAST")
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
            for(mime in listOf("image/x-adobe-dng","image/heic")){
                val warning="Compatibility note fixture: $mime"
                val source=dev.filmroom.io.PhotoSource(File(a.cacheDir,"information-note"),"fixture",mime,32,24,100,developmentWarning=warning)
                state.value=EditorUiState(photo=source)
                await{info(a).visibility==View.VISIBLE}
                info(a).performClick();val dialog=ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(dialog.isShowing)
                assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text.contains(warning)})
                dialog.dismiss()
            }
            state.value=EditorUiState()
            await{info(a).visibility==View.GONE}
        }
    }
    @Test(timeout=30000) fun importedPhotoInformationRemainsAccessibleAfterCancelledReplacement(){
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val vm=ViewModelProvider(a)[EditorViewModel::class.java]
            await{vm.state.value.startupReady && operationDone(vm)}
            vm.localDetailEnabled=false
            vm.renderPreview={b,_,_,_->b.copy(Bitmap.Config.ARGB_8888,false)}
            val input=File(app.cacheDir,"information-import.png")
            Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply{input.outputStream().use{compress(Bitmap.CompressFormat.PNG,100,it)};recycle()}
            val uri=Uri.parse("content://home-info/photo.png")
            Shadows.shadowOf(app.contentResolver).registerInputStream(uri,input.inputStream())
            vm.importPhoto(uri);await{operationDone(vm) && vm.state.value.busy==null && !vm.state.value.updating}
            val source=checkNotNull(vm.state.value.photo){"Import failed: ${vm.state.value.message}"}
            assertArrayEquals(input.readBytes(),source.localFile.readBytes())
            await{info(a).visibility==View.VISIBLE}
            assertTrue(info(a).performClick())
            var dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text.contains(source.name) && it.text.contains("32 × 24")})
            dialog.dismiss()
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val blocked=Uri.parse("content://home-info/cancel")
            Shadows.shadowOf(app.contentResolver).registerInputStream(blocked,object:java.io.InputStream(){
                override fun read():Int{entered.countDown();release.await(5,TimeUnit.SECONDS);return -1}
                override fun read(b:ByteArray,off:Int,len:Int)=read()
            })
            try{
                vm.importPhoto(blocked);await{entered.count==0L}
                vm.cancel();release.countDown();await{operationDone(vm) && vm.state.value.busy==null}
                assertSame(source,vm.state.value.photo)
                await{info(a).visibility==View.VISIBLE};info(a).performClick()
                dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing);dialog.dismiss()
            }finally{release.countDown();vm.cancel()}
        }
        // Clear the persisted session, then create a fresh Activity/VM: no stale information action.
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val vm=ViewModelProvider(c.get())[EditorViewModel::class.java]
            await{vm.state.value.startupReady && operationDone(vm)}
            assertNull(vm.state.value.photo);assertEquals(View.GONE,info(c.get()).visibility)
        }
    }
}
