package dev.filmroom.export

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.ExportQueueActivity
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchivedThumbnailHostTest {
    @Test(timeout=30000) fun missingLinkRetainsFailureMessageAcrossRefreshAndCannotOpen() {
        ExportQueue::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { host ->
                val a=host.get()
                val queue=ExportQueue.get(a)
                val source=ExportJob(ExportJobStore.newId(),1,EditorState(),32,24,ExportFormat.JPEG,95,ExportJobState.COMPLETED,
                    mediaUri="content://media/external/images/media/999",outputName="missing.jpg")
                queue.store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(source)))
                fun layout(){a.findViewById<ViewGroup>(android.R.id.content).let { root->root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));root.layout(0,0,400,900)}}
                fun cards()=ExportQueueActivity::class.java.getDeclaredField("cards").apply{isAccessible=true}.get(a) as Map<*,*>
                fun field(name:String):Any?=cards()[source.id]?.let{it.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(it)}
                fun until(condition:()->Boolean){repeat(300){shadowOf(android.os.Looper.getMainLooper()).idle();layout();if(condition())return;Thread.sleep(10)};error("timed out")}
                until{field("archivedUnavailable")==true}
                val message=field("message") as android.widget.TextView
                assertTrue(message.text.isNotEmpty());val expected=message.text.toString()
                ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,queue.store.jobs.value)
                assertEquals(expected,message.text.toString());assertEquals(View.VISIBLE,message.visibility)
                (field("open") as View).performClick();(field("open") as View).performClick()
                until{field("archivedOpenPending")==false}
                assertNull(shadowOf(a).nextStartedActivity)
                assertEquals(0,queue.store.jobs.value.count{it.canRetry})
                val provider=object:android.content.ContentProvider() {
                    override fun onCreate()=true
                    override fun query(uri:android.net.Uri,p:Array<out String>?,s:String?,args:Array<out String>?,sort:String?)=
                        android.database.MatrixCursor(p).apply{addRow(p!!.map{when(it){"_display_name"->"missing.jpg";"owner_package_name"->a.packageName;"relative_path"->"Pictures/暗房/";else->null}}.toTypedArray())}
                    override fun getType(uri:android.net.Uri)="image/jpeg"
                    override fun insert(uri:android.net.Uri,v:android.content.ContentValues?):android.net.Uri?=error("no write")
                    override fun delete(uri:android.net.Uri,s:String?,args:Array<out String>?)=error("no write")
                    override fun update(uri:android.net.Uri,v:android.content.ContentValues?,s:String?,args:Array<out String>?)=error("no write")
                }
                provider.attachInfo(a,android.content.pm.ProviderInfo().apply{authority="media"})
                org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider)
                val png=java.io.ByteArrayOutputStream().also{out->Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).let{it.compress(Bitmap.CompressFormat.PNG,100,out);it.recycle()}}.toByteArray()
                shadowOf(a.contentResolver).registerInputStreamSupplier(android.net.Uri.parse(source.mediaUri)){png.inputStream()}
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.cancel()
                a.savedThumbnailDecoder={Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888)}
                host.pause().resume()
                until{field("archivedUnavailable")==false && (field("previewPlaceholder") as? View)?.visibility==View.GONE}
                (field("open") as View).performClick();(field("open") as View).performClick()
                until{field("archivedOpenPending")==false}
                assertEquals(android.net.Uri.parse(source.mediaUri),shadowOf(a).nextStartedActivity.data)
                assertNull(shadowOf(a).nextStartedActivity)
            }
        } finally {}
    }
}
