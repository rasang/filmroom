package dev.filmroom

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.filmroom.effects.PhotoEffectActivity
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Real history card -> chooser -> explicit effect Activity, without launching GL/codec. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class EffectHistoryEntryHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    @Test fun bothHistoryOptionsCarryOriginalAndExportedUris() {
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { host ->
            val activity=host.get()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            val job=ExportJob(java.util.UUID.randomUUID().toString(),1,EditorState(),20,20,ExportFormat.JPEG,95,ExportJobState.COMPLETED)
                .copy(mediaUri="content://media/external/images/media/123",originalUri="content://photos/original/456")
            ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(activity,listOf(job))
            val action=all(activity.window.decorView).single{it.tag=="history-effects:${job.id}"}
            assertEquals(View.VISIBLE,action.visibility);assertTrue(action.isEnabled)
            for((index,kind) in listOf("LIGHT_TABLE","OPTICAL_LEAK").withIndex()) {
                action.performClick()
                val dialog=ShadowAlertDialog.getLatestAlertDialog()
                assertEquals(2,dialog.listView.adapter.count)
                dialog.listView.performItemClick(null,index,index.toLong())
                val intent=shadowOf(activity).nextStartedActivity
                assertNotNull(intent)
                assertEquals(PhotoEffectActivity::class.java.name,intent.component!!.className)
                assertEquals(kind,intent.getStringExtra(PhotoEffectActivity.KIND))
                assertEquals(job.mediaUri,intent.getStringExtra(PhotoEffectActivity.RESULT_URI))
                assertEquals(job.originalUri,intent.getStringExtra(PhotoEffectActivity.SOURCE_URI))
                assertEquals(job.id,intent.getStringExtra(PhotoEffectActivity.JOB_ID))
                dialog.dismiss()
            }
        }
    }
}
